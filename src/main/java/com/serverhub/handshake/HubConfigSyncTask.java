package com.serverhub.handshake;

import com.serverhub.config.HubConfig;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.neoforged.neoforge.network.ConfigSync;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.configuration.SyncConfig;
import net.neoforged.neoforge.network.payload.ConfigFilePayload;

/**
 * Sends Hub server configs and empty fallback server configs for client mods so modded clients
 * initialize their {@code ModConfig.Type.SERVER} specs with defaults when joining the Hub.
 */
public record HubConfigSyncTask(ServerConfigurationPacketListener listener) implements ICustomConfigurationTask {
    private static final List<String> KNOWN_SERVER_CONFIG_FILES = List.of(
            "neoforge-server.toml",
            "actuallyadditions-server.toml",
            "additionalplacements-server.toml",
            "ae2-server.toml",
            "aether-server.toml",
            "allthearcanistgear-server.toml",
            "almostunified-server.toml",
            "amendments-server.toml",
            "apokinetics-server.toml",
            "apotheosis-server.toml",
            "apothic_enchanting-server.toml",
            "apothic_spawners-server.toml",
            "appmek-server.toml",
            "architectury-server.toml",
            "ars_additions-server.toml",
            "ars_controle-server.toml",
            "ars_creo-server.toml",
            "ars_nouveau-server.toml",
            "ars_ocultas-server.toml",
            "ars_ocultas/base-server.toml",
            "ars_technica-server.toml",
            "ars_unification-server.toml",
            "artifacts-server.toml",
            "athena-server.toml",
            "atlasviewer-server.toml",
            "betterthirdperson-server.toml",
            "bhc-server.toml",
            "buildinggadgets-server.toml",
            "buildinggadgets2-server.toml",
            "charginggadgets-server.toml",
            "cloth_config-server.toml",
            "cobblemon-server.toml",
            "cobblenav-server.toml",
            "cobbreeding-server.toml",
            "cobweb-server.toml",
            "comforts-server.toml",
            "commoncapabilities-server.toml",
            "commonnetworking-server.toml",
            "computercraft-server.toml",
            "crashutilities-server.toml",
            "create-server.toml",
            "create_aquatic_ambitions-server.toml",
            "create_dragons_plus-aether-integration-server.toml",
            "create_dragons_plus-server.toml",
            "create_dragons_plus-simulated-extension-server.toml",
            "create_enchantment_industry-apotheosis-server.toml",
            "create_enchantment_industry-apothic_enchanting-server.toml",
            "create_enchantment_industry-server.toml",
            "create_enchantment_industry-touhou_little_maid-server.toml",
            "create_hypertube-server.toml",
            "cristellib-server.toml",
            "curios-server.toml",
            "cyclopscore-server.toml",
            "diagonalblocks-server.toml",
            "dimstorage-server.toml",
            "easy_villagers-server.toml",
            "edivadlib-server.toml",
            "elevatorid-server.toml",
            "emi-server.toml",
            "endercore-server.toml",
            "enderio-server.toml",
            "enderio_conduits_modded-server.toml",
            "enderio_machines-server.toml",
            "epitaphs-server.toml",
            "evolvedmekanism-server.toml",
            "expanded_ecosphere-server.toml",
            "farmersdelight-server.toml",
            "fissionrecipe-server.toml",
            "fluxnetworks-server.toml",
            "flywheel-server.toml",
            "framedblocks-server.toml",
            "ftbchunks-server.toml",
            "ftbteams-server.toml",
            "ftbultimine-server.toml",
            "fzzy_config-server.toml",
            "geckolib-server.toml",
            "generatorgalore-server.toml",
            "getittogetherdrops-server.toml",
            "glodium-server.toml",
            "gravestone-server.toml",
            "holeydigsite-crates.toml",
            "holeydigsite-server.toml",
            "immersiveengineering-server.toml",
            "incontrol-server.toml",
            "integratedcrafting-server.toml",
            "integrateddynamics-server.toml",
            "integrateddynamicscompat-server.toml",
            "integratedscripting-server.toml",
            "integratedterminals-server.toml",
            "integratedterminalscompat-server.toml",
            "integratedtunnels-server.toml",
            "integratedtunnelscompat-server.toml",
            "jade-server.toml",
            "jaopca-server.toml",
            "jei-server.toml",
            "journeymap-server.toml",
            "jumpboat-server.toml",
            "justdirethings-server.toml",
            "kotlinforforge-server.toml",
            "kubejs-server.toml",
            "laserio-server.toml",
            "letmedespawn-server.toml",
            "libx-server.toml",
            "lithium-server.toml",
            "lostcities-server.toml",
            "mahoutsukai-server.toml",
            "mcjtylib-server.toml",
            "mekanism-server.toml",
            "mekanismgenerators-server.toml",
            "mekanismtools-server.toml",
            "mekanisticrouters-server.toml",
            "mekmm-server.toml",
            "minecraft-server.toml",
            "mob_grinding_utils-server.toml",
            "modern_industrialization-server.toml",
            "modernui-server.toml",
            "modonomicon-server.toml",
            "modularbees-server.toml",
            "modularrouters-server.toml",
            "moonlight-server.toml",
            "more_cobblemon_tweaks-server.toml",
            "moredragoneggs-server.toml",
            "morered-server.toml",
            "not_enough_glyphs-server.toml",
            "occultism-server.toml",
            "octolib-server.toml",
            "palladium-server.toml",
            "patchouli-server.toml",
            "per_viam_invenire-server.toml",
            "pipez-server.toml",
            "ponder-server.toml",
            "productivebees-server.toml",
            "productivefarming-server.toml",
            "productivelib-server.toml",
            "productivetrees-server.toml",
            "puzzleslib-server.toml",
            "pylons-server.toml",
            "radium-server.toml",
            "railcraft-server.toml",
            "rangedpumps-server.toml",
            "rctapi-server.toml",
            "rctmod-server.toml",
            "refinedstorage-server.toml",
            "rei-server.toml",
            "resourcefullib-server.toml",
            "rftoolsbase-server.toml",
            "rftoolsbuilder-server.toml",
            "rftoolspower-server.toml",
            "rftoolsstorage-server.toml",
            "rftoolsutility-server.toml",
            "sable-server.toml",
            "searchables-server.toml",
            "securitycraft-server.toml",
            "sfm-ai.toml",
            "sfm-server.toml",
            "silentgear-server.toml",
            "simplebackups-common-experimental.toml",
            "simplebackups-server.toml",
            "skyblockbuilder-server.toml",
            "smartbrainlib-server.toml",
            "sodium-server.toml",
            "solcarrot-server.toml",
            "sophisticatedbackpacks-server.toml",
            "sophisticatedcore-server.toml",
            "sophisticatedstorage-server.toml",
            "sophisticatedstorageinmotion-server.toml",
            "starbunclemania-server.toml",
            "structureessentials-server.toml",
            "supermartijn642configlib-server.toml",
            "t_and_t-server.toml",
            "the_bumblezone-server.toml",
            "the_one_probe-server.toml",
            "theoneprobe-server.toml",
            "theurgy-server.toml",
            "tiab-server.toml",
            "titanium-server.toml",
            "touhou_little_maid-server.toml",
            "treetap-server.toml",
            "trophymanager-server.toml",
            "utilitarian-server.toml",
            "xaeroworldmap-server.toml",
            "xnet-server.toml",
            "zerocore-server.toml");

    @Override
    public void run(Consumer<CustomPacketPayload> sender) {
        Set<String> sentFiles = new HashSet<>();
        for (ConfigFilePayload payload : ConfigSync.syncConfigs()) {
            sentFiles.add(payload.fileName());
            sender.accept(payload);
        }

        Set<String> extraConfigFiles = new LinkedHashSet<>(KNOWN_SERVER_CONFIG_FILES);
        for (String configuredFile : HubConfig.get().extraServerConfigs) {
            if (configuredFile != null && !configuredFile.isBlank()) {
                extraConfigFiles.add(configuredFile.trim());
            }
        }
        if (listener instanceof ServerCommonPacketListenerImpl commonListener
                && commonListener.getOwner() != null
                && commonListener.getOwner().getId() != null) {
            for (String modId : ClientModTracker.getClientMods(commonListener.getOwner().getId())) {
                if (modId != null && !modId.isBlank()) {
                    extraConfigFiles.add(modId + "-server.toml");
                }
            }
        }

        for (String fileName : extraConfigFiles) {
            if (sentFiles.add(fileName)) {
                sender.accept(new ConfigFilePayload(fileName, new byte[0]));
            }
        }

        listener.finishCurrentTask(type());
    }

    @Override
    public Type type() {
        return SyncConfig.TYPE;
    }
}
