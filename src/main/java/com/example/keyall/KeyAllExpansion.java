package com.example.keyall;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/** Adds %keyall_time% and %keyall_seconds% for TAB, scoreboards and holograms. */
public final class KeyAllExpansion extends PlaceholderExpansion {

    private final KeyAllPlugin plugin;

    public KeyAllExpansion(KeyAllPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "keyall";
    }

    @Override
    public String getAuthor() {
        return "KeyAll";
    }

    @Override
    public String getVersion() {
        return "1.1.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        return plugin.placeholder(player, params);
    }
}
