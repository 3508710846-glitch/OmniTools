package dev.modmind.omnitools;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import dev.modmind.omnitools.config.ModuleId;

/** Applies and removes the effects belonging to a player's selected title. */
public final class TitleEffectService {
    private static final String MODIFIER_NAMESPACE = ModMindEntry.MOD_ID;
    private static final Map<UUID, AppliedEffects> APPLIED = new HashMap<>();
    private static long lastParticleTick = Long.MIN_VALUE;

    private TitleEffectService() {
    }

    public static void refresh(ServerPlayer player) {
        remove(player);
        if (!ModMindEntry.isModuleEnabled(ModuleId.TITLE_EFFECTS)
                || !ModMindEntry.isModuleEnabled(ModuleId.TITLES)) {
            player.setHealth(Math.min(player.getHealth(), player.getMaxHealth()));
            return;
        }
        if (!ModMindEntry.titleConfig().effectsEnabled(player.getUUID())) {
            player.setHealth(Math.min(player.getHealth(), player.getMaxHealth()));
            return;
        }
        ModMindEntry.titleConfig().selectedTitle(player.getUUID()).ifPresent(title -> apply(player, title));
        player.setHealth(Math.min(player.getHealth(), player.getMaxHealth()));
    }

    public static void remove(ServerPlayer player) {
        AppliedEffects applied = APPLIED.remove(player.getUUID());
        if (applied == null) {
            return;
        }
        for (Map.Entry<Holder<MobEffect>, AppliedPotion> entry : applied.potions.entrySet()) {
            Holder<MobEffect> effect = entry.getKey();
            AppliedPotion appliedPotion = entry.getValue();
            MobEffectInstance current = player.getEffect(effect);
            if (isCurrentTitlePotion(current, appliedPotion)) {
                player.removeEffect(effect);
                if (appliedPotion.original() != null) {
                    player.addEffect(new MobEffectInstance(appliedPotion.original()));
                }
            }
        }
        for (AttributeModifierRef reference : applied.attributeModifiers) {
            reference.instance().removeModifier(reference.id());
        }
    }

    public static void forget(ServerPlayer player) {
        APPLIED.remove(player.getUUID());
    }

    public static void refreshAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            refresh(player);
        }
    }

    public static void removeAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            remove(player);
        }
    }

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() == lastParticleTick) {
            return;
        }
        lastParticleTick = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            AppliedEffects applied = APPLIED.get(player.getUUID());
            if (applied == null || applied.particles.isEmpty()) {
                continue;
            }
            if (!applied.hasMoved(player)) {
                continue;
            }
            for (TitleEffectConfig.EffectDefinition definition : applied.particles) {
                if (player.tickCount % definition.frequency() != 0 || !player.onGround()) {
                    continue;
                }
                ParticleOptions options = particle(definition.particle());
                if (options != null) {
                    ServerLevel level = player.level();
                    level.sendParticles(options, player.getX(), player.getY() + 0.1D, player.getZ(), 1,
                            0.18D, 0.02D, 0.18D, 0.01D);
                }
            }
        }
    }

    /** Checks a custom permission unlocked by an active title effect. */
    public static boolean hasPermission(ServerPlayer player, String permission) {
        AppliedEffects applied = APPLIED.get(player.getUUID());
        return applied != null && applied.permissions.contains(permission.trim().toLowerCase(Locale.ROOT));
    }

    /** Extends the native command permission set while an effect-bearing title is active. */
    public static PermissionSet permissionSet(ServerPlayer player, PermissionSet base) {
        AppliedEffects applied = APPLIED.get(player.getUUID());
        if (applied == null || applied.permissions.isEmpty()) {
            return base;
        }
        return permission -> base.hasPermission(permission) || grants(applied.permissions, permission);
    }

    private static void apply(ServerPlayer player, TitleConfig.TitleDefinition title) {
        AppliedEffects applied = new AppliedEffects();
        for (String effectId : title.effects()) {
            ModMindEntry.titleEffectConfig().definition(effectId).ifPresent(definition -> apply(player, definition, applied));
        }
        APPLIED.put(player.getUUID(), applied);
    }

    private static void apply(ServerPlayer player, TitleEffectConfig.EffectDefinition definition,
                              AppliedEffects applied) {
        switch (definition.type()) {
            case POTION -> applyPotion(player, definition, applied);
            case ATTRIBUTE -> applyAttribute(player, definition, applied);
            case PARTICLE -> applied.particles.add(definition);
            case PERMISSION -> applied.permissions.add(definition.permission().trim().toLowerCase(Locale.ROOT));
        }
    }

    private static void applyPotion(ServerPlayer player, TitleEffectConfig.EffectDefinition definition,
                                    AppliedEffects applied) {
        Identifier id = Identifier.tryParse(definition.effect());
        if (id == null) {
            return;
        }
        var holder = BuiltInRegistries.MOB_EFFECT.get(id);
        if (holder.isEmpty()) {
            return;
        }
        Holder<MobEffect> effect = holder.get();
        if (applied.potions.containsKey(effect)) {
            return;
        }
        MobEffectInstance previous = player.getEffect(effect);
        int duration = definition.duration() < 0 ? MobEffectInstance.INFINITE_DURATION : definition.duration();
        player.addEffect(new MobEffectInstance(effect, duration, definition.amplifier(), true, false, true));
        MobEffectInstance current = player.getEffect(effect);
        if (isCurrentTitlePotion(current, definition.amplifier(), definition.duration() < 0, duration)) {
            applied.potions.put(effect, new AppliedPotion(previous == null ? null : new MobEffectInstance(previous),
                    definition.amplifier(), definition.duration() < 0, duration));
        }
    }

    private static void applyAttribute(ServerPlayer player, TitleEffectConfig.EffectDefinition definition,
                                       AppliedEffects applied) {
        Identifier attributeId = normalizedAttributeId(definition.attribute());
        if (attributeId == null) {
            return;
        }
        var holder = BuiltInRegistries.ATTRIBUTE.get(attributeId);
        if (holder.isEmpty()) {
            return;
        }
        AttributeInstance instance = player.getAttribute(holder.get());
        if (instance == null) {
            return;
        }
        Identifier modifierId = Identifier.fromNamespaceAndPath(MODIFIER_NAMESPACE,
                "title_effect/" + definition.id());
        instance.addOrUpdateTransientModifier(new AttributeModifier(modifierId, definition.amount(),
                switch (definition.operation()) {
                    case ADDITION -> AttributeModifier.Operation.ADD_VALUE;
                    case ADD_MULTIPLIED_BASE -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
                    case ADD_MULTIPLIED_TOTAL -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
                }));
        applied.attributeModifiers.add(new AttributeModifierRef(instance, modifierId));
    }

    private static Identifier normalizedAttributeId(String configuredId) {
        Identifier id = Identifier.tryParse(configuredId);
        if (id == null) {
            return null;
        }
        return switch (id.toString()) {
            case "minecraft:generic.max_health" -> Identifier.withDefaultNamespace("max_health");
            case "minecraft:generic.movement_speed" -> Identifier.withDefaultNamespace("movement_speed");
            case "minecraft:generic.attack_damage" -> Identifier.withDefaultNamespace("attack_damage");
            case "minecraft:generic.armor" -> Identifier.withDefaultNamespace("armor");
            default -> id;
        };
    }

    private static ParticleOptions particle(String id) {
        Identifier particleId = Identifier.tryParse(id);
        if (particleId == null) {
            return null;
        }
        if ("minecraft:redstone".equals(particleId.toString())) {
            return DustParticleOptions.REDSTONE;
        }
        return BuiltInRegistries.PARTICLE_TYPE.get(particleId)
                .map(holder -> holder.value())
                .filter(value -> value instanceof ParticleOptions)
                .map(value -> (ParticleOptions) value)
                .orElse(null);
    }

    private static boolean isCurrentTitlePotion(MobEffectInstance current, AppliedPotion expected) {
        return isCurrentTitlePotion(current, expected.amplifier(), expected.infinite(), expected.duration());
    }

    private static boolean isCurrentTitlePotion(MobEffectInstance current, int amplifier, boolean infinite, int duration) {
        if (current == null || current.getAmplifier() != amplifier) {
            return false;
        }
        return infinite ? current.isInfiniteDuration() : current.getDuration() > 0 && current.getDuration() <= duration;
    }

    private static boolean grants(Set<String> granted, Permission permission) {
        if (permission instanceof Permission.Atom atom) {
            return granted.contains(atom.id().toString());
        }
        if (permission instanceof Permission.HasCommandLevel commandLevel) {
            for (String value : granted) {
                PermissionLevel grantedLevel = commandLevel(value);
                if (grantedLevel != null && grantedLevel.isEqualOrHigherThan(commandLevel.level())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static PermissionLevel commandLevel(String permission) {
        return switch (permission) {
            case "omnitools:command.moderator", "omnitools:commands/moderator", "omnitools:commands/moderators" ->
                    PermissionLevel.MODERATORS;
            case "omnitools:command.gamemaster", "omnitools:commands/gamemaster", "omnitools:commands/gamemasters" ->
                    PermissionLevel.GAMEMASTERS;
            case "omnitools:command.admin", "omnitools:commands/admin", "omnitools:commands/admins" ->
                    PermissionLevel.ADMINS;
            case "omnitools:command.owner", "omnitools:commands/owner", "omnitools:commands/owners" ->
                    PermissionLevel.OWNERS;
            default -> null;
        };
    }

    private static final class AppliedEffects {
        private final Map<Holder<MobEffect>, AppliedPotion> potions = new HashMap<>();
        private final Set<AttributeModifierRef> attributeModifiers = new HashSet<>();
        private final Set<String> permissions = new HashSet<>();
        private final Set<TitleEffectConfig.EffectDefinition> particles = new HashSet<>();
        private boolean particlePositionKnown;
        private double lastParticleX;
        private double lastParticleZ;

        private boolean hasMoved(ServerPlayer player) {
            double currentX = player.getX();
            double currentZ = player.getZ();
            if (!particlePositionKnown) {
                particlePositionKnown = true;
                lastParticleX = currentX;
                lastParticleZ = currentZ;
                return false;
            }
            double deltaX = currentX - lastParticleX;
            double deltaZ = currentZ - lastParticleZ;
            lastParticleX = currentX;
            lastParticleZ = currentZ;
            return deltaX * deltaX + deltaZ * deltaZ > 0.0001D;
        }
    }

    private record AppliedPotion(MobEffectInstance original, int amplifier, boolean infinite, int duration) {
    }

    private record AttributeModifierRef(AttributeInstance instance, Identifier id) {
    }
}
