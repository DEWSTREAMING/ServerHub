package com.serverhub.devtest;

import com.serverhub.ServerHubMod;
import com.serverhub.chat.ChatMuteManager;
import com.serverhub.config.HubConfig;
import com.serverhub.handshake.ClientModTracker;
import com.serverhub.portal.HubPortalManager;
import com.serverhub.portal.HubStructureGenerator;
import com.serverhub.relay.RelayClient;
import com.serverhub.relay.RelayMessage;
import com.serverhub.relay.RelayServer;
import com.serverhub.relay.ServerStatusInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@SuppressWarnings("removal")
@GameTestHolder(ServerHubMod.MOD_ID)
@PrefixGameTestTemplate(false)
public class ServerHubGameTests {
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void acceptsTransfersMixin(GameTestHelper helper) {
        helper.assertTrue(
                helper.getLevel().getServer().acceptsTransfers(),
                "MinecraftServer.acceptsTransfers() should return true via mixin");
        try {
            Class.forName("net.neoforged.neoforge.network.registration.NetworkRegistry");
            Class.forName("net.neoforged.neoforge.network.ConfigurationInitialization");
            Class.forName("net.minecraft.server.dedicated.DedicatedServer");
        } catch (ClassNotFoundException e) {
            helper.fail("Missing mixin target class: " + e.getMessage());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void chatMuteFiltering(GameTestHelper helper) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("serverhub-mutes-test");
            Path tempFile = tempDir.resolve("mutes.json");
            ChatMuteManager.setStoragePathForTesting(tempFile);

            UUID player = UUID.randomUUID();
            helper.assertTrue(
                    ChatMuteManager.shouldReceiveChat(player, "hub", "atm11"),
                    "Default settings should allow chat from atm11");
            helper.assertTrue(
                    ChatMuteManager.shouldReceiveChat(player, "hub", "holeydigsite"),
                    "Default settings should allow chat from holeydigsite");

            ChatMuteManager.muteServer(player, "atm11");
            helper.assertFalse(
                    ChatMuteManager.shouldReceiveChat(player, "hub", "atm11"),
                    "Muted server atm11 should be blocked");
            helper.assertTrue(
                    ChatMuteManager.shouldReceiveChat(player, "hub", "holeydigsite"),
                    "Unmuted server holeydigsite should still be allowed");

            ChatMuteManager.setLocalOnly(player, true);
            helper.assertFalse(
                    ChatMuteManager.shouldReceiveChat(player, "hub", "holeydigsite"),
                    "Local-only mode should block other servers");
            helper.assertTrue(
                    ChatMuteManager.shouldReceiveChat(player, "hub", "hub"),
                    "Local-only mode must still allow same-server chat");

            ChatMuteManager.unmuteServer(player, "atm11");
            ChatMuteManager.setLocalOnly(player, false);
            helper.assertTrue(
                    ChatMuteManager.shouldReceiveChat(player, "hub", "atm11"),
                    "Unmuting atm11 and disabling local mode should restore atm11 chat");

            ChatMuteManager.muteServer(player, "atm11");
            ChatMuteManager.clearMutes(player);
            helper.assertTrue(
                    ChatMuteManager.shouldReceiveChat(player, "hub", "atm11"),
                    "clearMutes should restore all chat");

            helper.succeed();
        } catch (Exception e) {
            helper.fail("chatMuteFiltering threw exception: " + e.getMessage());
        } finally {
            ChatMuteManager.setStoragePathForTesting(null);
            ChatMuteManager.load();
            if (tempDir != null) {
                try {
                    Files.deleteIfExists(tempDir.resolve("mutes.json"));
                    Files.deleteIfExists(tempDir);
                } catch (Exception ignored) {}
            }
        }
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void modpackCompatibilityCheck(GameTestHelper helper) {
        UUID atmPlayer = UUID.randomUUID();
        UUID digPlayer = UUID.randomUUID();
        try {
            ClientModTracker.recordClientModSet(
                    atmPlayer, Set.of("minecraft", "neoforge", "allthemodium", "mekanism"));
            ClientModTracker.recordClientModSet(
                    digPlayer, Set.of("minecraft", "neoforge", "holeydigsite"));

            helper.assertTrue(
                    ClientModTracker.getMissingMods(atmPlayer, List.of("allthemodium")).isEmpty(),
                    "ATM11 player should pass allthemodium check");
            helper.assertTrue(
                    ClientModTracker.getMissingMods(atmPlayer, List.of("holeydigsite")).equals(List.of("holeydigsite")),
                    "ATM11 player should report holeydigsite as missing");
            helper.assertTrue(
                    ClientModTracker.getMissingMods(digPlayer, List.of("holeydigsite")).isEmpty(),
                    "Holey Digsite player should pass holeydigsite check");
            helper.assertTrue(
                    ClientModTracker.getMissingMods(digPlayer, List.of("allthemodium")).equals(List.of("allthemodium")),
                    "Holey Digsite player should report allthemodium as missing");
            helper.assertTrue(
                    ClientModTracker.getMissingMods(atmPlayer, List.of()).isEmpty(),
                    "ATM11 player should pass empty Hub requirements");
            helper.assertTrue(
                    ClientModTracker.getMissingMods(digPlayer, List.of()).isEmpty(),
                    "Holey Digsite player should pass empty Hub requirements");

            helper.succeed();
        } finally {
            ClientModTracker.clearPlayer(atmPlayer);
            ClientModTracker.clearPlayer(digPlayer);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void relayStatusAndChatExchange(GameTestHelper helper) {
        RelayServer server = new RelayServer(0, "test-secret");
        RelayClient atmClient = null;
        RelayClient digClient = null;
        UUID trackedPlayer = UUID.randomUUID();
        try {
            server.start();
            int port = server.getPort();

            atmClient = new RelayClient("127.0.0.1", port, "test-secret");
            digClient = new RelayClient("127.0.0.1", port, "test-secret");

            List<RelayMessage> digChatInbox = new CopyOnWriteArrayList<>();
            List<String> statusTransitions = new CopyOnWriteArrayList<>();
            digClient.setChatListener(digChatInbox::add);
            digClient.setStatusChangeListener((info, prev) ->
                    statusTransitions.add(info.serverId() + ":" + prev + ">" + info.status()));

            atmClient.start();
            digClient.start();

            RelayClient atmRef = atmClient;
            RelayClient digRef = digClient;
            waitUntil(() -> atmRef.isConnected() && digRef.isConnected(), 3000L, "Relay clients did not connect");

            long now = System.currentTimeMillis();
            ServerStatusInfo atmOnline = new ServerStatusInfo(
                    "atm11", "All The Mods 11", "127.0.0.1", 25566, "ONLINE", 3, 20, List.of("allthemodium"), now);
            atmClient.sendHeartbeat(atmOnline);

            waitUntil(
                    () -> digRef.getServerStatus("atm11") != null && digRef.getServerStatus("atm11").isOnline(),
                    3000L,
                    "atm11 ONLINE status did not propagate");

            ServerStatusInfo atmRestarting = atmOnline.copyWithStatus("RESTARTING", 0, System.currentTimeMillis());
            atmClient.sendHeartbeat(atmRestarting);

            waitUntil(
                    () -> {
                        ServerStatusInfo info = digRef.getServerStatus("atm11");
                        return info != null && "RESTARTING".equalsIgnoreCase(info.status()) && !info.isOnline();
                    },
                    3000L,
                    "atm11 RESTARTING status did not propagate");

            atmClient.sendHeartbeat(atmOnline.copyWithStatus("ONLINE", 0, System.currentTimeMillis()));
            waitUntil(
                    () -> digRef.getServerStatus("atm11") != null && digRef.getServerStatus("atm11").isOnline(),
                    3000L,
                    "atm11 recovery to ONLINE did not propagate");

            helper.assertTrue(
                    statusTransitions.contains("atm11:ONLINE>RESTARTING")
                            && statusTransitions.contains("atm11:RESTARTING>ONLINE"),
                    "Expected ONLINE to RESTARTING to ONLINE transitions, got: " + statusTransitions);

            atmClient.sendPlayerOrigin(trackedPlayer, "atm11");
            waitUntil(
                    () -> ClientModTracker.getOriginServer(trackedPlayer)
                            .map("atm11"::equals)
                            .orElse(false),
                    3000L,
                    "PLAYER_ORIGIN did not propagate");

            UUID sender = UUID.randomUUID();
            atmClient.sendChat("atm11", "All The Mods 11", sender, "Steve", "Hello from ATM11");
            waitUntil(() -> !digChatInbox.isEmpty(), 3000L, "CHAT message did not relay");

            RelayMessage relayed = digChatInbox.get(0);
            helper.assertTrue("atm11".equals(relayed.sourceServerId), "Unexpected sourceServerId: " + relayed.sourceServerId);
            helper.assertTrue("Steve".equals(relayed.senderName), "Unexpected senderName: " + relayed.senderName);
            helper.assertTrue(
                    "Hello from ATM11".equals(relayed.chatMessage),
                    "Unexpected chatMessage: " + relayed.chatMessage);

            helper.succeed();
        } catch (Exception e) {
            helper.fail("relayStatusAndChatExchange failed: " + e.getMessage());
        } finally {
            ClientModTracker.clearPlayer(trackedPlayer);
            if (atmClient != null) {
                atmClient.stop();
            }
            if (digClient != null) {
                digClient.stop();
            }
            server.stop();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void portalBoundsAndCooldown(GameTestHelper helper) {
        HubConfig.PortalDefinition portal = new HubConfig.PortalDefinition(
                "atm11_gate", "atm11", "minecraft:overworld", 12, 64, 12, 8, 68, 8);

        helper.assertTrue(
                portal.contains("minecraft:overworld", 10.0, 65.5, 10.0),
                "Point inside normalized bounds should match portal");
        helper.assertFalse(
                portal.contains("minecraft:the_nether", 10.0, 65.5, 10.0),
                "Different dimension must not match portal");
        helper.assertFalse(
                portal.contains("minecraft:overworld", 15.0, 65.5, 10.0),
                "Point outside X bounds must not match portal");

        UUID player = UUID.randomUUID();
        try {
            HubPortalManager.clearCooldownsForTesting();
            long t0 = 10_000L;
            helper.assertTrue(
                    HubPortalManager.tryConsumeCooldown(player, t0),
                    "First portal entry should succeed");
            helper.assertFalse(
                    HubPortalManager.tryConsumeCooldown(player, t0 + 1000L),
                    "Re-entry before 5s cooldown should be blocked");
            helper.assertTrue(
                    HubPortalManager.tryConsumeCooldown(player, t0 + HubPortalManager.COOLDOWN_MS),
                    "Entry once cooldown expires should succeed");
            helper.succeed();
        } finally {
            HubPortalManager.clearPlayer(player);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void generateHubAndLinkPortal(GameTestHelper helper) {
        HubConfig previousConfig = HubConfig.get();
        List<HubConfig.PortalDefinition> previousPortals = new ArrayList<>(previousConfig.portals);
        try {
            HubConfig testCfg = new HubConfig();
            testCfg.portals = new ArrayList<>();
            HubConfig.setForTesting(testCfg);

            BlockPos basePos = helper.absolutePos(new BlockPos(0, 2, 0));
            List<HubConfig.PortalDefinition> generated =
                    HubStructureGenerator.generateHub(helper.getLevel(), basePos);
            helper.assertTrue(
                    generated.size() == 4,
                    "Expected 4 premade portals, got " + generated.size());
            for (String expectedName : HubStructureGenerator.PREMADE_PORTAL_NAMES) {
                boolean present = HubConfig.get().portals.stream()
                        .anyMatch(p -> expectedName.equalsIgnoreCase(p.name));
                helper.assertTrue(present, "Missing premade portal: " + expectedName);
            }

            helper.assertTrue(
                    helper.getLevel().getBlockState(basePos).is(Blocks.SEA_LANTERN),
                    "Hub center floor block should be a sea lantern");
            helper.assertTrue(
                    helper.getLevel().getBlockState(basePos.offset(0, 0, -12)).is(Blocks.CYAN_STAINED_GLASS),
                    "North portal threshold should be cyan stained glass");

            boolean linked = HubStructureGenerator.linkPortal(
                    helper.getLevel().getServer(), "north", "holeydigsite");
            helper.assertTrue(linked, "linkPortal should succeed for premade 'north' portal");

            HubConfig.PortalDefinition northPortal = HubConfig.get().portals.stream()
                    .filter(p -> "north".equalsIgnoreCase(p.name))
                    .findFirst()
                    .orElse(null);
            helper.assertTrue(
                    northPortal != null && "holeydigsite".equals(northPortal.targetServerId),
                    "North portal targetServerId should update to holeydigsite");

            BlockPos northSignPos = basePos.offset(0, 4, -11);
            BlockEntity be = helper.getLevel().getBlockEntity(northSignPos);
            helper.assertTrue(be instanceof SignBlockEntity, "Expected SignBlockEntity above north portal");
            SignBlockEntity sign = (SignBlockEntity) be;
            String signTarget = sign.getFrontText().getMessage(1, false).getString();
            helper.assertTrue(
                    signTarget.contains("holeydigsite"),
                    "North portal sign line 1 should contain 'holeydigsite', got: " + signTarget);

            helper.succeed();
        } finally {
            previousConfig.portals = previousPortals;
            HubConfig.setForTesting(previousConfig);
            HubConfig.save();
        }
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMs, String failureMessage)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new IllegalStateException(failureMessage);
    }
}
