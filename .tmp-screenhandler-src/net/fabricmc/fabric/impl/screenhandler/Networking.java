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

package net.fabricmc.fabric.impl.screenhandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.registry.RegistryEntryAddedCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.class_1703;
import net.minecraft.class_2378;
import net.minecraft.class_2561;
import net.minecraft.class_2960;
import net.minecraft.class_3222;
import net.minecraft.class_7923;
import net.minecraft.class_8710;
import net.minecraft.class_8824;
import net.minecraft.class_9129;
import net.minecraft.class_9139;

public final class Networking implements ModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("fabric-screen-handler-api-v1/server");

	// [Packet format]
	// typeId: identifier
	// syncId: varInt
	// title: text
	// customData: buf
	public static final class_2960 OPEN_ID = class_2960.method_60655("fabric-screen-handler-api-v1", "open_screen");
	public static final Map<class_2960, class_9139<? super class_9129, ?>> CODEC_BY_ID = new HashMap<>();

	/**
	 * Opens an extended screen handler by sending a custom packet to the client.
	 *
	 * @param player  the player
	 * @param factory the screen handler factory
	 * @param handler the screen handler instance
	 * @param syncId  the synchronization ID
	 */
	@SuppressWarnings("unchecked")
	public static <D> void sendOpenPacket(class_3222 player, ExtendedScreenHandlerFactory<D> factory, class_1703 handler, int syncId) {
		Objects.requireNonNull(player, "player is null");
		Objects.requireNonNull(factory, "factory is null");
		Objects.requireNonNull(handler, "handler is null");

		class_2960 typeId = class_7923.field_41187.method_10221(handler.method_17358());

		if (typeId == null) {
			LOGGER.warn("Trying to open unregistered screen handler {}", handler);
			return;
		}

		class_9139<class_9129, D> codec = (class_9139<class_9129, D>) Objects.requireNonNull(CODEC_BY_ID.get(typeId), () -> "Codec for " + typeId + " is not registered!");
		D data = factory.getScreenOpeningData(player);

		ServerPlayNetworking.send(player, new OpenScreenPayload<>(typeId, syncId, factory.method_5476(), codec, data));
	}

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.playS2C().register(OpenScreenPayload.ID, OpenScreenPayload.CODEC);

		forEachEntry(class_7923.field_41187, (type, id) -> {
			if (type instanceof ExtendedScreenHandlerType<?, ?> extended) {
				CODEC_BY_ID.put(id, extended.getPacketCodec());
			}
		});
	}

	// Calls the consumer for each registry entry that has been registered or will be registered.
	private static <T> void forEachEntry(class_2378<T> registry, BiConsumer<T, class_2960> consumer) {
		for (T type : registry) {
			consumer.accept(type, registry.method_10221(type));
		}

		RegistryEntryAddedCallback.event(registry).register((rawId, id, type) -> {
			consumer.accept(type, id);
		});
	}

	public record OpenScreenPayload<D>(class_2960 identifier, int syncId, class_2561 title, class_9139<class_9129, D> innerCodec, D data) implements class_8710 {
		public static final class_9139<class_9129, OpenScreenPayload<?>> CODEC = class_8710.method_56484(OpenScreenPayload::write, OpenScreenPayload::fromBuf);
		public static final class_8710.class_9154<OpenScreenPayload<?>> ID = new class_9154<>(OPEN_ID);

		@SuppressWarnings("unchecked")
		private static <D> OpenScreenPayload<D> fromBuf(class_9129 buf) {
			class_2960 id = buf.method_10810();
			class_9139<class_9129, D> codec = (class_9139<class_9129, D>) CODEC_BY_ID.get(id);

			return new OpenScreenPayload<>(id, buf.readByte(), class_8824.field_48540.decode(buf), codec, codec == null ? null : codec.decode(buf));
		}

		private void write(class_9129 buf) {
			buf.method_10812(this.identifier);
			buf.method_52997(this.syncId);
			class_8824.field_48540.encode(buf, this.title);
			this.innerCodec.encode(buf, this.data);
		}

		@Override
		public class_9154<? extends class_8710> method_56479() {
			return ID;
		}
	}
}
