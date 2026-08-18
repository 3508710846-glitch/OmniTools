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

package net.fabricmc.fabric.impl.screenhandler.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.fabricmc.fabric.impl.screenhandler.Networking;
import net.minecraft.class_1657;
import net.minecraft.class_2561;
import net.minecraft.class_2960;
import net.minecraft.class_310;
import net.minecraft.class_3917;
import net.minecraft.class_3929;
import net.minecraft.class_3936;
import net.minecraft.class_437;
import net.minecraft.class_7923;

public final class ClientNetworking implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("fabric-screen-handler-api-v1/client");

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(Networking.OpenScreenPayload.ID, (payload, context) -> {
			this.openScreen(payload);
		});
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private <D> void openScreen(Networking.OpenScreenPayload<D> payload) {
		class_2960 typeId = payload.identifier();
		int syncId = payload.syncId();
		class_2561 title = payload.title();

		class_3917<?> type = class_7923.field_41187.method_63535(typeId);

		if (type == null || payload.data() == null) {
			LOGGER.warn("Unknown screen handler ID: {}", typeId);
			return;
		}

		if (!(type instanceof ExtendedScreenHandlerType)) {
			LOGGER.warn("Received extended opening packet for non-extended screen handler {}", typeId);
			return;
		}

		class_3929.class_3930 screenFactory = class_3929.method_17540(type);

		if (screenFactory != null) {
			class_310 client = class_310.method_1551();
			class_1657 player = client.field_1724;

			class_437 screen = screenFactory.create(
					((ExtendedScreenHandlerType<?, D>) type).create(syncId, player.method_31548(), payload.data()),
					player.method_31548(),
					title
			);

			player.field_7512 = ((class_3936<?>) screen).method_17577();
			client.method_1507(screen);
		} else {
			LOGGER.warn("Screen not registered for screen handler {}!", typeId);
		}
	}
}
