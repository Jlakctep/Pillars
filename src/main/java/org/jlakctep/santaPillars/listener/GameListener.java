package org.jlakctep.santaPillars.listener;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jlakctep.santaPillars.SantaPillars;
import org.jlakctep.santaPillars.game.Arena;
import org.jlakctep.santaPillars.util.Msg;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.jlakctep.santaPillars.game.JoinMenu;
import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;

public class GameListener implements Listener {
    private final SantaPillars plugin;
    public GameListener(SantaPillars plugin) { this.plugin = plugin; }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        var a = plugin.arenas().byPlayer(e.getPlayer().getUniqueId());
        if (a != null) a.removePlayer(e.getPlayer().getUniqueId(), true);
        plugin.arenas().leaveGlobalSpectate(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onKick(PlayerKickEvent e) {
        var a = plugin.arenas().byPlayer(e.getPlayer().getUniqueId());
        if (a != null) a.removePlayer(e.getPlayer().getUniqueId(), true);
        plugin.arenas().leaveGlobalSpectate(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Выдать кнопку рандомной арены в лобби при входе
        if (plugin.arenas().byPlayer(e.getPlayer().getUniqueId()) == null) setLobbyRandomItem(e.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Arena a = plugin.arenas().byPlayer(p.getUniqueId());
        if (a == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        // Сообщение о смерти
        Player killer = p.getKiller();
        if (killer != null && killer != p) {
            a.messageAll("Игрок " + p.getName() + " был убит игроком " + killer.getName());
        } else {
            a.messageAll("Игрок " + p.getName() + " погиб");
        }
        e.setDeathMessage(null);
        // Авто-возрождение: нулевая задержка
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            p.spigot().respawn();
            a.onDeath(p);
        });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        var a = plugin.arenas().byPlayer(e.getPlayer().getUniqueId());
        if (a != null) {
            // Режим спектатора и перенос выше точки ожидания
            e.getPlayer().setGameMode(GameMode.SPECTATOR);
            var base = a.waitingSpawn != null ? a.waitingSpawn : plugin.lobbySpawn();
            e.setRespawnLocation(base.clone().add(0, 20, 0));
        } else {
            // В лобби: выдадим кнопку рандомной арены в слот 4
            setLobbyRandomItem(e.getPlayer());
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        var a = plugin.arenas().byPlayer(e.getPlayer().getUniqueId());
        if (a == null) return;
        if (!a.isAlive(e.getPlayer().getUniqueId())) { e.setCancelled(true); return; }
        if (a.isFrozen(e.getPlayer())) { e.setCancelled(true); return; }
        if (!a.canBuildHere(e.getBlock().getLocation())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(ChatColor.RED + "Здесь нельзя строить");
            return;
        }
        a.onBlockPlace(e.getBlock());
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        var a = plugin.arenas().byPlayer(e.getPlayer().getUniqueId());
        if (a == null) return;
        if (!a.isAlive(e.getPlayer().getUniqueId())) { e.setCancelled(true); return; }
        if (a.isFrozen(e.getPlayer())) { e.setCancelled(true); return; }
        boolean allow = plugin.getConfig().getBoolean("build.allow-breaking", false);
        if (!allow) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        var a = plugin.arenas().byPlayer(e.getPlayer().getUniqueId());
        if (a == null) { e.setCancelled(true); return; }
        if (!a.isAlive(e.getPlayer().getUniqueId())) { e.setCancelled(true); return; }
        if (a.isFrozen(e.getPlayer())) { e.setCancelled(true); return; }
        boolean allowDrop = plugin.getConfig().getBoolean("build.allow-item-drop", false);
        if (!allowDrop) e.setCancelled(true);
    }

    @EventHandler
    public void onSwapHand(PlayerSwapHandItemsEvent e) {
        var a = plugin.arenas().byPlayer(e.getPlayer().getUniqueId());
        if (a == null) {
            if (plugin.arenas().isGlobalSpectator(e.getPlayer().getUniqueId())) e.setCancelled(true);
            return;
        }
        if (!a.isAlive(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    // запрет перемещения предметов в лобби обрабатывается внизу в onInventoryClick(InventoryClickEvent)

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        var a = plugin.arenas().byPlayer(e.getPlayer().getUniqueId());
        if (a == null) return;
        // Если наблюдатель: запрет всего, кроме наших инструментов наблюдателя (ПКМ компас/перо/кровать)
        if (!a.isAlive(e.getPlayer().getUniqueId())) {
            if (!(e.getAction().isRightClick() && e.getItem() != null && switch (e.getItem().getType()) { case COMPASS, FEATHER, RED_BED -> true; default -> false; })) {
                e.setCancelled(true);
                return;
            }
        }
        if (a.isFrozen(e.getPlayer())) e.setCancelled(true);
        // Правый клик предметами наблюдателя
        if (e.getAction().isRightClick() && e.getItem() != null && e.getItem().getType() != null) {
            switch (e.getItem().getType()) {
                case COMPASS -> openNavigator(e.getPlayer(), a);
                case FEATHER -> openSpeedMenu(e.getPlayer());
                case RED_BED -> {
                    a.removePlayer(e.getPlayer().getUniqueId(), false);
                    e.getPlayer().sendMessage(Msg.prefix() + Msg.tr("leave"));
                }
                default -> {}
            }
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        var a = plugin.arenas().byPlayer(e.getPlayer().getUniqueId());
        if (a == null) {
            // Лобби: если упал ниже минимума, вернуть на спавн лобби
            if (e.getTo().getY() < e.getTo().getWorld().getMinHeight()) {
                e.getPlayer().teleport(plugin.lobbySpawn());
            }
            return;
        }
        if (a.isFrozen(e.getPlayer())) {
        // Разрешаем вращаться, запрещаем смещение позиции
        if (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getY() != e.getTo().getY() || e.getFrom().getZ() != e.getTo().getZ()) {
            e.setTo(e.getFrom());
            }
            return;
        }
        // instant void kill: если вышел за пределы мира — мгновенная смерть
        if (e.getTo().getY() < e.getTo().getWorld().getMinHeight()) {
            e.getPlayer().setHealth(0.0);
        }
    }

    @EventHandler
    public void onInteractNPC(PlayerInteractAtEntityEvent e) {
        if (!(e.getRightClicked() instanceof LivingEntity le)) return;
        var opt = plugin.npcs().readSizeFrom(le);
        if (opt.isEmpty()) return;
        e.setCancelled(true);
        var size = opt.get();
        JoinMenu.open(plugin, e.getPlayer(), size);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        var a = plugin.arenas().byPlayer(p.getUniqueId());
        // В общем лобби запрещаем перемещать предметы/забирать/складывать,
        // но разрешаем клики в наших GUI (Навигатор, Скорость, JoinMenu)
        if (a == null) {
            String title = e.getView().getTitle();
            boolean ourGui = title.startsWith("Навигатор") || title.startsWith("Скорость") || (e.getView().getTopInventory().getHolder() instanceof JoinMenu.Holder);
            if (!ourGui) {
                e.setCancelled(true);
                return;
            }
        }
        if (a != null && !a.isAlive(p.getUniqueId())) {
            // Наблюдателям запрещено перемещать предметы, кроме кликов в наших меню
            String title = e.getView().getTitle();
            if (!(title.startsWith("Навигатор") || title.startsWith("Скорость"))) {
                e.setCancelled(true);
                return;
            }
        }
        // Навигатор: головы игроков
        if (e.getView().getTitle().startsWith("Навигатор") && e.getClickedInventory() == e.getView().getTopInventory()) {
            e.setCancelled(true);
            var it = e.getCurrentItem();
            if (it == null || it.getItemMeta() == null) return;
            String name = it.getItemMeta().getDisplayName();
            if (name == null || name.isEmpty()) return;
            var target = org.bukkit.Bukkit.getPlayerExact(org.bukkit.ChatColor.stripColor(name));
            if (target != null) p.teleport(target.getLocation());
            p.closeInventory();
            return;
        }
        // Скорость: 9x1 меню
        if (e.getView().getTitle().startsWith("Скорость") && e.getClickedInventory() == e.getView().getTopInventory()) {
            e.setCancelled(true);
            int slot = e.getRawSlot();
            float fly = switch (slot) { case 0 -> 0.1f; case 1 -> 0.2f; case 2 -> 0.3f; case 3 -> 0.5f; case 4 -> 0.7f; case 5 -> 1.0f; default -> p.getFlySpeed(); };
            try { p.setFlySpeed(fly); } catch (IllegalArgumentException ignored) {}
            return;
        }
        if (!(e.getView().getTopInventory().getHolder() instanceof JoinMenu.Holder holder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        if (slot == 49) {
            // Рандомная игра
            java.util.List<org.jlakctep.santaPillars.game.Arena> list = new ArrayList<>();
            for (var ar : plugin.arenas().all()) if (ar.size == holder.size && ar.isJoinable()) list.add(ar);
            var chosen = list.isEmpty() ? null : list.get(ThreadLocalRandom.current().nextInt(list.size()));
            if (chosen != null) {
                chosen.addPlayer(p);
                p.closeInventory();
                p.sendMessage(Msg.prefix() + Msg.tr("join-waiting").replace("%arena%", chosen.id).replace("%size%", holder.size.key()));
            } else {
                p.sendMessage(Msg.prefix() + Msg.tr("no-arenas").replace("%size%", holder.size.key()));
            }
            return;
        }
        ItemStack it = e.getCurrentItem();
        if (it == null) return;
        ItemMeta im = it.getItemMeta();
        if (im == null) return;
        String id = im.getPersistentDataContainer().get(new NamespacedKey(plugin, "arena_id"), PersistentDataType.STRING);
        if (id == null) return;
        var arena = plugin.arenas().get(id);
        if (arena == null || !arena.isJoinable()) { p.sendMessage(Msg.prefix() + Msg.tr("no-arenas").replace("%size%", holder.size.key())); return; }
        arena.addPlayer(p);
        p.closeInventory();
        p.sendMessage(Msg.prefix() + Msg.tr("join-waiting").replace("%arena%", arena.id).replace("%size%", holder.size.key()));
    }

    @EventHandler
    public void onInteractLobbyRandom(PlayerInteractEvent e) {
        if (e.getAction().isRightClick() && e.getItem() != null && e.getItem().getType() == org.bukkit.Material.CLOCK) {
            // Игрок не должен быть в арене
            if (plugin.arenas().byPlayer(e.getPlayer().getUniqueId()) != null) return;
            var any = plugin.arenas().findAnyJoinable();
            if (any != null) {
                any.addPlayer(e.getPlayer());
                e.getPlayer().sendMessage(Msg.prefix() + Msg.tr("join-waiting").replace("%arena%", any.id).replace("%size%", any.size.key()));
            } else {
                e.getPlayer().sendMessage(Msg.prefix() + Msg.tr("no-arenas").replace("%size%", "любой"));
            }
            e.setCancelled(true);
        }
    }

    private void setLobbyRandomItem(Player p) {
        var clock = new org.bukkit.inventory.ItemStack(org.bukkit.Material.CLOCK);
        var im1 = clock.getItemMeta();
        if (im1 != null) {
            im1.displayName(net.kyori.adventure.text.Component.text("Рандомная игра").color(net.kyori.adventure.text.format.NamedTextColor.GOLD));
            clock.setItemMeta(im1);
        }
        p.getInventory().setItem(4, clock);
    }

    private void openNavigator(Player p, Arena a) {
        var inv = org.bukkit.Bukkit.createInventory(null, 54, net.kyori.adventure.text.Component.text("Навигатор"));
        int i = 0;
        for (java.util.UUID u : a.alivePlayers()) {
            var pl = org.bukkit.Bukkit.getPlayer(u);
            if (pl == null) continue;
            var head = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PLAYER_HEAD);
            var meta = (org.bukkit.inventory.meta.SkullMeta) head.getItemMeta();
            if (meta != null) {
                meta.setOwningPlayer(pl);
                meta.displayName(net.kyori.adventure.text.Component.text(pl.getName()).color(net.kyori.adventure.text.format.NamedTextColor.GREEN));
                head.setItemMeta(meta);
            }
            inv.setItem(i++, head);
            if (i >= inv.getSize()) break;
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        Player attackerPlayer = (e.getDamager() instanceof Player) ? (Player) e.getDamager() : null;
        if (attackerPlayer == null && e.getDamager() instanceof org.bukkit.entity.Projectile proj) {
            Object src = proj.getShooter();
            if (src instanceof Player pSrc) attackerPlayer = pSrc;
        }
        Player victimPlayer = (e.getEntity() instanceof Player) ? (Player) e.getEntity() : null;

        if (attackerPlayer != null) {
            var a = plugin.arenas().byPlayer(attackerPlayer.getUniqueId());
            if (a != null && !a.isAlive(attackerPlayer.getUniqueId())) { e.setCancelled(true); return; }
            if (a == null) { e.setCancelled(true); return; } // общий лобби: PvP запрещён
            if (a.getState() != org.jlakctep.santaPillars.game.GameState.INGAME) { e.setCancelled(true); return; } // лобби ожидания: PvP запрещён
        }

        if (victimPlayer != null) {
            var a = plugin.arenas().byPlayer(victimPlayer.getUniqueId());
            if (a != null && !a.isAlive(victimPlayer.getUniqueId())) { e.setCancelled(true); return; }
            if (a == null) { e.setCancelled(true); return; } // общий лобби: PvP запрещён
            if (a.getState() != org.jlakctep.santaPillars.game.GameState.INGAME) { e.setCancelled(true); return; } // лобби ожидания: PvP запрещён
        }

        // Запомнить последний удар (для засчёта килла при падении/добивании)
        if (attackerPlayer != null && victimPlayer != null) {
            var aAtt = plugin.arenas().byPlayer(attackerPlayer.getUniqueId());
            var aVic = plugin.arenas().byPlayer(victimPlayer.getUniqueId());
            if (aAtt != null && aVic != null && aAtt == aVic && aAtt.getState() == org.jlakctep.santaPillars.game.GameState.INGAME) {
                aAtt.recordHit(victimPlayer.getUniqueId(), attackerPlayer.getUniqueId());
            }
        }
    }

    private void openSpeedMenu(Player p) {
        var inv = org.bukkit.Bukkit.createInventory(null, 9, net.kyori.adventure.text.Component.text("Скорость"));
        float[] speeds = new float[] {0.1f, 0.2f, 0.3f, 0.5f, 0.7f, 1.0f};
        for (int i = 0; i < speeds.length; i++) {
            var it = new org.bukkit.inventory.ItemStack(org.bukkit.Material.SUGAR);
            var im = it.getItemMeta();
            if (im != null) { im.displayName(net.kyori.adventure.text.Component.text("x" + String.valueOf((int)(speeds[i]*10))).color(net.kyori.adventure.text.format.NamedTextColor.GOLD)); it.setItemMeta(im); }
            inv.setItem(i, it);
        }
        p.openInventory(inv);
    }
}