package org.jlakctep.santaPillars.item;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.jlakctep.santaPillars.SantaPillars;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class ItemPool {
    private final SantaPillars plugin;
    private final List<Material> blockPool = new ArrayList<>();
    private final List<Material> itemPool = new ArrayList<>();
    private final Set<Material> blacklist = new HashSet<>();
    private String poolMode;
    private double preferBlocks;
    private int stackMin, stackMax;

    public ItemPool(SantaPillars plugin) { this.plugin = plugin; reload(); }

    public void reload() {
        blockPool.clear(); itemPool.clear(); blacklist.clear();
        FileConfiguration c = plugin.getConfig();
        poolMode = c.getString("items.pool", "BLOCKS").toUpperCase(Locale.ROOT);
        preferBlocks = c.getDouble("items.prefer-blocks-weight", 0.8);
        stackMin = Math.max(1, c.getInt("items.stack-min", 1));
        stackMax = Math.max(stackMin, c.getInt("items.stack-max", stackMin));

        for (String s : c.getStringList("items.blacklist")) {
            try { blacklist.add(Material.valueOf(s.toUpperCase(Locale.ROOT))); } catch (Exception ignored) {}
        }
        for (Material m : Material.values()) {
            if (m.isLegacy()) continue;
            if (blacklist.contains(m)) continue;
            if (m.isAir()) continue;
            if (m.isBlock()) {
                if (m.isItem()) blockPool.add(m);
            } else {
                if (m.isItem()) itemPool.add(m);
            }
        }
    }

    public Material randomMaterial() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        switch (poolMode) {
            case "ALL" -> {
                return pick(r, blockPool, itemPool, preferBlocks);
            }
            case "CUSTOM" -> {
                // пока как ALL; позже вынесем кастомный список в конфиг
                return pick(r, blockPool, itemPool, preferBlocks);
            }
            default -> {
                // сюда попадёт и "BLOCKS", и любые другие значения
                return blockPool.isEmpty() ? Material.STONE : blockPool.get(r.nextInt(blockPool.size()));
            }
        }
    }

    private Material pick(Random r, List<Material> blocks, List<Material> items, double wBlocks) {
        if (blocks.isEmpty() && items.isEmpty()) return Material.STONE;
        if (blocks.isEmpty()) return items.get(r.nextInt(items.size()));
        if (items.isEmpty()) return blocks.get(r.nextInt(blocks.size()));
        boolean pb = r.nextDouble() < wBlocks;
        return (pb ? blocks : items).get(r.nextInt(pb ? blocks.size() : items.size()));
    }

    public int randomAmount() {
        if (stackMin == stackMax) return stackMin;
        return ThreadLocalRandom.current().nextInt(stackMin, stackMax + 1);
    }
}