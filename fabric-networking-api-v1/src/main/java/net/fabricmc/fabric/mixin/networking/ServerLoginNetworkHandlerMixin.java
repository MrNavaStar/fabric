/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.fabric.mixin.networking;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import com.mojang.authlib.GameProfile;

import net.fabricmc.fabric.api.networking.v1.ServerCookieStore;
import net.fabricmc.fabric.api.networking.v1.ServerTransferable;

import net.fabricmc.fabric.impl.networking.ServerCookieCallback;

import net.minecraft.network.ClientConnection;

import net.minecraft.network.packet.c2s.common.CookieResponseC2SPacket;
import net.minecraft.network.packet.s2c.common.ServerTransferS2CPacket;
import net.minecraft.util.Identifier;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.login.LoginQueryResponseC2SPacket;
import net.minecraft.network.packet.s2c.login.LoginDisconnectS2CPacket;
import net.minecraft.network.packet.s2c.login.LoginQueryRequestS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;

import net.fabricmc.fabric.impl.networking.DisconnectPacketSource;
import net.fabricmc.fabric.impl.networking.NetworkHandlerExtensions;
import net.fabricmc.fabric.impl.networking.PacketCallbackListener;
import net.fabricmc.fabric.impl.networking.payload.PacketByteBufLoginQueryResponse;
import net.fabricmc.fabric.impl.networking.server.ServerLoginNetworkAddon;

import java.util.concurrent.CompletableFuture;

@Mixin(ServerLoginNetworkHandler.class)
abstract class ServerLoginNetworkHandlerMixin implements NetworkHandlerExtensions, DisconnectPacketSource, PacketCallbackListener, ServerTransferable, ServerCookieStore {
	@Shadow
	protected abstract void tickVerify(GameProfile profile);

	@Shadow
	@Final
	private ClientConnection connection;
	@Shadow
	@Final
	private boolean transferred;
	@Unique
	private ServerLoginNetworkAddon addon;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void initAddon(CallbackInfo ci) {
		this.addon = new ServerLoginNetworkAddon((ServerLoginNetworkHandler) (Object) this);
		// A bit of a hack but it allows the field above to be set in case someone registers handlers during INIT event which refers to said field
		this.addon.lateInit();
	}

	@Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerLoginNetworkHandler;tickVerify(Lcom/mojang/authlib/GameProfile;)V"))
	private void handlePlayerJoin(ServerLoginNetworkHandler instance, GameProfile profile) {
		// Do not accept the player, thereby moving into play stage until all login futures being waited on are completed
		if (this.addon.queryTick()) {
			this.tickVerify(profile);
		}
	}

	@Inject(method = "onQueryResponse", at = @At("HEAD"), cancellable = true)
	private void handleCustomPayloadReceivedAsync(LoginQueryResponseC2SPacket packet, CallbackInfo ci) {
		// Handle queries
		if (this.addon.handle(packet)) {
			ci.cancel();
		} else {
			if (packet.response() instanceof PacketByteBufLoginQueryResponse response) {
				response.data().skipBytes(response.data().readableBytes());
			}
		}
	}

	@Redirect(method = "tickVerify", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;getNetworkCompressionThreshold()I", ordinal = 0))
	private int removeLateCompressionPacketSending(MinecraftServer server) {
		return -1;
	}

	@Inject(method = "onCookieResponse", at = @At("HEAD"))
	private void storeCookie(CookieResponseC2SPacket packet, CallbackInfo ci) {
		((ServerCookieCallback) connection).fabric_invokeCookieCallback(packet.key(), packet.payload());
	}

	@WrapWithCondition(method = "onCookieResponse", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerLoginNetworkHandler;disconnect(Lnet/minecraft/text/Text;)V"))
	private boolean cancelDisconnect(ServerLoginNetworkHandler instance, Text reason) {
		return false;
	}

	@Override
	public void sent(Packet<?> packet) {
		if (packet instanceof LoginQueryRequestS2CPacket) {
			this.addon.registerOutgoingPacket((LoginQueryRequestS2CPacket) packet);
		}
	}

	@Override
	public ServerLoginNetworkAddon getAddon() {
		return this.addon;
	}

	@Override
	public Packet<?> createDisconnectPacket(Text message) {
		return new LoginDisconnectS2CPacket(message);
	}

	@Override
	public void transferToServer(String host, int port) {
		connection.send(new ServerTransferS2CPacket(host, port));
	}

	@Override
	public boolean wasTransferred() {
		return transferred;
	}

	@Override
	public void setCookie(Identifier cookieId, byte[] cookie) {
		((ServerCookieStore) connection).setCookie(cookieId, cookie);
	}

	@Override
	public CompletableFuture<byte[]> getCookie(Identifier cookieId) {
		return ((ServerCookieStore) connection).getCookie(cookieId);
	}
}
