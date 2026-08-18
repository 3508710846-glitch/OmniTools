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

package net.fabricmc.fabric.api.client.command.v2;

import net.minecraft.class_1297;
import net.minecraft.class_2172;
import net.minecraft.class_241;
import net.minecraft.class_243;
import net.minecraft.class_2561;
import net.minecraft.class_310;
import net.minecraft.class_638;
import net.minecraft.class_746;
import org.jspecify.annotations.Nullable;

/**
 * Extensions to {@link class_2172} for client-sided commands.
 */
public interface FabricClientCommandSource extends class_2172 {
	/**
	 * Sends a feedback message to the player.
	 *
	 * @param message the feedback message
	 */
	void sendFeedback(class_2561 message);

	/**
	 * Sends an error message to the player.
	 *
	 * @param message the error message
	 */
	void sendError(class_2561 message);

	/**
	 * Gets the client instance used to run the command.
	 *
	 * @return the client
	 */
	class_310 getClient();

	/**
	 * Gets the player that used the command.
	 *
	 * @return the player
	 */
	class_746 getPlayer();

	/**
	 * Gets the entity that used the command.
	 *
	 * @return the entity
	 */
	default class_1297 getEntity() {
		return getPlayer();
	}

	/**
	 * Gets the position from where the command has been executed.
	 *
	 * @return the position
	 */
	default class_243 getPosition() {
		return getPlayer().method_73189();
	}

	/**
	 * Gets the rotation of the entity that used the command.
	 *
	 * @return the rotation
	 */
	default class_241 getRotation() {
		return getPlayer().method_5802();
	}

	/**
	 * Gets the world where the player used the command.
	 *
	 * @return the world
	 */
	class_638 getWorld();

	/**
	 * Gets the meta property under {@code key} that was assigned to this source.
	 *
	 * <p>This method should return the same result for every call with the same {@code key}.
	 *
	 * @param key the meta key
	 * @return the meta
	 */
	default @Nullable Object getMeta(String key) {
		return null;
	}
}
