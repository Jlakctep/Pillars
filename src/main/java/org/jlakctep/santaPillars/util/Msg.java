package org.jlakctep.santaPillars.util;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jlakctep.santaPillars.SantaPillars;

import java.io.File;

public class Msg {
    private static YamlConfiguration cfg;
    private static String prefix;
    private static org.jlakctep.santaPillars.SantaPillars plugin;

    public static void load(SantaPillars pl) {
        plugin = pl;
        File f = new File(pl.getDataFolder(), "messages.yml");
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

    private static String color(String s) {
        String txt = s == null ? "" : s;
        txt = normalizeSymbols(txt);
        return ChatColor.translateAlternateColorCodes('&', txt);
    }

    // Удаляем вариационные селекторы/невидимые связки, которые делают эмодзи и ломают рендер в чате MC
    private static String normalizeSymbols(String s) {
        try {
            // FE0F/FE0E — variation selectors; 200D — zero-width joiner; 2060..206F — invisibles
            return s
                    .replace("\uFE0F", "")
                    .replace("\uFE0E", "")
                    .replace("\u200D", "")
                    .replace("\u2060", "")
                    .replace("\u2061", "")
                    .replace("\u2062", "")
                    .replace("\u2063", "")
                    .replace("\u2064", "")
                    .replace("\u2066", "")
                    .replace("\u2067", "")
                    .replace("\u2068", "")
                    .replace("\u2069", "");
        } catch (Throwable ignored) { return s; }
    }

    // Economy placeholders removed on request
}