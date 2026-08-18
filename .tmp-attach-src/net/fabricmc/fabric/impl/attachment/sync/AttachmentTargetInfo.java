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

package net.fabricmc.fabric.impl.attachment.sync;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.bytes.Byte2ObjectArrayMap;
import it.unimi.dsi.fastutil.bytes.Byte2ObjectMap;
import org.jspecify.annotations.Nullable;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.minecraft.class_124;
import net.minecraft.class_1297;
import net.minecraft.class_1923;
import net.minecraft.class_1937;
import net.minecraft.class_2338;
import net.minecraft.class_2561;
import net.minecraft.class_2586;
import net.minecraft.class_2791;
import net.minecraft.class_2806;
import net.minecraft.class_5244;
import net.minecraft.class_5250;
import net.minecraft.class_9135;
import net.minecraft.class_9139;

public sealed interface AttachmentTargetInfo<T> {
	int MAX_SIZE_IN_BYTES = Byte.BYTES + Long.BYTES;
	class_9139<ByteBuf, AttachmentTargetInfo<?>> PACKET_CODEC = class_9135.field_48548.method_56440(
			AttachmentTargetInfo::getId, Type::packetCodecFromId
	);

	Type<T> getType();

	default byte getId() {
		return getType().id;
	}

	@Nullable
	AttachmentTarget getTarget(class_1937 world);

	void appendDebugInformation(class_5250 text);

	record Type<T>(byte id, class_9139<ByteBuf, ? extends AttachmentTargetInfo<T>> packetCodec) {
		static Byte2ObjectMap<Type<?>> TYPES = new Byte2ObjectArrayMap<>();
		static Type<class_2586> BLOCK_ENTITY = new Type<>((byte) 0, BlockEntityTarget.PACKET_CODEC);
		static Type<class_1297> ENTITY = new Type<>((byte) 1, EntityTarget.PACKET_CODEC);
		static Type<class_2791> CHUNK = new Type<>((byte) 2, ChunkTarget.PACKET_CODEC);
		static Type<class_1937> WORLD = new Type<>((byte) 3, WorldTarget.PACKET_CODEC);

		public Type {
			TYPES.put(id, this);
		}

		static class_9139<ByteBuf, ? extends AttachmentTargetInfo<?>> packetCodecFromId(byte id) {
			return TYPES.get(id).packetCodec;
		}
	}

	record BlockEntityTarget(class_2338 pos) implements AttachmentTargetInfo<class_2586> {
		static final class_9139<ByteBuf, BlockEntityTarget> PACKET_CODEC = class_9139.method_56434(
				class_2338.field_48404, BlockEntityTarget::pos,
				BlockEntityTarget::new
		);

		@Override
		public Type<class_2586> getType() {
			return Type.BLOCK_ENTITY;
		}

		@Override
		public AttachmentTarget getTarget(class_1937 world) {
			return world.method_8321(pos);
		}

		@Override
		public void appendDebugInformation(class_5250 text) {
			text
					.method_10852(class_2561.method_43469(
							"fabric-data-attachment-api-v1.unknown-target.target-type",
							class_2561.method_43471("fabric-data-attachment-api-v1.unknown-target.target-type.block-entity").method_27692(class_124.field_1054)
					))
					.method_10852(class_5244.field_33849);
			text
					.method_10852(class_2561.method_43469(
							"fabric-data-attachment-api-v1.unknown-target.block-entity-position",
							class_2561.method_43470(pos.method_23854()).method_27692(class_124.field_1054)
					))
					.method_10852(class_5244.field_33849);
		}
	}

	record EntityTarget(int networkId) implements AttachmentTargetInfo<class_1297> {
		static final class_9139<ByteBuf, EntityTarget> PACKET_CODEC = class_9139.method_56434(
				class_9135.field_48550, EntityTarget::networkId,
				EntityTarget::new
		);

		@Override
		public Type<class_1297> getType() {
			return Type.ENTITY;
		}

		@Override
		public AttachmentTarget getTarget(class_1937 world) {
			return world.method_8469(networkId);
		}

		@Override
		public void appendDebugInformation(class_5250 text) {
			text
					.method_10852(class_2561.method_43469(
							"fabric-data-attachment-api-v1.unknown-target.target-type",
							class_2561.method_43471("fabric-data-attachment-api-v1.unknown-target.target-type.entity").method_27692(class_124.field_1054)
					))
					.method_10852(class_5244.field_33849);
			text
					.method_10852(class_2561.method_43469(
							"fabric-data-attachment-api-v1.unknown-target.entity-network-id",
							class_2561.method_43470(String.valueOf(networkId)).method_27692(class_124.field_1054)
					))
					.method_10852(class_5244.field_33849);
		}
	}

	record ChunkTarget(class_1923 pos) implements AttachmentTargetInfo<class_2791> {
		static final class_9139<ByteBuf, ChunkTarget> PACKET_CODEC = class_9135.field_48551
				.method_56432(class_1923::new, class_1923::method_8324)
				.method_56432(ChunkTarget::new, ChunkTarget::pos);

		@Override
		public Type<class_2791> getType() {
			return Type.CHUNK;
		}

		@Override
		public AttachmentTarget getTarget(class_1937 world) {
			return world.method_8402(pos.field_9181, pos.field_9180, class_2806.field_12803, false);
		}

		@Override
		public void appendDebugInformation(class_5250 text) {
			text
					.method_10852(class_2561.method_43469(
							"fabric-data-attachment-api-v1.unknown-target.target-type",
							class_2561.method_43471("fabric-data-attachment-api-v1.unknown-target.target-type.chunk").method_27692(class_124.field_1054)
					))
					.method_10852(class_5244.field_33849);
			text
					.method_10852(class_2561.method_43469(
							"fabric-data-attachment-api-v1.unknown-target.chunk-position",
							class_2561.method_43470(pos.field_9181 + ", " + pos.field_9180).method_27692(class_124.field_1054)
					))
					.method_10852(class_5244.field_33849);
		}
	}

	final class WorldTarget implements AttachmentTargetInfo<class_1937> {
		public static final WorldTarget INSTANCE = new WorldTarget();
		static final class_9139<ByteBuf, WorldTarget> PACKET_CODEC = class_9139.method_56431(INSTANCE);

		private WorldTarget() {
		}

		@Override
		public Type<class_1937> getType() {
			return Type.WORLD;
		}

		@Override
		public AttachmentTarget getTarget(class_1937 world) {
			return world;
		}

		@Override
		public void appendDebugInformation(class_5250 text) {
			text
					.method_10852(class_2561.method_43469(
							"fabric-data-attachment-api-v1.unknown-target.target-type",
							class_2561.method_43471("fabric-data-attachment-api-v1.unknown-target.target-type.world").method_27692(class_124.field_1054)
					))
					.method_10852(class_5244.field_33849);
		}
	}
}
