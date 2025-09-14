package org.jlakctep.santaPillars.npc;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;
import org.jlakctep.santaPillars.SantaPillars;
import org.jlakctep.santaPillars.game.GameSize;

import java.util.*;

public class NPCManager {
    private final SantaPillars plugin;
    private final Map<String, NPCMeta> npcs = new LinkedHashMap<>();
    private org.bukkit.scheduler.BukkitTask labelUpdater;

    public NPCManager(SantaPillars plugin) { this.plugin = plugin; }

    public void loadAndSpawn(YamlConfiguration cfg) {
        npcs.clear();
        ConfigurationSection sec = cfg.getConfigurationSection("npcs");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            var s = sec.getConfigurationSection(id);
            GameSize size = GameSize.fromKey(s.getString("size", "1x16"));
            World w = Bukkit.getWorld(s.getString("world", "world"));
            if (size == null || w == null) continue;
            double x = s.getDouble("x"), y = s.getDouble("y"), z = s.getDouble("z");
            float yaw = (float)s.getDouble("yaw"), pitch = (float)s.getDouble("pitch");
            String typeStr = s.getString("type", "VILLAGER");
            boolean playerLike = "PLAYER".equalsIgnoreCase(typeStr);
            EntityType type = playerLike ? EntityType.ARMOR_STAND : EntityType.valueOf(typeStr);
            java.util.List<String> lines = s.getStringList("name_lines");
            if (lines == null || lines.isEmpty()) {
                String legacy = s.getString("name", size.key());
                lines = java.util.Collections.singletonList(legacy);
            }
            NPCMeta meta = new NPCMeta(id, size, new Location(w, x, y, z, yaw, pitch), type, lines);
            meta.playerLike = playerLike;
            meta.skin = s.getString("skin", null);
            npcs.put(id, meta);
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::spawnAll, 20L);
    }

    public void save(YamlConfiguration cfg) {
        cfg.set("npcs", null);
        ConfigurationSection sec = cfg.createSection("npcs");
        for (var e : npcs.entrySet()) {
            var id = e.getKey(); var meta = e.getValue();
            ConfigurationSection s = sec.createSection(id);
            s.set("size", meta.size.key());
            s.set("world", meta.loc.getWorld().getName());
            s.set("x", meta.loc.getX()); s.set("y", meta.loc.getY()); s.set("z", meta.loc.getZ());
            s.set("yaw", (double) meta.loc.getYaw()); s.set("pitch", (double) meta.loc.getPitch());
            s.set("type", meta.playerLike ? "PLAYER" : meta.type.name());
            if (meta.skin != null) s.set("skin", meta.skin);
            s.set("name_lines", meta.nameLines);
        }
    }

    public void spawnAll() {
        despawnAll();
        for (NPCMeta meta : npcs.values()) {
            LivingEntity e = (LivingEntity) meta.loc.getWorld().spawnEntity(meta.loc, meta.type);
            e.setAI(false);
            e.setInvulnerable(true);
            e.setSilent(true);
            e.setCollidable(false);
            e.setCanPickupItems(false);
            try { e.setRotation(meta.loc.getYaw(), meta.loc.getPitch()); } catch (Throwable ignored) {}
            if (meta.playerLike && e instanceof org.bukkit.entity.ArmorStand stand) {
                stand.setArms(true);
                stand.setBasePlate(false);
                stand.setGravity(false);
                stand.setSmall(false);
                try {
                    org.bukkit.inventory.ItemStack head = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PLAYER_HEAD);
                    org.bukkit.inventory.meta.SkullMeta sm = (org.bukkit.inventory.meta.SkullMeta) head.getItemMeta();
                    if (sm != null) {
                        if (meta.skin != null && !meta.skin.isEmpty()) {
                            org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:"+meta.skin).getBytes()));
                            sm.setOwningPlayer(off);
                        }
                        sm.displayName(net.kyori.adventure.text.Component.text(" ").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                        head.setItemMeta(sm);
                    }
                    stand.getEquipment().setHelmet(head);
                } catch (Throwable ignored) {}
            }
            // Авто-имя: первая строка — размер (1x16/1x4/1x8), вторая — (игроков)
            int total = plugin.arenas().countPlayersInMode(meta.size);
            java.util.List<String> lines = (meta.nameLines == null || meta.nameLines.isEmpty())
                    ? java.util.Arrays.asList(meta.size.key(), "%online% игроков")
                    : meta.nameLines;
            String line1 = lines.get(0).replace("%online%", String.valueOf(total));
            String line2 = (lines.size() >= 2 ? lines.get(1) : "").replace("%online%", String.valueOf(total));
            // Не используем имя самого NPC, чтобы можно было точно позиционировать строки
            try { e.customName(null); } catch (Throwable ignored) {}
            e.setCustomNameVisible(false);

            // Заголовок: отдельный ArmorStand повыше головы
            double headOffset = e.getHeight() + 0.25;
            org.bukkit.entity.ArmorStand title = (org.bukkit.entity.ArmorStand) meta.loc.getWorld().spawnEntity(
                    meta.loc.clone().add(0, headOffset, 0), org.bukkit.entity.EntityType.ARMOR_STAND);
            title.setVisible(false);
            try { title.setMarker(true); } catch (Throwable ignored) {}
            title.setGravity(false);
            title.setSmall(true);
            title.setCustomNameVisible(true);
            net.kyori.adventure.text.Component header = net.kyori.adventure.text.Component.text(line1)
                    .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW)
                    .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
            try { title.customName(header); } catch (Throwable ex) { title.setCustomName(line1); }
            meta.title = title;

            // Лор: второй ArmorStand сразу под заголовком
            if (!line2.isEmpty()) {
                double yOffset = headOffset - 0.25;
                org.bukkit.entity.ArmorStand label = (org.bukkit.entity.ArmorStand) meta.loc.getWorld().spawnEntity(
                        meta.loc.clone().add(0, yOffset, 0), org.bukkit.entity.EntityType.ARMOR_STAND);
                label.setVisible(false);
                try { label.setMarker(true); } catch (Throwable ignored) {}
                label.setGravity(false);
                label.setSmall(true);
                label.setCustomNameVisible(true);
                net.kyori.adventure.text.Component footer = net.kyori.adventure.text.Component.text(line2)
                        .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
                try { label.customName(footer); } catch (Throwable ex) { label.setCustomName(line2); }
                meta.label = label;
            }
            NamespacedKey keyMode = new NamespacedKey(plugin, "mode");
            e.getPersistentDataContainer().set(keyMode, PersistentDataType.STRING, meta.size.key());
            meta.entity = e;
        }
        // Periodically refresh labels with live online counts per mode
        if (labelUpdater != null) { try { labelUpdater.cancel(); } catch (Throwable ignored) {} }
        labelUpdater = org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (NPCMeta meta : npcs.values()) {
                int total = plugin.arenas().countPlayersInMode(meta.size);
                java.util.List<String> lines = (meta.nameLines == null || meta.nameLines.isEmpty())
                        ? java.util.Arrays.asList(meta.size.key(), "%online% игроков")
                        : meta.nameLines;
                String line1 = lines.get(0).replace("%online%", String.valueOf(total));
                String line2 = (lines.size() >= 2 ? lines.get(1) : "").replace("%online%", String.valueOf(total));
                if (meta.title != null && meta.title.isValid()) {
                    net.kyori.adventure.text.Component header = net.kyori.adventure.text.Component.text(line1)
                            .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW)
                            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
                    try { meta.title.customName(header); } catch (Throwable ex) { meta.title.setCustomName(line1); }
                }
                if (meta.label != null && meta.label.isValid()) {
                    net.kyori.adventure.text.Component footer = net.kyori.adventure.text.Component.text(line2)
                            .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
                    try { meta.label.customName(footer); } catch (Throwable ex) { meta.label.setCustomName(line2); }
                }
            }
        }, 20L, 40L);
    }

    public void despawnAll() {
        if (labelUpdater != null) { try { labelUpdater.cancel(); } catch (Throwable ignored) {} labelUpdater = null; }
        for (NPCMeta meta : npcs.values()) {
            if (meta.entity != null && meta.entity.isValid()) meta.entity.remove();
            meta.entity = null;
            if (meta.title != null && meta.title.isValid()) meta.title.remove();
            meta.title = null;
            if (meta.label != null && meta.label.isValid()) meta.label.remove();
            meta.label = null;
            // Citizens integration removed
        }
    }

    public boolean createNPC(String id, GameSize size, Location loc, EntityType type) {
        if (npcs.containsKey(id)) return false;
        java.util.List<String> lines = java.util.Arrays.asList(size.key(), "%online% игроков");
        boolean playerLike = (type == EntityType.PLAYER);
        EntityType spawnType = playerLike ? EntityType.ARMOR_STAND : type;
        NPCMeta meta = new NPCMeta(id, size, loc, spawnType, lines);
        meta.playerLike = playerLike;
        npcs.put(id, meta);
        spawnAll();
        return true;
    }

    public boolean removeNPC(String id) {
        NPCMeta meta = npcs.remove(id);
        if (meta == null) return false;
        if (meta.entity != null) meta.entity.remove();
        if (meta.title != null) meta.title.remove();
        if (meta.label != null) meta.label.remove();
        // Citizens integration removed
        return true;
    }

    public Optional<GameSize> readSizeFrom(LivingEntity e) {
        NamespacedKey keyMode = new NamespacedKey(plugin, "mode");
        String s = e.getPersistentDataContainer().get(keyMode, PersistentDataType.STRING);
        return Optional.ofNullable(GameSize.fromKey(s == null ? "" : s));
    }

    // Citizens integration removed
    private static class NPCMeta {
        final String id; final GameSize size; final Location loc; final EntityType type; java.util.List<String> nameLines;
        LivingEntity entity; org.bukkit.entity.ArmorStand title; org.bukkit.entity.ArmorStand label; boolean playerLike; String skin;
        NPCMeta(String id, GameSize size, Location loc, EntityType type, java.util.List<String> lines) { this.id = id; this.size = size; this.loc = loc; this.type = type; this.nameLines = (lines == null ? java.util.Collections.emptyList() : lines); }
        NPCMeta(String id, GameSize size, Location loc, EntityType type, String name) { this(id, size, loc, type, java.util.Collections.singletonList(name)); }
    }
}