package dev.combi.bedwarsshuffle.feature.teamswap;

import de.marcely.bedwars.api.event.arena.ArenaUnloadEvent;
import de.marcely.bedwars.api.event.arena.RoundEndEvent;
import de.marcely.bedwars.api.event.arena.RoundStartEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class TeamSwapListener implements Listener {
    private final TeamSwapService svc;

    public TeamSwapListener(TeamSwapService svc) {
        this.svc = svc;
    }

    @EventHandler
    public void onRoundStart(RoundStartEvent e) {
        // Drop any leftover countdown from a previous match on a reused (non-cloned) arena.
        svc.release(e.getArena());
    }

    @EventHandler
    public void onRoundEnd(RoundEndEvent e) {
        svc.release(e.getArena());
    }

    @EventHandler
    public void onArenaUnload(ArenaUnloadEvent e) {
        svc.release(e.getArena());
    }
}
