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

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.class_124;
import net.minecraft.class_2561;
import net.minecraft.class_310;
import net.minecraft.class_637;
import net.minecraft.class_638;
import net.minecraft.class_746;

@Mixin(class_637.class)
abstract class ClientSuggestionProviderMixin implements FabricClientCommandSource {
	@Shadow
	@Final
	private class_310 minecraft;

	@Override
	public void sendFeedback(class_2561 message) {
		this.minecraft.field_1705.method_1743().method_1812(message);
		this.minecraft.method_44713().method_70816(message);
	}

	@Override
	public void sendError(class_2561 message) {
		sendFeedback(class_2561.method_43473().method_10852(message).method_27692(class_124.field_1061));
	}

	@Override
	public class_310 getClient() {
		return minecraft;
	}

	@Override
	public class_746 getPlayer() {
		return minecraft.field_1724;
	}

	@Override
	public class_638 getWorld() {
		return minecraft.field_1687;
	}
}
