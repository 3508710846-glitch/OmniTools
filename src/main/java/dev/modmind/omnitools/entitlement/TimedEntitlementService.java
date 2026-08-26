package dev.modmind.omnitools.entitlement;

import dev.modmind.omnitools.ModMindEntry;
import dev.modmind.omnitools.ServerText;
import dev.modmind.omnitools.TitleData;
import dev.modmind.omnitools.TitleDisplayService;
import dev.modmind.omnitools.TitleEffectService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Drives online, equipped entitlement consumption and batches durable writes. */
public final class TimedEntitlementService {
    public static final int SAVE_INTERVAL_TICKS = 100;
    private long lastTick = Long.MIN_VALUE;

    public void tickTitles(MinecraftServer server) {
        long tick = server.getTickCount();
        if (tick == lastTick) {
            return;
        }
        lastTick = tick;
        TitleData data = TitleData.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String selected = ModMindEntry.titleConfig().selectedTitleId(player.getUUID());
            // A removed definition stays in data but is not wearable and therefore never consumes time.
            if (selected.isEmpty() || ModMindEntry.titleConfig().definition(selected).isEmpty()) {
                continue;
            }
            TitleData.TickResult result = data.consumeSelectedActiveTick(player.getUUID());
            if (result.expired()) {
                TitleDisplayService.refreshPlayer(player);
                TitleEffectService.refresh(player);
                player.displayClientMessage(ServerText.translatable("message.omnitools.title.expired"), true);
            }
        }
        if (tick % SAVE_INTERVAL_TICKS == 0L) {
            data.flushTimedChanges();
        }
    }

    public void flush(MinecraftServer server) {
        if (server != null) {
            TitleData.get(server).flushTimedChanges();
        }
    }
}
