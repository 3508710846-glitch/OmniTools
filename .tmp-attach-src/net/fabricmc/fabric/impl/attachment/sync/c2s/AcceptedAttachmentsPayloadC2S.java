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

package net.fabricmc.fabric.impl.attachment.sync.c2s;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.class_2540;
import net.minecraft.class_2960;
import net.minecraft.class_8710;
import net.minecraft.class_9135;
import net.minecraft.class_9139;

public record AcceptedAttachmentsPayloadC2S(Set<class_2960> acceptedAttachments) implements class_8710 {
	public static final class_9139<class_2540, AcceptedAttachmentsPayloadC2S> CODEC = class_9139.method_56434(
			class_9135.method_56376(HashSet::new, class_2960.field_48267), AcceptedAttachmentsPayloadC2S::acceptedAttachments,
			AcceptedAttachmentsPayloadC2S::new
	);
	public static final class_2960 PACKET_ID = class_2960.method_60655("fabric", "accepted_attachments_v1");
	public static final class_9154<AcceptedAttachmentsPayloadC2S> ID = new class_9154<>(PACKET_ID);

	@Override
	public class_9154<? extends class_8710> method_56479() {
		return ID;
	}
}
