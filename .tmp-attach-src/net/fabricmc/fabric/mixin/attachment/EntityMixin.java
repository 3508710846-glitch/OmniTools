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

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.impl.attachment.AttachmentTargetImpl;
import net.fabricmc.fabric.impl.attachment.AttachmentTypeImpl;
import net.fabricmc.fabric.impl.attachment.sync.AttachmentChange;
import net.fabricmc.fabric.impl.attachment.sync.AttachmentSync;
import net.fabricmc.fabric.impl.attachment.sync.AttachmentTargetInfo;
import net.minecraft.class_11368;
import net.minecraft.class_11372;
import net.minecraft.class_1297;
import net.minecraft.class_1937;
import net.minecraft.class_3222;
import net.minecraft.class_5455;

@Mixin(class_1297.class)
abstract class EntityMixin implements AttachmentTargetImpl {
	@Shadow
	private int id;

	@Shadow
	public abstract class_1937 level();

	@Inject(
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;readAdditionalSaveData(Lnet/minecraft/world/level/storage/ValueInput;)V"),
			method = "load"
	)
	private void readEntityAttachments(class_11368 data, CallbackInfo ci) {
		this.fabric_readAttachmentsFromNbt(data);
	}

	@Inject(
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;addAdditionalSaveData(Lnet/minecraft/world/level/storage/ValueOutput;)V"),
			method = "saveWithoutId"
	)
	private void writeEntityAttachments(class_11372 view, CallbackInfo ci) {
		this.fabric_writeAttachmentsToNbt(view);
	}

	@Override
	public AttachmentTargetInfo<?> fabric_getSyncTargetInfo() {
		return new AttachmentTargetInfo.EntityTarget(this.id);
	}

	@Override
	public void fabric_syncChange(AttachmentType<?> type, AttachmentChange change) {
		if (!this.level().method_8608()) {
			AttachmentSyncPredicate predicate = ((AttachmentTypeImpl<?>) type).syncPredicate();

			if ((Object) this instanceof class_3222 self && predicate.test(this, self)) {
				// Players do not track themselves
				AttachmentSync.trySync(change, self);
			}

			PlayerLookup.tracking((class_1297) (Object) this)
					.forEach(player -> {
						if (predicate.test(this, player)) {
							AttachmentSync.trySync(change, player);
						}
					});
		}
	}

	@Override
	public boolean fabric_shouldTryToSync() {
		return !this.level().method_8608();
	}

	@Override
	public class_5455 fabric_getDynamicRegistryManager() {
		return this.level().method_30349();
	}
}
