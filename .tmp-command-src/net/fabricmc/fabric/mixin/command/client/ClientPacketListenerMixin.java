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

package net.fabricmc.fabric.mixin.command.client;

import com.mojang.brigadier.CommandDispatcher;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;
import net.minecraft.class_2172;
import net.minecraft.class_2641;
import net.minecraft.class_2678;
import net.minecraft.class_437;
import net.minecraft.class_5455;
import net.minecraft.class_634;
import net.minecraft.class_637;
import net.minecraft.class_7157;
import net.minecraft.class_7699;

@Mixin(class_634.class)
abstract class ClientPacketListenerMixin implements ClientCommandInternals.LastReceivedCommandsPacketAccessor {
	@Shadow
	private CommandDispatcher<class_2172> commands;

	@Shadow
	@Final
	private class_637 suggestionsProvider;

	@Final
	@Shadow
	private class_7699 enabledFeatures;

	@Final
	@Shadow
	private class_5455.class_6890 registryAccess;

	@Unique
	private @Nullable class_2641 lastReceivedCommandsPacket = null;

	@Inject(method = "handleLogin", at = @At("RETURN"))
	private void onGameJoin(class_2678 packet, CallbackInfo info) {
		final CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();
		ClientCommandInternals.setActiveDispatcher(dispatcher);
		ClientCommandRegistrationCallback.EVENT.invoker().register(dispatcher, class_7157.method_46722(this.registryAccess, this.enabledFeatures));
		ClientCommandInternals.finalizeInit();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Inject(method = "handleCommands", at = @At("RETURN"))
	private void onOnCommandTree(class_2641 packet, CallbackInfo info) {
		// Add the commands to the vanilla dispatcher for completion.
		// It's done here because both the server and the client commands have
		// to be in the same dispatcher and completion results.
		ClientCommandInternals.addCommands((CommandDispatcher) commands, (FabricClientCommandSource) suggestionsProvider);
	}

	@Inject(method = "handleCommands", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER))
	private void setLastReceivedCommandsPacket(class_2641 packet, CallbackInfo ci) {
		this.lastReceivedCommandsPacket = packet;
	}

	@Inject(method = "sendUnattendedCommand", at = @At("HEAD"), cancellable = true)
	private void onSendCommand(String command, class_437 screen, CallbackInfo info) {
		if (ClientCommandInternals.executeCommand(command)) {
			info.cancel();
		}
	}

	@Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
	private void onSendCommand(String command, CallbackInfo info) {
		if (ClientCommandInternals.executeCommand(command)) {
			info.cancel();
		}
	}

	@Override
	public @Nullable class_2641 fabric_api$getLastReceivedCommandsPacket() {
		return this.lastReceivedCommandsPacket;
	}
}
