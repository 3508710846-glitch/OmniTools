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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.netty.buffer.Unpooled;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.impl.attachment.AttachmentRegistryImpl;
import net.fabricmc.fabric.impl.attachment.AttachmentTypeImpl;
import net.fabricmc.fabric.impl.attachment.sync.s2c.AttachmentSyncPayloadS2C;
import net.fabricmc.fabric.mixin.attachment.ServerboundCustomPayloadPacketAccessor;
import net.fabricmc.fabric.mixin.attachment.VarIntAccessor;
import net.fabricmc.fabric.mixin.networking.accessor.ServerCommonPacketListenerImplAccessor;
import net.minecraft.class_124;
import net.minecraft.class_1937;
import net.minecraft.class_2540;
import net.minecraft.class_2561;
import net.minecraft.class_2960;
import net.minecraft.class_3222;
import net.minecraft.class_5244;
import net.minecraft.class_5250;
import net.minecraft.class_5455;
import net.minecraft.class_9129;
import net.minecraft.class_9135;
import net.minecraft.class_9139;

public record AttachmentChange(AttachmentTargetInfo<?> targetInfo, AttachmentType<?> type, byte[] data) {
	public static final class_9139<class_2540, AttachmentChange> PACKET_CODEC = class_9139.method_56436(
			AttachmentTargetInfo.PACKET_CODEC, AttachmentChange::targetInfo,
			class_2960.field_48267.method_56432(
					id -> Objects.requireNonNull(AttachmentRegistryImpl.get(id)),
					AttachmentType::identifier
			), AttachmentChange::type,
			class_9135.field_48987, AttachmentChange::data,
			AttachmentChange::new
	);
	private static final int MAX_PADDING_SIZE_IN_BYTES = AttachmentTargetInfo.MAX_SIZE_IN_BYTES + AttachmentSync.MAX_IDENTIFIER_SIZE;
	private static final int MAX_DATA_SIZE_IN_BYTES = ServerboundCustomPayloadPacketAccessor.getMaxPayloadSize() - MAX_PADDING_SIZE_IN_BYTES;
	private static final boolean DISCONNECT_ON_UNKNOWN_TARGETS = System.getProperty("fabric.attachment.disconnect_on_unknown_targets") != null;

	private static final Logger LOGGER = LoggerFactory.getLogger(AttachmentChange.class);

	@SuppressWarnings("unchecked")
	public static AttachmentChange create(AttachmentTargetInfo<?> targetInfo, AttachmentType<?> type, @Nullable Object value, class_5455 dynamicRegistryManager) {
		class_9139<? super class_9129, Object> codec = (class_9139<? super class_9129, Object>) ((AttachmentTypeImpl<?>) type).packetCodec();
		Objects.requireNonNull(codec, "attachment packet codec cannot be null");
		Objects.requireNonNull(dynamicRegistryManager, "dynamic registry manager cannot be null");

		class_9129 buf = new class_9129(PacketByteBufs.create(), dynamicRegistryManager);

		if (value != null) {
			buf.method_52964(true);
			codec.encode(buf, value);
		} else {
			buf.method_52964(false);
		}

		byte[] encoded = buf.array();

		if (encoded.length > MAX_DATA_SIZE_IN_BYTES) {
			throw new IllegalArgumentException("Data for attachment '%s' was too big (%d bytes, over maximum %d)".formatted(
					type.identifier(),
					encoded.length,
					MAX_DATA_SIZE_IN_BYTES
			));
		}

		return new AttachmentChange(targetInfo, type, encoded);
	}

	public static void partitionAndSendPackets(List<AttachmentChange> changes, class_3222 player) {
		Set<class_2960> supported = ((SupportedAttachmentsClientConnection) ((ServerCommonPacketListenerImplAccessor) player.field_13987).getConnection())
				.fabric_getSupportedAttachments();
		// sort by size to better partition packets
		changes.sort(Comparator.comparingInt(c -> c.data().length));
		List<AttachmentChange> packetChanges = new ArrayList<>();
		int maxVarIntSize = VarIntAccessor.getMaxByteSize();
		int byteSize = maxVarIntSize;

		for (AttachmentChange change : changes) {
			if (!supported.contains(change.type.identifier())) {
				continue;
			}

			int size = MAX_PADDING_SIZE_IN_BYTES + change.data.length;

			if (!packetChanges.isEmpty() && byteSize + size > MAX_DATA_SIZE_IN_BYTES) {
				ServerPlayNetworking.send(player, new AttachmentSyncPayloadS2C(List.copyOf(packetChanges)));
				packetChanges.clear();
				byteSize = maxVarIntSize;
			}

			packetChanges.add(change);
			byteSize += size;
		}

		if (!packetChanges.isEmpty()) {
			ServerPlayNetworking.send(player, new AttachmentSyncPayloadS2C(packetChanges));
		}
	}

	@SuppressWarnings("unchecked")
	@Nullable
	public Object decodeValue(class_5455 dynamicRegistryManager) {
		class_9139<? super class_9129, Object> codec = (class_9139<? super class_9129, Object>) ((AttachmentTypeImpl<?>) type).packetCodec();
		Objects.requireNonNull(codec, "codec was null");
		Objects.requireNonNull(dynamicRegistryManager, "dynamic registry manager cannot be null");

		class_9129 buf = new class_9129(Unpooled.copiedBuffer(data), dynamicRegistryManager);

		if (!buf.readBoolean()) {
			return null;
		}

		return codec.decode(buf);
	}

	public void tryApply(class_1937 world) throws AttachmentSyncException {
		AttachmentTarget target = targetInfo.getTarget(world);
		Object value = decodeValue(world.method_30349());

		if (target == null) {
			final class_5250 errorMessageText = class_2561.method_43473();
			errorMessageText
					.method_10852(class_2561.method_43471("fabric-data-attachment-api-v1.unknown-target.title").method_27692(class_124.field_1061))
					.method_10852(class_5244.field_33849);
			errorMessageText.method_10852(class_5244.field_33849);

			errorMessageText
					.method_10852(class_2561.method_43469(
							"fabric-data-attachment-api-v1.unknown-target.attachment-identifier",
							class_2561.method_43470(String.valueOf(type.identifier())).method_27692(class_124.field_1054))
					)
					.method_10852(class_5244.field_33849);
			errorMessageText
					.method_10852(class_2561.method_43469(
							"fabric-data-attachment-api-v1.unknown-target.world",
							class_2561.method_43470(String.valueOf(world.method_27983().method_29177())).method_27692(class_124.field_1054)
					))
					.method_10852(class_5244.field_33849);
			targetInfo.appendDebugInformation(errorMessageText);

			if (DISCONNECT_ON_UNKNOWN_TARGETS) {
				throw new AttachmentSyncException(errorMessageText);
			}

			LOGGER.warn(errorMessageText.getString().trim());
			return;
		}

		target.setAttached((AttachmentType<Object>) type, value);
	}
}
