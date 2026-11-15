# ServerHub

Server-side NeoForge mod for running a shared `1.21.1` Hub server alongside multiple modpack servers (including newer Minecraft versions such as `26.1.2` via Velocity + ViaVersion).

## Branches

- `phase-1` (default): `1.21.1` NeoForge build (runs on the Hub and `1.21.1` game servers).
- `phase-1-mc26.1.2`: `26.1.2` NeoForge build (runs on `26.1.2` game servers such as ATM11).

## Generating a Registry Manifest for a New Modpack

When players join the `1.21.1` Hub from a modpack whose mods or Minecraft version are not installed on the Hub, NeoForge still expects the server to send every data-driven registry entry and tag defined by that client's mods.

You do not need to edit code or rebuild the Hub to support a new modpack. Run the scanner script against a client or server instance of the modpack:

```bash
python tools/scan_client_registries.py "<path to modpack instance>" --pack-id <pack-name> --out <pack-name>.json
```

Example (ATM11 on Windows):

```powershell
python tools/scan_client_registries.py "C:\Users\Dewey\curseforge\minecraft\Instances\All the Mods 11 - ATM11" --pack-id atm11 --out atm11.json
```

Then install it on the Hub:

1. Copy `<pack-name>.json` into `config/serverhub/registry-manifests/` on the Hub server.
2. Run `/hubadmin reload` in-game or from the Hub console (no restart required).

ServerHub automatically matches joining players to a manifest by their reported mod namespaces and sends empty stub entries and tags for anything missing on the Hub.

## Building

```bash
./gradlew build
```

The compiled jar is written to `build/libs/serverhub-1.0.0.jar`.
