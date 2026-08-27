package dev.modmind.omnitools.achievement;

import dev.modmind.omnitools.AchievementConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Expands explicit IDs, tags, groups and wildcards while loading a configuration. */
public final class StatisticTargetResolver {
    public static final int MAX_TARGETS = 2048;

    private StatisticTargetResolver() {
    }

    public static List<String> resolve(AchievementConfig.RequirementType type, List<String> rawTargets,
                                       Map<String, List<String>> groups, String context) {
        if (rawTargets == null || rawTargets.isEmpty()) {
            throw new IllegalArgumentException("No targets configured for " + context);
        }
        List<String> expanded = new ArrayList<>();
        Set<String> visiting = new HashSet<>();
        ExpansionMarkers markers = new ExpansionMarkers();
        for (String raw : rawTargets) {
            inspect(raw, groups, new HashSet<>(), markers, context);
        }
        if (markers.wildcard && markers.concrete) {
            throw new IllegalArgumentException("Wildcard target '*' cannot be combined with other targets for " + context);
        }
        for (String raw : rawTargets) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("Target must be a non-empty string for " + context);
            }
            expand(type, raw.trim(), groups, visiting, expanded, context);
            if (expanded.size() > MAX_TARGETS) {
                throw new IllegalArgumentException("Target count for " + context + " exceeds " + MAX_TARGETS);
            }
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>(expanded);
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("Targets for " + context + " resolved to an empty set");
        }
        if (unique.size() > MAX_TARGETS) {
            throw new IllegalArgumentException("Target count for " + context + " exceeds " + MAX_TARGETS);
        }
        return List.copyOf(unique);
    }

    private static void inspect(String raw, Map<String, List<String>> groups, Set<String> visiting,
                                ExpansionMarkers markers, String context) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Target must be a non-empty string for " + context);
        }
        String value = raw.trim();
        if (value.equals("*")) {
            markers.wildcard = true;
            return;
        }
        if (value.startsWith("$")) {
            String group = value.substring(1).trim().toLowerCase(Locale.ROOT);
            if (group.isEmpty() || !groups.containsKey(group)) {
                throw new IllegalArgumentException("Unknown target group " + value + " for " + context);
            }
            if (!visiting.add(group)) {
                throw new IllegalArgumentException("Circular target group reference involving $" + group
                        + " for " + context);
            }
            for (String nested : groups.get(group)) {
                inspect(nested, groups, visiting, markers, context);
            }
            visiting.remove(group);
            return;
        }
        markers.concrete = true;
    }

    private static void expand(AchievementConfig.RequirementType type, String raw,
                               Map<String, List<String>> groups, Set<String> visiting,
                               List<String> output, String context) {
        if (raw.startsWith("$")) {
            String group = raw.substring(1).trim().toLowerCase(Locale.ROOT);
            if (group.isEmpty() || !groups.containsKey(group)) {
                throw new IllegalArgumentException("Unknown target group " + raw + " for " + context);
            }
            if (!visiting.add(group)) {
                throw new IllegalArgumentException("Circular target group reference involving $" + group
                        + " for " + context);
            }
            for (String nested : groups.get(group)) {
                expand(type, nested, groups, visiting, output, context);
            }
            visiting.remove(group);
            return;
        }
        if (raw.equals("*")) {
            registry(type).keySet().stream().map(Identifier::toString).forEach(output::add);
            return;
        }
        if (raw.equals("@block_items")) {
            if (type != AchievementConfig.RequirementType.ITEM_USED) {
                throw new IllegalArgumentException("@block_items is only valid for item_used statistics in " + context);
            }
            for (Item item : BuiltInRegistries.ITEM) {
                if (item instanceof BlockItem) {
                    output.add(BuiltInRegistries.ITEM.getKey(item).toString());
                }
            }
            return;
        }
        if (raw.startsWith("#")) {
            Identifier tagId = Identifier.tryParse(raw.substring(1));
            if (tagId == null) {
                throw new IllegalArgumentException("Invalid tag target " + raw + " for " + context);
            }
            List<String> tagTargets = switch (type.domain()) {
                case BLOCK -> tagEntries(BuiltInRegistries.BLOCK, TagKey.create(Registries.BLOCK, tagId));
                case ITEM -> tagEntries(BuiltInRegistries.ITEM, TagKey.create(Registries.ITEM, tagId));
                case ENTITY -> tagEntries(BuiltInRegistries.ENTITY_TYPE,
                        TagKey.create(Registries.ENTITY_TYPE, tagId));
                case CUSTOM -> tagEntries(BuiltInRegistries.CUSTOM_STAT,
                        TagKey.create(Registries.CUSTOM_STAT, tagId));
            };
            boolean found = !tagTargets.isEmpty();
            output.addAll(tagTargets);
            if (!found) {
                throw new IllegalArgumentException("Unknown or empty tag target " + raw + " for " + context);
            }
            return;
        }
        if (raw.contains("#")) {
            throw new IllegalArgumentException("Invalid target " + raw + " for " + context);
        }
        output.add(raw);
    }

    @SuppressWarnings("unchecked")
    private static Registry<Object> registry(AchievementConfig.RequirementType type) {
        return switch (type.domain()) {
            case BLOCK -> (Registry<Object>) (Registry<?>) BuiltInRegistries.BLOCK;
            case ITEM -> (Registry<Object>) (Registry<?>) BuiltInRegistries.ITEM;
            case ENTITY -> (Registry<Object>) (Registry<?>) BuiltInRegistries.ENTITY_TYPE;
            case CUSTOM -> (Registry<Object>) (Registry<?>) BuiltInRegistries.CUSTOM_STAT;
        };
    }

    private static <T> List<String> tagEntries(Registry<T> registry, TagKey<T> tag) {
        List<String> result = new ArrayList<>();
        for (Holder<T> holder : registry.getTagOrEmpty(tag)) {
            holder.unwrapKey().ifPresent(key -> result.add(key.identifier().toString()));
        }
        return result;
    }

    private static final class ExpansionMarkers {
        private boolean wildcard;
        private boolean concrete;
    }
}
