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

package net.fabricmc.fabric.mixin.screen;

import net.minecraft.class_11909;
import net.minecraft.class_2561;
import net.minecraft.class_437;
import net.minecraft.class_465;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(class_465.class)
public abstract class AbstractContainerScreenMixin extends class_437 {
	private AbstractContainerScreenMixin(class_2561 title) {
		super(title);
	}

	@Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
	private void callSuperMouseReleased(class_11909 ctx, CallbackInfoReturnable<Boolean> cir) {
		if (super.method_25406(ctx)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
	private void callSuperMouseReleased(class_11909 ctx, double deltaX, double deltaY, CallbackInfoReturnable<Boolean> cir) {
		if (super.method_25403(ctx, deltaX, deltaY)) {
			cir.setReturnValue(true);
		}
	}
}
