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

package net.fabricmc.fabric.impl.client.screen;

import java.util.AbstractList;
import java.util.List;
import net.minecraft.class_339;
import net.minecraft.class_364;
import net.minecraft.class_4068;
import net.minecraft.class_6379;

// TODO: When events for listening to addition of child elements are added, fire events from this list.
public final class ButtonList extends AbstractList<class_339> {
	private final List<class_4068> drawables;
	private final List<class_6379> selectables;
	private final List<class_364> children;

	public ButtonList(List<class_4068> drawables, List<class_6379> selectables, List<class_364> children) {
		this.drawables = drawables;
		this.selectables = selectables;
		this.children = children;
	}

	@Override
	public class_339 get(int index) {
		final int drawableIndex = translateIndex(drawables, index, false);
		return (class_339) drawables.get(drawableIndex);
	}

	@Override
	public class_339 set(int index, class_339 element) {
		final int drawableIndex = translateIndex(drawables, index, false);
		drawables.set(drawableIndex, element);

		final int selectableIndex = translateIndex(selectables, index, false);
		selectables.set(selectableIndex, element);

		final int childIndex = translateIndex(children, index, false);
		return (class_339) children.set(childIndex, element);
	}

	@Override
	public void add(int index, class_339 element) {
		// ensure no duplicates
		final int duplicateIndex = drawables.indexOf(element);

		if (duplicateIndex >= 0) {
			drawables.remove(element);
			selectables.remove(element);
			children.remove(element);

			if (duplicateIndex <= translateIndex(drawables, index, true)) {
				index--;
			}
		}

		final int drawableIndex = translateIndex(drawables, index, true);
		drawables.add(drawableIndex, element);

		final int selectableIndex = translateIndex(selectables, index, true);
		selectables.add(selectableIndex, element);

		final int childIndex = translateIndex(children, index, true);
		children.add(childIndex, element);
	}

	@Override
	public class_339 remove(int index) {
		index = translateIndex(drawables, index, false);

		final class_339 removedButton = (class_339) drawables.remove(index);
		this.selectables.remove(removedButton);
		this.children.remove(removedButton);

		return removedButton;
	}

	@Override
	public int size() {
		int ret = 0;

		for (class_4068 drawable : drawables) {
			if (drawable instanceof class_339) {
				ret++;
			}
		}

		return ret;
	}

	private int translateIndex(List<?> list, int index, boolean allowAfter) {
		int remaining = index;

		for (int i = 0, max = list.size(); i < max; i++) {
			if (list.get(i) instanceof class_339) {
				if (remaining == 0) {
					return i;
				}

				remaining--;
			}
		}

		if (allowAfter && remaining == 0) {
			return list.size();
		}

		throw new IndexOutOfBoundsException(String.format("Index: %d, Size: %d", index, index - remaining));
	}
}
