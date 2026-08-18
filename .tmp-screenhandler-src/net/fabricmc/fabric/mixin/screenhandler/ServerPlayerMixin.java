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

package net.fabricmc.fabric.mixin.screenhandler;

import java.util.Objects;
import java.util.OptionalInt;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.authlib.GameProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.fabricmc.fabric.impl.screenhandler.Networking;
import net.minecraft.class_1657;
import net.minecraft.class_1703;
import net.minecraft.class_1937;
import net.minecraft.class_2596;
import net.minecraft.class_2960;
import net.minecraft.class_3222;
import net.minecraft.class_3244;
import net.minecraft.class_3908;
import net.minecraft.class_747;
import net.minecraft.class_7923;

@Mixin(class_3222.class)
public abstract class ServerPlayerMixin extends class_1657 {
	@Shadow
	private int containerCounter;

	private ServerPlayerMixin(class_1937 world, GameProfile gameProfile) {
		super(world, gameProfile);
	}

	@Shadow
	public abstract void method_7346();

	@Redirect(method = "openMenu(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;closeContainer()V"))
	private void fabric_closeHandledScreenIfAllowed(class_3222 player, class_3908 factory) {
		if (factory.shouldCloseCurrentScreen()) {
			this.method_7346();
		} else {
			// Called by closeContainer in vanilla
			this.method_14247();
		}
	}

	@Inject(method = "openMenu(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
	private void fabric_storeOpenedScreenHandler(class_3908 factory, CallbackInfoReturnable<OptionalInt> info, @Local class_1703 handler) {
		if (factory instanceof ExtendedScreenHandlerFactory || (factory instanceof class_747 simpleFactory && simpleFactory.field_17280 instanceof ExtendedScreenHandlerFactory)) {
			// Set the screen handler, so the factory method can access it through the player.
			field_7512 = handler;
		} else if (handler.method_17358() instanceof ExtendedScreenHandlerType<?, ?>) {
			class_2960 id = class_7923.field_41187.method_10221(handler.method_17358());
			throw new IllegalArgumentException("[Fabric] Extended screen handler " + id + " must be opened with an ExtendedScreenHandlerFactory!");
		}
	}

	@Redirect(method = "openMenu(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
	private void fabric_replaceVanillaScreenPacket(class_3244 networkHandler, class_2596<?> packet, class_3908 factory) {
		if (factory instanceof class_747 simpleFactory && simpleFactory.field_17280 instanceof ExtendedScreenHandlerFactory<?> extendedFactory) {
			factory = extendedFactory;
		}

		if (factory instanceof ExtendedScreenHandlerFactory<?> extendedFactory) {
			class_1703 handler = Objects.requireNonNull(field_7512);

			if (handler.method_17358() instanceof ExtendedScreenHandlerType<?, ?>) {
				Networking.sendOpenPacket((class_3222) (Object) this, extendedFactory, handler, containerCounter);
			} else {
				class_2960 id = class_7923.field_41187.method_10221(handler.method_17358());
				throw new IllegalArgumentException("[Fabric] Non-extended screen handler " + id + " must not be opened with an ExtendedScreenHandlerFactory!");
			}
		} else {
			// Use vanilla logic for non-extended screen handlers
			networkHandler.method_14364(packet);
		}
	}
}
