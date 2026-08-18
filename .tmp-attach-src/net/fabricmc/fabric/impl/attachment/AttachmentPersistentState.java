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

package net.fabricmc.fabric.impl.attachment;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Encoder;
import net.minecraft.class_11352;
import net.minecraft.class_11362;
import net.minecraft.class_11368;
import net.minecraft.class_18;
import net.minecraft.class_2487;
import net.minecraft.class_2509;
import net.minecraft.class_3218;
import net.minecraft.class_8942;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backing storage for server-side world attachments.
 * Thanks to custom {@link #method_79()} logic, the file is only written if something needs to be persisted.
 */
public class AttachmentPersistentState extends class_18 {
	private static final Logger LOGGER = LoggerFactory.getLogger(AttachmentPersistentState.class);
	public static final String ID = "fabric_attachments";
	private final AttachmentTargetImpl worldTarget;
	private final boolean wasSerialized;

	public AttachmentPersistentState(class_3218 world) {
		this.worldTarget = (AttachmentTargetImpl) world;
		this.wasSerialized = worldTarget.fabric_hasPersistentAttachments();
	}

	// TODO 1.21.5 look at making this more idiomatic
	public static Codec<AttachmentPersistentState> codec(class_3218 world) {
		final class_8942.class_11336 reporterContext = () -> "AttachmentPersistentState @ " + world.method_27983().method_29177();

		return Codec.of(new Encoder<>() {
			@Override
			public <T> DataResult<T> encode(AttachmentPersistentState input, DynamicOps<T> ops, T prefix) {
				try (class_8942.class_11340 reporter = new class_8942.class_11340(reporterContext, LOGGER)) {
					class_11362 writeView = class_11362.method_71458(reporter);
					((AttachmentTargetImpl) world).fabric_writeAttachmentsToNbt(writeView);
					return DataResult.success(class_2509.field_11560.method_29146(ops, writeView.method_71475()));
				}
			}
		}, new Decoder<>() {
			@Override
			public <T> DataResult<Pair<AttachmentPersistentState, T>> decode(DynamicOps<T> ops, T input) {
				try (class_8942.class_11340 reporter = new class_8942.class_11340(reporterContext, LOGGER)) {
					class_11368 readView = class_11352.method_71417(reporter, world.method_30349(), (class_2487) ops.convertTo(class_2509.field_11560, input));
					((AttachmentTargetImpl) world).fabric_readAttachmentsFromNbt(readView);
					return DataResult.success(Pair.of(new AttachmentPersistentState(world), ops.empty()));
				}
			}
		});
	}

	@Override
	public boolean method_79() {
		// Only write data if there are attachments, or if we previously wrote data.
		return wasSerialized || worldTarget.fabric_hasPersistentAttachments();
	}
}
