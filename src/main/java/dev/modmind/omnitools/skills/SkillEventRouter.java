package dev.modmind.omnitools.skills;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Maps vanilla event facts to one auditable skill XP request without mutating game state. */
public final class SkillEventRouter {
    private static final long BLOCK_XP = 5L;
    private static final long MELEE_XP = 15L;
    private static final long ARCHERY_XP = 20L;

    private SkillEventRouter() {
    }

    /** Ignores immature crops so repeatedly breaking and replacing them cannot grant farming XP. */
    public static Optional<BlockBreakRoute> blockBreak(BlockState state) {
        if (state == null) return Optional.empty();
        boolean crop = state.getBlock() instanceof CropBlock;
        boolean matureCrop = !crop || ((CropBlock) state.getBlock()).isMaxAge(state);
        return routeBlock(state.is(BlockTags.LOGS), state.is(BlockTags.CROPS), matureCrop,
                state.is(BlockTags.MINEABLE_WITH_SHOVEL));
    }

    static Optional<BlockBreakRoute> routeBlock(boolean log, boolean crop, boolean matureCrop, boolean shovelMineable) {
        if (crop && !matureCrop) return Optional.empty();
        if (log) return Optional.of(new BlockBreakRoute("woodcutting", SkillXpSource.BLOCK_BREAK));
        if (crop) return Optional.of(new BlockBreakRoute("herbalism", SkillXpSource.SURVIVAL));
        if (shovelMineable) return Optional.of(new BlockBreakRoute("excavation", SkillXpSource.BLOCK_BREAK));
        return Optional.of(new BlockBreakRoute("mining", SkillXpSource.BLOCK_BREAK));
    }

    public static CombatRoute entityKill(String heldItemPath) {
        String item = heldItemPath == null ? "" : heldItemPath.toLowerCase(Locale.ROOT);
        if (item.contains("bow") || item.contains("crossbow")) return new CombatRoute("archery", ARCHERY_XP);
        if (item.contains("axe")) return new CombatRoute("axes", MELEE_XP);
        return new CombatRoute("swords", MELEE_XP);
    }

    public static String blockOperation(UUID playerId, BlockPos position, long gameTime, String skillId) {
        if (playerId == null || position == null || skillId == null || skillId.isBlank()) {
            throw new IllegalArgumentException("Block XP operation identity is incomplete");
        }
        return "block:" + playerId + ":" + position.asLong() + ":" + gameTime + ":" + skillId;
    }

    public static String entityOperation(UUID playerId, UUID targetId, long gameTime, String skillId) {
        if (playerId == null || targetId == null || skillId == null || skillId.isBlank()) {
            throw new IllegalArgumentException("Entity XP operation identity is incomplete");
        }
        return "kill:" + playerId + ":" + targetId + ":" + gameTime + ":" + skillId;
    }

    public record BlockBreakRoute(String skillId, SkillXpSource source) {
        public BlockBreakRoute {
            if (skillId == null || skillId.isBlank() || source == null) {
                throw new IllegalArgumentException("Block XP route is invalid");
            }
        }

        public long xp() {
            return BLOCK_XP;
        }
    }

    public record CombatRoute(String skillId, long xp) {
        public CombatRoute {
            if (skillId == null || skillId.isBlank() || xp <= 0L) {
                throw new IllegalArgumentException("Combat XP route is invalid");
            }
        }
    }
}
