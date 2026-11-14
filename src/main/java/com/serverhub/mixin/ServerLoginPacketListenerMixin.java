package com.serverhub.mixin;

import com.mojang.authlib.GameProfile;
import com.serverhub.transfer.TransferAuthToken;
import java.lang.reflect.Field;
import javax.annotation.Nullable;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerMixin {
    @Unique
    private static final Logger SERVERHUB_LOGGER =
            LoggerFactory.getLogger(ServerLoginPacketListenerMixin.class);

    @Unique
    @Nullable
    private static final Field SERVERHUB_STATE_FIELD;

    @Unique
    @Nullable
    private static final Object SERVERHUB_STATE_NEGOTIATING;

    @Unique
    @Nullable
    private static final Object SERVERHUB_STATE_KEY;

    static {
        Field stateField = null;
        Object negotiating = null;
        Object key = null;
        try {
            for (Field f : ServerLoginPacketListenerImpl.class.getDeclaredFields()) {
                if (f.getType().isEnum() && f.getType().getName().endsWith("$State")) {
                    f.setAccessible(true);
                    stateField = f;
                    for (Object constant : f.getType().getEnumConstants()) {
                        String name = ((Enum<?>) constant).name();
                        if ("NEGOTIATING".equals(name)) {
                            negotiating = constant;
                        } else if ("KEY".equals(name)) {
                            key = constant;
                        }
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            SERVERHUB_LOGGER.warn("Failed to locate ServerLoginPacketListenerImpl.State via reflection", t);
        }
        SERVERHUB_STATE_FIELD = stateField;
        SERVERHUB_STATE_NEGOTIATING = negotiating;
        SERVERHUB_STATE_KEY = key;
    }

    @Shadow
    @Final
    private boolean transferred;

    @Shadow
    @Final
    MinecraftServer server;

    @Shadow
    @Final
    Connection connection;

    @Shadow
    @Final
    private byte[] challenge;

    @Shadow
    @Nullable
    String requestedUsername;

    @Shadow
    abstract void startClientVerification(GameProfile authenticatedProfile);

    @Unique
    private boolean serverhub$awaitingTransferCookie;

    @Unique
    @Nullable
    private GameProfile serverhub$verifiedTransferProfile;

    @Inject(
            method = "handleHello(Lnet/minecraft/network/protocol/login/ServerboundHelloPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;)V"),
            cancellable = true)
    private void serverhub$requestTransferCookie(ServerboundHelloPacket packet, CallbackInfo ci) {
        if (!this.transferred
                || !TransferAuthToken.isConfigured()
                || SERVERHUB_STATE_FIELD == null
                || SERVERHUB_STATE_NEGOTIATING == null
                || SERVERHUB_STATE_KEY == null) {
            return;
        }
        if (serverhub$setState(this, SERVERHUB_STATE_NEGOTIATING)) {
            this.serverhub$awaitingTransferCookie = true;
            this.connection.send(new ClientboundCookieRequestPacket(TransferAuthToken.COOKIE_KEY));
            ci.cancel();
        }
    }

    @Inject(
            method = "handleCookieResponse(Lnet/minecraft/network/protocol/cookie/ServerboundCookieResponsePacket;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void serverhub$handleTransferCookieResponse(
            ServerboundCookieResponsePacket packet, CallbackInfo ci) {
        if (!this.serverhub$awaitingTransferCookie
                || !TransferAuthToken.COOKIE_KEY.equals(packet.key())) {
            return;
        }
        this.serverhub$awaitingTransferCookie = false;
        GameProfile verified =
                TransferAuthToken.verifyAndConsume(packet.payload(), this.requestedUsername);
        serverhub$setState(this, SERVERHUB_STATE_KEY);
        if (verified != null) {
            this.serverhub$verifiedTransferProfile = verified;
            this.connection.send(new ClientboundHelloPacket(
                    "", this.server.getKeyPair().getPublic().getEncoded(), this.challenge, false));
        } else {
            SERVERHUB_LOGGER.debug(
                    "Transfer cookie missing/invalid for {}, falling back to Mojang authentication",
                    this.requestedUsername);
            this.connection.send(new ClientboundHelloPacket(
                    "", this.server.getKeyPair().getPublic().getEncoded(), this.challenge, true));
        }
        ci.cancel();
    }

    @Inject(
            method = "handleKey(Lnet/minecraft/network/protocol/login/ServerboundKeyPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/Connection;setEncryptionKey(Ljavax/crypto/Cipher;Ljavax/crypto/Cipher;)V",
                    shift = At.Shift.AFTER),
            cancellable = true)
    private void serverhub$completeVerifiedTransferLogin(
            ServerboundKeyPacket packet, CallbackInfo ci) {
        if (this.serverhub$verifiedTransferProfile != null) {
            GameProfile verified = this.serverhub$verifiedTransferProfile;
            this.serverhub$verifiedTransferProfile = null;
            SERVERHUB_LOGGER.info(
                    "Verified transfer cookie for {} ({}), skipping Mojang session lookup",
                    verified.name(),
                    verified.id());
            this.startClientVerification(verified);
            ci.cancel();
        }
    }

    @Unique
    private static boolean serverhub$setState(Object instance, @Nullable Object state) {
        if (SERVERHUB_STATE_FIELD == null || state == null) {
            return false;
        }
        try {
            SERVERHUB_STATE_FIELD.set(instance, state);
            return true;
        } catch (IllegalAccessException e) {
            SERVERHUB_LOGGER.warn("Failed to set ServerLoginPacketListenerImpl.state", e);
            return false;
        }
    }
}
