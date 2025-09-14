package org.jlakctep.santaPillars.game;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jlakctep.santaPillars.SantaPillars;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class JoinMenu {

    public static final class Holder implements InventoryHolder {
        public final SantaPillars plugin;
        public final GameSize size;
        public Holder(SantaPillars plugin, GameSize size) { this.plugin = plugin; this.size = size; }
        @Override public Inventory getInventory() { return null; }
    }

    private static final int INVENTORY_SIZE = 54;

    public static void open(SantaPillars plugin, Player player, GameSize size) {
        Inventory inv = Bukkit.createInventory(new Holder(plugin, size), INVENTORY_SIZE, Component.text("Арены " + size.key()).decoration(TextDecoration.ITALIC, false));

        // Кнопка рандома в 49
        ItemStack clock = new ItemStack(Material.CLOCK);
        ItemMeta cm = clock.getItemMeta();
        if (cm != null) {
            cm.displayName(Component.text("Рандомная игра").color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Нажмите чтобы присоединиться").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            cm.lore(lore);
            clock.setItemMeta(cm);
        }
        inv.setItem(49, clock);

        // Слоты под арены: по бокам и сверху пустота, правый край пустой, 49 занят часами
        Set<Integer> blocked = new HashSet<>();
        // верхняя строка
        for (int i = 0; i <= 8; i++) blocked.add(i);
        // левая колонка
        blocked.add(9); blocked.add(18); blocked.add(27); blocked.add(36); blocked.add(45);
        // правая колонка
        blocked.add(8); blocked.add(17); blocked.add(26); blocked.add(35); blocked.add(44); blocked.add(53);
        // часы
        blocked.add(49);
        List<Integer> availableSlots = new ArrayList<>();
        for (int row = 1; row < 6; row++) {
            int base = row * 9;
            for (int col = 1; col <= 7; col++) {
                int slot = base + col;
                if (!blocked.contains(slot)) availableSlots.add(slot);
            }
        }

        NamespacedKey keyId = new NamespacedKey(plugin, "arena_id");
        int idx = 0;
        for (Arena a : plugin.arenas().all()) {
            if (a.size != size) continue;
            if (!a.isJoinable()) continue;
            if (idx >= availableSlots.size()) break;
            ItemStack it = new ItemStack(Material.SLIME_BALL);
            ItemMeta im = it.getItemMeta();
            if (im != null) {
                im.displayName(Component.text(a.id).color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text("Ожидание игроков [" + a.playersCount() + "/" + a.size.max() + "]")
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                im.lore(lore);
                im.getPersistentDataContainer().set(keyId, PersistentDataType.STRING, a.id);
                it.setItemMeta(im);
            }
            inv.setItem(availableSlots.get(idx), it);
            idx++;
        }

        player.openInventory(inv);
    }

}


