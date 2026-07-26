package dev.combi.bedwarsshuffle;

import dev.combi.bedwarsshuffle.config.TeamSwapConfig;
import dev.combi.bedwarsshuffle.feature.teamswap.TeamSwapListener;
import dev.combi.bedwarsshuffle.feature.teamswap.TeamSwapService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class BedwarsShuffle extends JavaPlugin {

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("MBedwars") == null) {
            getLogger().severe("MBedwars not found. Disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        var cfg = new TeamSwapConfig(getConfig());

        if (!cfg.enabled) {
            getLogger().info("Team shuffling is disabled in config.yml — doing nothing.");
            return;
        }

        var svc = new TeamSwapService(this, cfg);
        svc.startTicking();
        getServer().getPluginManager().registerEvents(new TeamSwapListener(svc), this);

        getLogger().info("Team shuffling enabled: players will be reshuffled onto different teams every "
                + cfg.minSeconds + "-" + cfg.maxSeconds + " seconds.");

        warnIfDeathmatchAlsoRunning();
    }

    /**
     * Team shuffling and the MBedwarsStuff deathmatch are meant for separate game modes.
     * Running both on one server means beds get destroyed mid-shuffle, which is almost
     * certainly a misconfigured server rather than an intentional setup.
     */
    private void warnIfDeathmatchAlsoRunning() {
        Plugin other = Bukkit.getPluginManager().getPlugin("MBedwarsStuff");
        if (other == null || !other.isEnabled()) return;
        if (!other.getConfig().getBoolean("deathmatch.enabled", false)) return;

        getLogger().warning("MBedwarsStuff is installed with deathmatch enabled while team "
                + "shuffling is also active. These are intended for separate game modes — "
                + "consider disabling one of them on this server.");
    }
}
