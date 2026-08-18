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

package net.fabricmc.fabric.mixin.attachment;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.impl.attachment.AttachmentTargetImpl;
import net.minecraft.class_11352;
import net.minecraft.class_11362;
import net.minecraft.class_11368;
import net.minecraft.class_11897;
import net.minecraft.class_1923;
import net.minecraft.class_2487;
import net.minecraft.class_2791;
import net.minecraft.class_2839;
import net.minecraft.class_2852;
import net.minecraft.class_3218;
import net.minecraft.class_4153;
import net.minecraft.class_5539;
import net.minecraft.class_8942;
import net.minecraft.class_9240;

@Mixin(class_2852.class)
abstract class SerializableChunkDataMixin {
	@Unique
	private static final Logger LOGGER = LoggerFactory.getLogger("SerializableChunkDataMixin");

	// Adding a mutable record field like this is likely a bad idea, but I cannot see a better way.
	@Unique
	@Nullable
	private class_2487 attachmentNbtData;

	@Inject(method = "parse", at = @At("RETURN"))
	private static void storeAttachmentNbtData(class_5539 heightLimitView, class_11897 arg, class_2487 nbt, CallbackInfoReturnable<class_2852> cir, @Share("attachmentDataNbt") LocalRef<class_2487> attachmentDataNbt) {
		final class_2852 serializer = cir.getReturnValue();

		if (serializer == null) {
			return;
		}

		//noinspection SimplifyOptionalCallChains
		class_2487 attachmentNbtData = nbt.method_10562(AttachmentTarget.NBT_ATTACHMENT_KEY).orElse(null);

		if (attachmentNbtData != null) {
			((SerializableChunkDataMixin) (Object) serializer).attachmentNbtData = attachmentNbtData;
		}
	}

	@Inject(method = "read", at = @At("RETURN"))
	private void setAttachmentDataInChunk(class_3218 serverWorld, class_4153 pointOfInterestStorage, class_9240 storageKey, class_1923 chunkPos, CallbackInfoReturnable<class_2839> cir) {
		class_2839 chunk = cir.getReturnValue();

		if (chunk != null && attachmentNbtData != null) {
			var nbt = new class_2487();
			nbt.method_10566(AttachmentTarget.NBT_ATTACHMENT_KEY, attachmentNbtData);

			try (class_8942.class_11340 reporter = new class_8942.class_11340(LOGGER)) {
				class_11368 readView = class_11352.method_71417(reporter, serverWorld.method_30349(), nbt);
				((AttachmentTargetImpl) chunk).fabric_readAttachmentsFromNbt(readView);
			}
		}
	}

	@Inject(method = "copyOf", at = @At("RETURN"))
	private static void storeAttachmentNbtData(class_3218 world, class_2791 chunk, CallbackInfoReturnable<class_2852> cir) {
		try (class_8942.class_11340 reporter = new class_8942.class_11340(LOGGER)) {
			class_11362 writeView = class_11362.method_71459(reporter, world.method_30349());
			((AttachmentTargetImpl) chunk).fabric_writeAttachmentsToNbt(writeView);

			//noinspection SimplifyOptionalCallChains
			class_2487 attachmentNbtData = writeView.method_71475().method_10562(AttachmentTarget.NBT_ATTACHMENT_KEY).orElse(null);

			if (attachmentNbtData != null) {
				((SerializableChunkDataMixin) (Object) cir.getReturnValue()).attachmentNbtData = attachmentNbtData;
			}
		}
	}

	@Inject(method = "write", at = @At("RETURN"))
	private void writeChunkAttachments(CallbackInfoReturnable<class_2487> cir) {
		if (attachmentNbtData != null) {
			cir.getReturnValue().method_10566(AttachmentTarget.NBT_ATTACHMENT_KEY, attachmentNbtData);
		}
	}
}
