#!/usr/bin/env python3
"""Scan a modded Minecraft instance and generate a ServerHub registry manifest JSON.

Usage:
    python tools/scan_client_registries.py "<instance_dir>" --pack-id atm11 --out atm11.json
"""

import argparse
import collections
import glob
import json
import os
import re
import zipfile

SYNCED_MINECRAFT_REGISTRIES = (
    "banner_pattern",
    "cat_sound_variant",
    "cat_variant",
    "chat_type",
    "chicken_sound_variant",
    "chicken_variant",
    "cow_sound_variant",
    "cow_variant",
    "damage_type",
    "dialog",
    "enchantment",
    "frog_variant",
    "instrument",
    "jukebox_song",
    "painting_variant",
    "pig_sound_variant",
    "pig_variant",
    "timeline",
    "trim_material",
    "trim_pattern",
    "wolf_sound_variant",
    "wolf_variant",
    "world_clock",
    "worldgen/biome",
    "zombie_nautilus_variant",
)

EXTRA_MODDED_REGISTRIES = {
    ("forbidden_arcanus", "item_modifier"): "forbidden_arcanus:item_modifier",
}

STATIC_TAG_REGISTRIES = (
    "block",
    "entity_type",
    "fluid",
    "game_event",
    "item",
    "point_of_interest_type",
)

TAG_SCAN_REGISTRIES = SYNCED_MINECRAFT_REGISTRIES + STATIC_TAG_REGISTRIES

MOD_ID_TOML_RE = re.compile(r'^\s*modId\s*=\s*"([^"]+)"', re.MULTILINE)


def detect_mc_version(instance_dir: str) -> str:
    for fname in ("minecraftinstance.json", "manifest.json"):
        path = os.path.join(instance_dir, fname)
        if not os.path.exists(path):
            continue
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            if "baseModLoader" in data and isinstance(data["baseModLoader"], dict):
                mc = data["baseModLoader"].get("minecraftVersion")
                if mc:
                    return str(mc)
            if "minecraft" in data and isinstance(data["minecraft"], dict):
                mc = data["minecraft"].get("version")
                if mc:
                    return str(mc)
        except Exception:
            pass
    return ""


def collect_mod_ids(z: zipfile.ZipFile, mod_ids: set[str]) -> None:
    for toml_name in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
        if toml_name in z.namelist():
            try:
                text = z.read(toml_name).decode("utf-8", errors="ignore")
                for match in MOD_ID_TOML_RE.findall(text):
                    if match not in ("minecraft", "neoforge", "forge", "javafml"):
                        mod_ids.add(match)
            except Exception:
                pass


def scan_instance(instance_dir: str, pack_id: str) -> dict:
    mods_dir = os.path.join(instance_dir, "mods")
    jars = sorted(glob.glob(os.path.join(mods_dir, "*.jar")))
    if not jars:
        raise SystemExit(f"No .jar files found in {mods_dir}")

    mod_ids: set[str] = set()
    registries: dict[str, set[str]] = collections.defaultdict(set)
    tags: dict[str, set[str]] = collections.defaultdict(set)

    for jar in jars:
        try:
            z = zipfile.ZipFile(jar)
        except Exception:
            continue
        with z:
            collect_mod_ids(z, mod_ids)
            for name in z.namelist():
                if not name.startswith("data/") or not name.endswith(".json"):
                    continue
                parts = name.split("/")
                if len(parts) < 4:
                    continue
                ns = parts[1]
                if ns != "minecraft":
                    mod_ids.add(ns)

                # Tags: data/<tag_ns>/tags/<reg_path...>/<tag_name>.json
                if parts[2] == "tags" and len(parts) >= 5:
                    rel_after_tags = "/".join(parts[3:])
                    for reg in TAG_SCAN_REGISTRIES:
                        prefix = reg + "/"
                        if rel_after_tags.startswith(prefix):
                            tag_name = rel_after_tags[len(prefix) : -len(".json")]
                            tags[f"minecraft:{reg}"].add(f"{ns}:{tag_name}")
                            break
                    continue

                # Modded dynamic registry: data/<entry_ns>/<reg_ns>/<reg_path>/<entry>.json
                if len(parts) >= 5 and (parts[2], parts[3]) in EXTRA_MODDED_REGISTRIES:
                    reg_id = EXTRA_MODDED_REGISTRIES[(parts[2], parts[3])]
                    entry_name = "/".join(parts[4:])[: -len(".json")]
                    if ns != "minecraft":
                        registries[reg_id].add(f"{ns}:{entry_name}")
                    continue

                # Standard synced minecraft registry: data/<entry_ns>/<reg_path>/<entry>.json
                rel_after_ns = "/".join(parts[2:])
                for reg in SYNCED_MINECRAFT_REGISTRIES:
                    prefix = reg + "/"
                    if rel_after_ns.startswith(prefix):
                        if ns != "minecraft":
                            entry_name = rel_after_ns[len(prefix) : -len(".json")]
                            registries[f"minecraft:{reg}"].add(f"{ns}:{entry_name}")
                        break

    mc_version = detect_mc_version(instance_dir)
    if mc_version.startswith("26."):
        # 26.x vanilla item tag referenced by modded enchantments but absent on 1.21.1 Hubs.
        tags["minecraft:item"].add("minecraft:enchantable/melee_weapon")

    return {
        "packId": pack_id,
        "minecraftVersion": mc_version,
        "modIds": sorted(mod_ids),
        "registries": {k: sorted(v) for k, v in sorted(registries.items()) if v},
        "tags": {k: sorted(v) for k, v in sorted(tags.items()) if v},
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate a ServerHub registry manifest from a modpack instance.")
    parser.add_argument("instance_dir", help="Path to the Minecraft instance folder containing mods/")
    parser.add_argument("--pack-id", default="", help="Manifest packId (defaults to instance folder name)")
    parser.add_argument("--out", default="", help="Output JSON path (defaults to <pack-id>.json)")
    args = parser.parse_args()

    instance_dir = os.path.abspath(args.instance_dir)
    pack_id = args.pack_id or re.sub(r"[^a-z0-9_-]+", "-", os.path.basename(instance_dir).lower()).strip("-")
    out_path = args.out or f"{pack_id}.json"

    manifest = scan_instance(instance_dir, pack_id)
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")

    total_entries = sum(len(v) for v in manifest["registries"].values())
    total_tags = sum(len(v) for v in manifest["tags"].values())
    print(
        f"Wrote {out_path}: packId={pack_id}, mc={manifest['minecraftVersion']}, "
        f"mods={len(manifest['modIds'])}, entries={total_entries}, tags={total_tags}"
    )


if __name__ == "__main__":
    main()
