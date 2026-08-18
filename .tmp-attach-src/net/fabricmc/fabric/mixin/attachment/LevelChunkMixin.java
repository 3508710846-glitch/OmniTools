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

import java.util.Map;
import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.impl.attachment.AttachmentTargetImpl;
import net.fabricmc.fabric.impl.attachment.AttachmentTypeImpl;
import net.fabricmc.fabric.impl.attachment.sync.AttachmentChange;
import net.fabricmc.fabric.impl.attachment.sync.AttachmentSync;
import net.minecraft.class_1937;
import net.minecraft.class_2338;
import net.minecraft.class_2586;
import net.minecraft.class_2791;
import net.minecraft.class_2818;
import net.minecraft.class_2839;
import net.minecraft.class_3218;
import net.minecraft.class_3222;
import net.minecraft.class_5455;

@Mixin(class_2818.class)
abstract class LevelChunkMixin extends AttachmentTargetsMixin implements AttachmentTargetImpl {
	@Shadow
	@Final
	class_1937 level;

	@Shadow
	public abstract Map<class_2338, class_2586> getBlockEntities();

	@Inject(method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V", at = @At("TAIL"))
	private void transferProtoChunkAttachment(class_3218 world, class_2839 protoChunk, class_2818.class_6829 entityLoader, CallbackInfo ci) {
		AttachmentTargetImpl.transfer(protoChunk, this, false);
	}

	@Override
	public void fabric_computeInitialSyncChanges(class_3222 player, Consumer<AttachmentChange> changeOutput) {
		super.fabric_computeInitialSyncChanges(player, changeOutput);

		for (class_2586 be : this.getBlockEntities().values()) {
			((AttachmentTargetImpl) be).fabric_computeInitialSyncChanges(player, changeOutput);
		}
	}

	@Override
	public void fabric_syncChange(AttachmentType<?> type, AttachmentChange change) {
		if (this.level instanceof class_3218 serverWorld) {
			// can't shadow from Chunk because this already extends a supermixin
			PlayerLookup.tracking(serverWorld, ((class_2791) (Object) this).method_12004())
					.forEach(player -> {
						if (((AttachmentTypeImpl<?>) type).syncPredicate().test(this, player)) {
							AttachmentSync.trySync(change, player);
						}
					});
		}
	}

	@Override
	public boolean fabric_shouldTryToSync() {
		return !this.level.method_8608();
	}

	@Override
	public class_5455 fabric_getDynamicRegistryManager() {
		return level.method_30349();
	}
}
