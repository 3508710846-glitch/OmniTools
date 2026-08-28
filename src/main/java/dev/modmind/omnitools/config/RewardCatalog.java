package dev.modmind.omnitools.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.modmind.omnitools.reward.RewardDefinition;
import net.minecraft.core.HolderLookup;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable common reward library and recursively-expanded reward sets. */
public final class RewardCatalog {
    public static final int MAX_SET_DEPTH = 16;
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]{1,64}");

    private final Map<String, RewardDefinition> rewards;
    private final Map<String, List<RewardDefinition>> sets;

    private RewardCatalog(Map<String, RewardDefinition> rewards, Map<String, List<RewardDefinition>> sets) {
        this.rewards = Collections.unmodifiableMap(new LinkedHashMap<>(rewards));
        Map<String, List<RewardDefinition>> copy = new LinkedHashMap<>();
        sets.forEach((id, definitions) -> copy.put(id, List.copyOf(definitions)));
        this.sets = Collections.unmodifiableMap(copy);
    }

    public static RewardCatalog empty() {
        return new RewardCatalog(Map.of(), Map.of());
    }

    static RewardCatalog parse(JsonObject root, HolderLookup.Provider registries) {
        return parse(root, registries, Map.of());
    }

    static RewardCatalog parse(JsonObject root, HolderLookup.Provider registries, Map<String, JsonObject> templates) {
        JsonElement rewardsElement = root.get("rewards");
        JsonElement setsElement = root.get("sets");
        if (rewardsElement == null || !rewardsElement.isJsonObject()) {
            throw new JsonParseException("common.rewards.rewards must be an object");
        }
        if (setsElement == null || !setsElement.isJsonObject()) {
            throw new JsonParseException("common.rewards.sets must be an object");
        }
        Map<String, RewardDefinition> definitions = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : rewardsElement.getAsJsonObject().entrySet()) {
            String id = normalizedId(entry.getKey(), "common.rewards.rewards");
            if (!entry.getValue().isJsonObject()) {
                throw new JsonParseException("common.rewards.rewards." + id + " must be an object");
            }
            if (definitions.containsKey(id)) {
                throw new JsonParseException("common.rewards.rewards contains duplicate id " + id);
            }
            JsonElement expanded = CommonConfig.expandRewardTemplates(entry.getValue(), templates,
                    "common.rewards.rewards." + id);
            if (!expanded.isJsonObject()) {
                throw new JsonParseException("common.rewards.rewards." + id + " must resolve to an object");
            }
            definitions.put(id, RewardDefinition.parseCatalogDefinition(id, expanded.getAsJsonObject(),
                    "common.rewards.rewards." + id, registries));
        }

        Map<String, List<Reference>> rawSets = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : setsElement.getAsJsonObject().entrySet()) {
            String id = normalizedId(entry.getKey(), "common.rewards.sets");
            if (!entry.getValue().isJsonObject()) {
                throw new JsonParseException("common.rewards.sets." + id + " must be an object");
            }
            if (rawSets.containsKey(id)) {
                throw new JsonParseException("common.rewards.sets contains duplicate id " + id);
            }
            rawSets.put(id, parseSet(entry.getValue().getAsJsonObject(), "common.rewards.sets." + id));
        }

        Map<String, List<RewardDefinition>> expandedSets = new LinkedHashMap<>();
        for (String id : rawSets.keySet()) {
            expandedSets.put(id, resolveSet(id, rawSets, definitions, expandedSets, new ArrayDeque<>(), 0));
        }
        return new RewardCatalog(definitions, expandedSets);
    }

    /** Returns catalog definitions for `{ "reward": "..." }` and `{ "set": "..." }` only. */
    public List<RewardDefinition> expandReference(JsonObject entry, String context) {
        if (entry == null || (!entry.has("reward") && !entry.has("set"))) {
            return List.of();
        }
        if (entry.has("reward") && entry.has("set")) {
            throw new JsonParseException(context + " cannot contain both reward and set");
        }
        if (entry.entrySet().size() != 1) {
            throw new JsonParseException(context + " reward and set references cannot override reward fields");
        }
        if (entry.has("reward")) {
            String id = referenceId(entry.get("reward"), context + ".reward");
            RewardDefinition reward = rewards.get(id);
            if (reward == null) {
                throw new JsonParseException(context + " references unknown catalog reward " + id);
            }
            return List.of(reward);
        }
        String id = referenceId(entry.get("set"), context + ".set");
        List<RewardDefinition> set = sets.get(id);
        if (set == null) {
            throw new JsonParseException(context + " references unknown reward set " + id);
        }
        return set;
    }

    public Map<String, RewardDefinition> rewards() {
        return rewards;
    }

    public Map<String, List<RewardDefinition>> sets() {
        return sets;
    }

    /** True when a business reward array contains a catalog reference whose resolved content affects its meaning. */
    public boolean containsReference(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                if (containsReference(child)) {
                    return true;
                }
            }
            return false;
        }
        if (!element.isJsonObject()) {
            return false;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("reward") || object.has("set")) {
            return true;
        }
        for (JsonElement child : object.asMap().values()) {
            if (containsReference(child)) {
                return true;
            }
        }
        return false;
    }

    private static List<Reference> parseSet(JsonObject source, String context) {
        ConfigFieldReporter.warnUnknown(source, context, Set.of("rewards"));
        JsonElement rewards = source.get("rewards");
        if (rewards == null || !rewards.isJsonArray() || rewards.getAsJsonArray().isEmpty()) {
            throw new JsonParseException(context + ".rewards must be a non-empty array");
        }
        List<Reference> result = new ArrayList<>();
        JsonArray entries = rewards.getAsJsonArray();
        for (int index = 0; index < entries.size(); index++) {
            JsonElement entry = entries.get(index);
            String entryContext = context + ".rewards[" + index + "]";
            if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
                result.add(new Reference(ReferenceType.REWARD, normalizedId(entry.getAsString(), entryContext)));
                continue;
            }
            if (!entry.isJsonObject()) {
                throw new JsonParseException(entryContext + " must be a reward id or a set reference");
            }
            JsonObject object = entry.getAsJsonObject();
            if (object.entrySet().size() != 1 || (!object.has("reward") && !object.has("set"))) {
                throw new JsonParseException(entryContext + " must contain exactly reward or set");
            }
            if (object.has("reward")) {
                result.add(new Reference(ReferenceType.REWARD, referenceId(object.get("reward"), entryContext + ".reward")));
            } else {
                result.add(new Reference(ReferenceType.SET, referenceId(object.get("set"), entryContext + ".set")));
            }
        }
        return List.copyOf(result);
    }

    private static List<RewardDefinition> resolveSet(String id, Map<String, List<Reference>> rawSets,
                                                      Map<String, RewardDefinition> rewards,
                                                      Map<String, List<RewardDefinition>> resolved,
                                                      Deque<String> path, int depth) {
        List<RewardDefinition> cached = resolved.get(id);
        if (cached != null) {
            return cached;
        }
        if (depth >= MAX_SET_DEPTH) {
            throw new JsonParseException("common reward set " + id + " exceeds max nesting depth " + MAX_SET_DEPTH);
        }
        if (path.contains(id)) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(id);
            throw new JsonParseException("common reward set cycle: " + String.join(" -> ", cycle));
        }
        List<Reference> references = rawSets.get(id);
        if (references == null) {
            throw new JsonParseException("common reward set references unknown set " + id);
        }
        path.addLast(id);
        List<RewardDefinition> result = new ArrayList<>();
        for (Reference reference : references) {
            if (reference.type() == ReferenceType.REWARD) {
                RewardDefinition reward = rewards.get(reference.id());
                if (reward == null) {
                    throw new JsonParseException("common reward set " + id + " references unknown reward "
                            + reference.id());
                }
                result.add(reward);
            } else {
                result.addAll(resolveSet(reference.id(), rawSets, rewards, resolved, path, depth + 1));
            }
        }
        path.removeLast();
        Set<String> ids = new HashSet<>();
        for (RewardDefinition definition : result) {
            if (!ids.add(definition.id())) {
                throw new JsonParseException("common reward set " + id + " expands duplicate reward id "
                        + definition.id());
            }
        }
        List<RewardDefinition> immutable = List.copyOf(result);
        resolved.put(id, immutable);
        return immutable;
    }

    private static String referenceId(JsonElement element, String context) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(context + " must be a string");
        }
        return normalizedId(element.getAsString(), context);
    }

    private static String normalizedId(String value, String context) {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) {
            throw new JsonParseException(context + " must match " + ID.pattern());
        }
        return id;
    }

    private enum ReferenceType {
        REWARD,
        SET
    }

    private record Reference(ReferenceType type, String id) {
    }
}
