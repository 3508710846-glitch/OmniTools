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

package net.fabricmc.fabric.api.command.v2;

import com.mojang.brigadier.arguments.ArgumentType;
import net.fabricmc.fabric.mixin.command.ArgumentTypeInfosAccessor;
import net.minecraft.class_2314;
import net.minecraft.class_2378;
import net.minecraft.class_2960;
import net.minecraft.class_7923;

public final class ArgumentTypeRegistry {
	/**
	 * Register a new argument type.
	 *
	 * @param id the identifier of the argument type
	 * @param clazz the class of the argument type
	 * @param serializer the serializer for the argument type
	 * @param <A> the argument type
	 * @param <T> the argument type properties
	 */
	public static <A extends ArgumentType<?>, T extends class_2314.class_7217<A>> void registerArgumentType(
			class_2960 id, Class<? extends A> clazz, class_2314<A, T> serializer) {
		ArgumentTypeInfosAccessor.fabric_getClassMap().put(clazz, serializer);
		class_2378.method_10230(class_7923.field_41192, id, serializer);
	}

	private ArgumentTypeRegistry() {
	}
}
