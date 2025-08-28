package org.jlakctep.santaPillars.util;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jlakctep.santaPillars.SantaPillars;

import java.io.File;

public class Msg {
    private static YamlConfiguration cfg;
    private static String prefix;

    public static void load(SantaPillars plugin) {
        File f = new File(plugin.getDataFolder(), "messages.yml");
        cfg = YamlConfiguration.loadConfiguration(f);
        prefix = color(cfg.getString("prefix", ""));
    }

    public static String tr(String key) { return color(cfg.getString(key, key)); }
    public static String prefix() { return prefix; }
    public static void send(org.bukkit.command.CommandSender s, String key) { s.sendMessage(tr(key)); }
    public static java.util.List<String> trList(String key) {
        java.util.List<String> raw = cfg.getStringList(key);
        if (raw == null) return java.util.Collections.emptyList();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : raw) out.add(color(s));
        return out;
    }

    private static String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }
}