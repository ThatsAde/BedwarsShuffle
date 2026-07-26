package dev.combi.bedwarsshuffle.config;

import org.bukkit.configuration.file.FileConfiguration;

public final class TeamSwapConfig {

    public final boolean enabled;
    public final boolean debug;
    public final int minSeconds;
    public final int maxSeconds;
    public final String title;
    public final String subtitle;
    public final String chat;

    public TeamSwapConfig(FileConfiguration cfg) {
        // Defaults to true: this plugin exists solely to shuffle teams, so a fresh
        // install that silently does nothing would just look broken.
        this.enabled = cfg.getBoolean("enabled", true);
        this.debug = cfg.getBoolean("debug", false);

        int min = Math.max(1, cfg.getInt("min_seconds", 60));
        int max = Math.max(min, cfg.getInt("max_seconds", 180));
        this.minSeconds = min;
        this.maxSeconds = max;

        this.title    = cfg.getString("announce.title", "&eTEAM SHUFFLE");
        this.subtitle = cfg.getString("announce.subtitle", "&7Everyone has been swapped!");
        this.chat     = cfg.getString("announce.chat", "&eTeams have been shuffled — check your new team!");
    }
}
