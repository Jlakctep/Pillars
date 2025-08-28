package org.jlakctep.santaPillars.game;

import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;
import org.jlakctep.santaPillars.SantaPillars;
import org.jlakctep.santaPillars.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Arena {

    private final SantaPillars plugin;
    public final String id;
    public GameSize size;
    public final World world;

    // Редактор/арена
    public Region bounds; // пока не используется, оставил на будущее
    public Location waitingSpawn; // точка ожидания
    private final Map<Integer, Location> teamSpawns = new HashMap<>();

    private final Set<UUID> players = ConcurrentHashMap.newKeySet();
    private final Set<UUID> alive = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> placedBlocks = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> kills = new HashMap<>();
    // Последний нанесший урон: жертва -> (атакующий, время удара)
    private final Map<UUID, AttackInfo> lastHit = new HashMap<>();

    private GameState state = GameState.WAITING;
    private int countdownTicks = 0;
    private int freezeTicks = 0;
    private int ticksToNextItem = 0;
    private int hardLimitTicks = 0;
    private long startTimeMs = 0L;
    private int lastTimeLeftAnnounced = -1;

    private BukkitTask loopTask;
    private BossBar bossBar;
    private final Map<UUID, Scoreboard> scoreboards = new HashMap<>();

    public Arena(SantaPillars plugin, String id, GameSize size, World world) {
        this.plugin = plugin; this.id = id; this.size = size; this.world = world;
        if (plugin.getConfig().getBoolean("ui.bossbar", true)) {
            bossBar = Bukkit.createBossBar("Pillars", BarColor.YELLOW, BarStyle.SEGMENTED_12);
            bossBar.setVisible(false);
        }
    }

    public GameState getState() { return state; }
    public boolean isAlive(java.util.UUID u) { return alive.contains(u); }
    public java.util.Collection<java.util.UUID> alivePlayers() { return new java.util.ArrayList<>(alive); }
    public int playersCount() { return players.size(); }

    public boolean isJoinable() {
        if (state != GameState.WAITING && state != GameState.STARTING) return false;
        return players.size() < size.max();
    }

    public void addPlayer(Player p) {
        if (players.contains(p.getUniqueId())) { Msg.send(p, "already-in"); return; }
        players.add(p.getUniqueId());
        alive.add(p.getUniqueId());
        kills.putIfAbsent(p.getUniqueId(), 0);
        plugin.arenas().bindPlayer(p.getUniqueId(), id);

        p.getInventory().clear();
        p.setGameMode(GameMode.ADVENTURE);
        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.setFireTicks(0);
        p.teleport(waitingSpawn != null ? waitingSpawn : plugin.lobbySpawn());

        // Выдать кнопку выхода (кровать) только если есть отдельная точка ожидания арены
        if (waitingSpawn != null) {
            try {
                org.bukkit.inventory.ItemStack bed = new org.bukkit.inventory.ItemStack(Material.RED_BED);
                var im2 = bed.getItemMeta();
                if (im2 != null) {
                    im2.displayName(net.kyori.adventure.text.Component.text("§aПокинуть арену"));
                    bed.setItemMeta(im2);
                }
                p.getInventory().setItem(8, bed);
            } catch (Exception ignored) {}
        }

        giveScoreboard(p);
        updateScoreboards();

        int minPlayers = plugin.getConfig().getInt("modes."+size.key()+".min-players", 2);
        if (players.size() >= minPlayers && state == GameState.WAITING) {
            startCountdown();
        }
    }

    public void removePlayer(UUID uuid, boolean eliminate) {
        Player p = Bukkit.getPlayer(uuid);
        players.remove(uuid);
        alive.remove(uuid);
        kills.remove(uuid);
        lastHit.remove(uuid);
        plugin.arenas().unbindPlayer(uuid);

        if (p != null) {
            if (eliminate) Msg.send(p, "eliminated");
            p.getInventory().clear();
            p.setGameMode(GameMode.ADVENTURE);
            p.teleport(plugin.lobbySpawn());
            try {
                org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(Material.CLOCK);
                org.bukkit.inventory.meta.ItemMeta im = it.getItemMeta();
                if (im != null) {
                    im.displayName(net.kyori.adventure.text.Component.text("Рандомная игра").color(net.kyori.adventure.text.format.NamedTextColor.GOLD));
                    it.setItemMeta(im);
                }
                p.getInventory().setItem(4, it);
            } catch (Exception ignored) {}
            clearScoreboard(p);
        }
        if (state == GameState.INGAME || state == GameState.STARTING) checkWin();
        if (players.isEmpty() && state != GameState.WAITING) end(null);
        updateScoreboards();
    }

    private void startCountdown() {
        if (state != GameState.WAITING) return;
        state = GameState.STARTING;
        int sec = plugin.getConfig().getInt("timings.prestart-seconds", 30);
        countdownTicks = Math.max(1, sec) * 20;
        // Сообщаем один раз о старте отсчёта и начальном времени
        broadcast(Msg.tr("countdown-begin"));
        broadcast(Msg.tr("starting-in").replace("%sec%", String.valueOf(sec)));
        if (loopTask == null) loopTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void forceStart() {
        if (state == GameState.INGAME) return;
        if (loopTask == null) loopTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        countdownTicks = 1; // старт ближайшим тиком
        if (state == GameState.WAITING) state = GameState.STARTING;
    }

    private void startGame() {
        if (state == GameState.INGAME) return;
        state = GameState.INGAME;
        startTimeMs = System.currentTimeMillis();
        lastTimeLeftAnnounced = -1;

        // freeze
        freezeTicks = Math.max(0, plugin.getConfig().getInt("timings.freeze-seconds", 5)) * 20;
        ticksToNextItem = Math.max(1, plugin.getConfig().getInt("timings.item-period-ticks", 40));
        int hardSec = Math.max(0, plugin.getConfig().getInt("timings.hard-time-limit-seconds", 0));
        hardLimitTicks = hardSec > 0 ? hardSec * 20 : 0;

        assignSpawns();
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            p.getInventory().clear();
            p.setGameMode(GameMode.SURVIVAL);
        }

        if (bossBar != null) {
            bossBar.setVisible(true);
            for (UUID u : players) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) bossBar.addPlayer(p);
            }
        }
        updateScoreboards();
    }

    private void tick() {
        if (state == GameState.STARTING) {
            int minPlayers = plugin.getConfig().getInt("modes."+size.key()+".min-players", 2);
            if (players.size() < minPlayers) {
                state = GameState.WAITING;
                broadcast(Msg.tr("not-enough-players"));
                return;
            }
            if (countdownTicks % 20 == 0) {
                int s = Math.max(0, countdownTicks / 20);
                if (s >= 10 && s % 10 == 0) {
                    broadcast(Msg.tr("starting-in").replace("%sec%", String.valueOf(s)));
                } else if (s == 5) {
                    broadcast(Msg.tr("starting-in").replace("%sec%", "5"));
                } else if (s > 0 && s <= 4) {
                    broadcast(String.valueOf(s));
                }
            }
            if (--countdownTicks <= 0) startGame();

        } else if (state == GameState.INGAME) {
            if (freezeTicks > 0) {
                if (freezeTicks % 20 == 0) {
                    int s = Math.max(0, freezeTicks / 20);
                    for (UUID u : players) {
                        Player p = Bukkit.getPlayer(u);
                        if (p != null) p.sendActionBar(Msg.tr("freeze").replace("%sec%", String.valueOf(s)));
                    }
                }
                if (--freezeTicks == 0) {
                    // показать титр "Игра началась"
                    for (UUID u : players) {
                        Player p = Bukkit.getPlayer(u);
                        if (p != null) p.sendTitle(ChatColor.GREEN + Msg.tr("started-title"), "", 10, 30, 10);
                    }
                    broadcast(Msg.tr("started"));
                }
            }
            // Объявления оставшегося времени: после фриза, раз в секунду
            if (hardLimitTicks > 0 && freezeTicks <= 0 && hardLimitTicks % 20 == 0) {
                int hardLimitSecCfg = Math.max(0, plugin.getConfig().getInt("timings.hard-time-limit-seconds", 0));
                if (hardLimitSecCfg > 0) {
                    int rawElapsed = (int)((System.currentTimeMillis() - startTimeMs) / 1000);
                    int freezeTotalSecCfg = Math.max(0, plugin.getConfig().getInt("timings.freeze-seconds", 5));
                    int effectiveElapsed = rawElapsed < freezeTotalSecCfg ? 0 : (rawElapsed - freezeTotalSecCfg);
                    int timeLeft = Math.max(hardLimitSecCfg - Math.max(0, effectiveElapsed), 0);
                    if (timeLeft != lastTimeLeftAnnounced) {
                        if (timeLeft >= 60 && timeLeft % 60 == 0) {
                            int mins = timeLeft / 60;
                            // Не объявляем стартовое "полное" значение, оно уже пройдено после фриза
                            broadcast("&eОсталось &6" + mins + " &eмин." );
                            lastTimeLeftAnnounced = timeLeft;
                        } else if (timeLeft == 30) {
                            broadcast("&eОсталось &630 &eсекунд");
                            lastTimeLeftAnnounced = timeLeft;
                        } else if (timeLeft == 15) {
                            broadcast("&eОсталось &615 &eсекунд");
                            lastTimeLeftAnnounced = timeLeft;
                        } else if (timeLeft <= 5 && timeLeft >= 1) {
                            if (timeLeft == 5) broadcast("&65 &eсекунд...");
                            else broadcast("&6" + timeLeft + "&e...");
                            lastTimeLeftAnnounced = timeLeft;
                        }
                    }
                }
            }
            if (hardLimitTicks > 0 && --hardLimitTicks <= 0) {
                // Лимит времени: определяем победителя по киллам
                java.util.List<UUID> order = sortPlayersByKills();
                UUID best = order.isEmpty() ? null : order.get(0);
                Player winner = best != null ? Bukkit.getPlayer(best) : null;
                for (UUID u : players) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null) p.sendMessage(Msg.tr("time-limit"));
                }
                end(winner);
                return;
            }

            int period = Math.max(1, plugin.getConfig().getInt("timings.item-period-ticks", 40));
            if (bossBar != null) {
                if (freezeTicks > 0) {
                    bossBar.setTitle(Msg.tr("bossbar-freeze").replace("%sec%", String.format("%.1f", freezeTicks / 20.0)));
                    bossBar.setProgress(Math.min(1.0, Math.max(0.0, 1.0 - (double)freezeTicks / (plugin.getConfig().getInt("timings.freeze-seconds", 5) * 20.0))));
                } else {
                    double progress = 1.0 - (double)ticksToNextItem / (double)period;
                    bossBar.setTitle(Msg.tr("bossbar-next-item").replace("%sec%", String.format("%.1f", Math.max(0, ticksToNextItem / 20.0))));
                    bossBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
                }
            }
            if (freezeTicks <= 0) {
                if (--ticksToNextItem <= 0) {
                    giveItems();
                    ticksToNextItem = period;
                }
            }
            checkWin();
        }
        updateScoreboards();
    }

    private void giveItems() {
        boolean allowDrop = plugin.getConfig().getBoolean("build.allow-item-drop", false);
        for (UUID u : new ArrayList<>(alive)) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            var mat = plugin.items().randomMaterial();
            int amt = plugin.items().randomAmount();
            ItemStack it = new ItemStack(mat, amt);
            var left = p.getInventory().addItem(it);
            if (!left.isEmpty() && allowDrop) {
                left.values().forEach(v -> p.getWorld().dropItemNaturally(p.getLocation(), v));
            }
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.2f);
        }
    }

    public boolean isFrozen(Player p) {
        return state == GameState.INGAME && freezeTicks > 0 && alive.contains(p.getUniqueId());
    }

    public void onBlockPlace(org.bukkit.block.Block b) { placedBlocks.add(new BlockPos(b.getX(), b.getY(), b.getZ())); }

    public boolean canBuildHere(Location loc) {
        var c = plugin.getConfig();
        if (!c.getBoolean("build.allow-placing", true)) return false;
        if (c.getBoolean("build.restrict-to-bounds", true) && bounds != null) {
            if (!bounds.contains(loc)) return false;
        }
        int y = loc.getBlockY();
        return !(y < c.getInt("build.y-min", -64) || y > c.getInt("build.y-max", 320));
    }

    public void onDeath(Player p) {
        if (!alive.contains(p.getUniqueId())) return;
        alive.remove(p.getUniqueId());
        UUID credited = null;
        Player direct = p.getKiller();
        if (direct != null && !direct.getUniqueId().equals(p.getUniqueId())) {
            credited = direct.getUniqueId();
        } else {
            AttackInfo info = lastHit.get(p.getUniqueId());
            if (info != null && (System.currentTimeMillis() - info.time) <= 10000L && !info.attacker.equals(p.getUniqueId())) {
                credited = info.attacker;
            }
        }
        if (credited != null) {
            kills.put(credited, kills.getOrDefault(credited, 0) + 1);
            Player kp = Bukkit.getPlayer(credited);
            if (kp != null) rewardKill(kp);
        }
        lastHit.remove(p.getUniqueId());
        p.getInventory().clear();
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport((waitingSpawn != null ? waitingSpawn : plugin.lobbySpawn()).clone().add(0, 20, 0));
        showLoseTitle(p);
        Msg.send(p, "eliminated");
        p.sendMessage(Msg.tr("leave-hint"));
        checkWin();
        updateScoreboards();
    }

    // Публичная рассылка сообщения всем игрокам арены (без префикса цветов)
    public void messageAll(String plain) {
        for (UUID u : players) {
            Player rp = Bukkit.getPlayer(u);
            if (rp != null) rp.sendMessage(Msg.prefix() + plain);
        }
    }

    public void giveSpectatorItems(Player p) {
        try {
            p.getInventory().clear();
            org.bukkit.inventory.ItemStack nav = new org.bukkit.inventory.ItemStack(Material.COMPASS);
            org.bukkit.inventory.meta.ItemMeta m1 = nav.getItemMeta();
            if (m1 != null) {
                m1.displayName(net.kyori.adventure.text.Component.text("Навигатор").color(net.kyori.adventure.text.format.NamedTextColor.GOLD));
                nav.setItemMeta(m1);
            }
            org.bukkit.inventory.ItemStack speed = new org.bukkit.inventory.ItemStack(Material.FEATHER);
            org.bukkit.inventory.meta.ItemMeta m2 = speed.getItemMeta();
            if (m2 != null) {
                m2.displayName(net.kyori.adventure.text.Component.text("Скорость").color(net.kyori.adventure.text.format.NamedTextColor.GOLD));
                speed.setItemMeta(m2);
            }
            org.bukkit.inventory.ItemStack leave = new org.bukkit.inventory.ItemStack(Material.RED_BED);
            org.bukkit.inventory.meta.ItemMeta m3 = leave.getItemMeta();
            if (m3 != null) {
                m3.displayName(net.kyori.adventure.text.Component.text("Покинуть игру").color(net.kyori.adventure.text.format.NamedTextColor.RED));
                leave.setItemMeta(m3);
            }
            p.getInventory().setItem(0, nav);
            p.getInventory().setItem(4, speed);
            p.getInventory().setItem(8, leave);
        } catch (Exception ignored) {}
    }

    private void checkWin() {
        if (!plugin.getConfig().getBoolean("win.end-when-one-left", true)) return;
        if (state != GameState.INGAME) return;
        if (alive.size() <= 1) {
            Player winner = null;
            for (UUID u : alive) { winner = Bukkit.getPlayer(u); break; }
            end(winner);
        }
    }

    private void end(Player winner) {
        state = GameState.ENDING;
        if (bossBar != null) { bossBar.setVisible(false); bossBar.removeAll(); }
        if (winner != null) broadcast(Msg.tr("win").replace("%player%", winner.getName()));
        else broadcast(ChatColor.GOLD + "Ничья!");
        announceTopByKills(winner);
        try { handlePayoutsOnEnd(winner); } catch (Exception ignored) {}

        // Показать титры участникам (наблюдателям ничего не показываем)
        if (winner != null) {
            showVictoryTitle(winner);
            for (UUID u : new ArrayList<>(alive)) {
                if (u.equals(winner.getUniqueId())) continue;
                Player p = Bukkit.getPlayer(u);
                if (p != null) showLoseTitle(p);
            }
        }

        // Фаза наблюдения 5 сек: оставляем на карте с невидимостью и полётом
        for (UUID u : new ArrayList<>(players)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                p.getInventory().clear();
                p.setGameMode(GameMode.ADVENTURE);
                p.setAllowFlight(true);
                p.setFlying(true);
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY, 5 * 20, 0, false, false, false));
                clearScoreboard(p);
            }
        }

        // Через 5 секунд выкидываем всех игроков и внешних наблюдателей в лобби и чистим состояние
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (UUID u : new ArrayList<>(players)) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) {
                    p.removePotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
                    p.setAllowFlight(false);
                    p.setFlying(false);
                    p.teleport(plugin.lobbySpawn());
                    try {
                        org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(Material.CLOCK);
                        org.bukkit.inventory.meta.ItemMeta im = it.getItemMeta();
                        if (im != null) {
                            im.displayName(net.kyori.adventure.text.Component.text("Рандомная игра").color(net.kyori.adventure.text.format.NamedTextColor.GOLD));
                            it.setItemMeta(im);
                        }
                        p.getInventory().setItem(4, it);
                    } catch (Exception ignored) {}
                }
                plugin.arenas().unbindPlayer(u);
            }
            // Внешние наблюдатели этой арены
            for (UUID sp : plugin.arenas().spectatorsOfArena(id)) {
                Player p = Bukkit.getPlayer(sp);
                if (p != null) {
                    p.getInventory().clear();
                    p.removePotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
                    p.setGameMode(GameMode.SURVIVAL);
                    p.setAllowFlight(false);
                    p.setFlying(false);
                    p.teleport(plugin.lobbySpawn());
                    try {
                        org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(Material.CLOCK);
                        org.bukkit.inventory.meta.ItemMeta im = it.getItemMeta();
                        if (im != null) {
                            im.displayName(net.kyori.adventure.text.Component.text("Рандомная игра").color(net.kyori.adventure.text.format.NamedTextColor.GOLD));
                            it.setItemMeta(im);
                        }
                        p.getInventory().setItem(4, it);
                    } catch (Exception ignored) {}
                }
                plugin.arenas().stopSpectating(sp);
            }
            players.clear(); alive.clear();
            kills.clear();
            lastHit.clear();
            rollback();
            state = GameState.WAITING;
            if (loopTask != null && !hasAnyPlayers()) { loopTask.cancel(); loopTask = null; }
        }, 5 * 20L);
    }

    private void showVictoryTitle(Player p) {
        try {
            Title.Times times = Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofSeconds(3), java.time.Duration.ofMillis(500));
            Component title = Component.text("VICTORY").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD);
            p.showTitle(Title.title(title, Component.empty(), times));
        } catch (Throwable ignored) {
            p.sendTitle(ChatColor.GOLD + "VICTORY", "", 10, 60, 10);
        }
    }

    private void showLoseTitle(Player p) {
        try {
            Title.Times times = Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofSeconds(3), java.time.Duration.ofMillis(500));
            Component title = Component.text("LOSE").color(NamedTextColor.RED).decorate(TextDecoration.BOLD);
            p.showTitle(Title.title(title, Component.empty(), times));
        } catch (Throwable ignored) {
            p.sendTitle(ChatColor.RED + "LOSE", "", 10, 60, 10);
        }
    }

    private boolean hasAnyPlayers() { return !players.isEmpty(); }

    private void rollback() {
        for (BlockPos bp : placedBlocks) {
            var b = world.getBlockAt(bp.x, bp.y, bp.z);
            b.setType(Material.AIR, false);
        }
        placedBlocks.clear();
    }

    private void broadcast(String msg) {
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(Msg.prefix() + ChatColor.translateAlternateColorCodes('&', msg));
        }
    }

    private java.util.List<UUID> sortPlayersByKills() {
        java.util.ArrayList<UUID> order = new java.util.ArrayList<>(players);
        order.sort((a, b) -> Integer.compare(kills.getOrDefault(b, 0), kills.getOrDefault(a, 0)));
        return order;
    }

    private void announceTopByKills(Player winner) {
        java.util.List<UUID> order = sortPlayersByKills();
        int topN = Math.min(3, order.size());
        if (topN == 0) return;
        broadcast(ChatColor.GOLD + "Топ по убийствам:");
        for (int i = 0; i < topN; i++) {
            UUID u = order.get(i);
            Player p = Bukkit.getPlayer(u);
            String name = p != null ? p.getName() : ("Игрок-" + u.toString().substring(0, 4));
            int k = kills.getOrDefault(u, 0);
            broadcast(ChatColor.YELLOW + String.valueOf(i + 1) + ". " + ChatColor.GREEN + name + ChatColor.GRAY + " (" + ChatColor.AQUA + k + ChatColor.GRAY + ")");
        }
    }

    private void rewardKill(Player killer) {
        double amt = plugin.getConfig().getDouble("economy.reward.kill", 0.0);
        if (amt <= 0.0) return;
        plugin.depositMoney(killer, amt);
    }

    private void handlePayoutsOnEnd(Player winner) {
        // Порядок по киллам для топа
        java.util.List<UUID> order = sortPlayersByKills();
        // Переменные вознаграждений
        double winAmt = plugin.getConfig().getDouble("economy.reward.win", 0.0);
        double loseAmt = plugin.getConfig().getDouble("economy.reward.lose", 0.0);
        double p1 = plugin.getConfig().getDouble("economy.reward.place1", 0.0);
        double p2 = plugin.getConfig().getDouble("economy.reward.place2", 0.0);
        double p3 = plugin.getConfig().getDouble("economy.reward.place3", 0.0);

        // Выдача победителю
        if (winner != null && winAmt > 0.0) plugin.depositMoney(winner, winAmt);
        // Топ-3 по киллам
        for (int i = 0; i < Math.min(3, order.size()); i++) {
            Player p = Bukkit.getPlayer(order.get(i));
            if (p == null) continue;
            double amt = switch (i) { case 0 -> p1; case 1 -> p2; case 2 -> p3; default -> 0.0; };
            if (amt > 0.0) plugin.depositMoney(p, amt);
        }
        // Остальным — за участие/проигрыш
        if (loseAmt > 0.0) {
            for (UUID u : players) {
                Player p = Bukkit.getPlayer(u);
                if (p == null || (winner != null && p.getUniqueId().equals(winner.getUniqueId()))) continue;
                plugin.depositMoney(p, loseAmt);
            }
        }
    }

    private void assignSpawns() {
        // Шафлим игроков и телепортируем по team1..teamN
        int i = 1;
        ArrayList<UUID> order = new ArrayList<>(players);
        Collections.shuffle(order);
        for (UUID u : order) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            Location tl = teamSpawns.get(i);
            if (tl == null) tl = waitingSpawn != null ? waitingSpawn : plugin.lobbySpawn();
            p.teleport(tl);
            i++;
            if (i > size.max()) break;
        }
    }

    private void giveScoreboard(Player p) {
        if (!plugin.getConfig().getBoolean("ui.scoreboard", true)) return;
        ScoreboardManager sm = Bukkit.getScoreboardManager();
        if (sm == null) return;
        Scoreboard sb = sm.getNewScoreboard();
        String title = Msg.tr((state == GameState.INGAME || state == GameState.ENDING) ? "scoreboard.ingame.title" : "scoreboard.waiting.title");
        if (title == null || title.isEmpty()) title = ChatColor.GOLD + "Pillars";
        Objective obj = sb.registerNewObjective("pillars", "dummy", title);
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        scoreboards.put(p.getUniqueId(), sb);
        p.setScoreboard(sb);
    }

    private void clearScoreboard(Player p) {
        ScoreboardManager sm = Bukkit.getScoreboardManager();
        if (sm != null) p.setScoreboard(sm.getMainScoreboard());
        scoreboards.remove(p.getUniqueId());
    }

    private void updateScoreboards() {
        if (!plugin.getConfig().getBoolean("ui.scoreboard", true)) return;
        int aliveCount = (state == GameState.INGAME) ? alive.size() : players.size();
        int total = size.max();
        int rawElapsed = state == GameState.INGAME ? (int)((System.currentTimeMillis() - startTimeMs)/1000) : 0;
        int freezeTotalSecCfg = Math.max(0, plugin.getConfig().getInt("timings.freeze-seconds", 5));
        int effectiveElapsed = 0;
        if (state == GameState.INGAME) {
            // Во время фриза время в скорборде стоит
            if (freezeTicks > 0 || rawElapsed < freezeTotalSecCfg) effectiveElapsed = 0;
            else effectiveElapsed = Math.max(0, rawElapsed - freezeTotalSecCfg);
        }
        int hardLimitSecCfg = Math.max(0, plugin.getConfig().getInt("timings.hard-time-limit-seconds", 0));
        int timeDisplaySec = hardLimitSecCfg > 0 ? Math.max(hardLimitSecCfg - effectiveElapsed, 0) : effectiveElapsed;
        int freezeSec = state == GameState.INGAME && freezeTicks > 0 ? (freezeTicks/20) : 0;
        int countdownSec = state == GameState.STARTING ? Math.max(0, countdownTicks / 20) : 0;
        java.util.List<String> lines = Msg.trList((state == GameState.INGAME || state == GameState.ENDING) ? "scoreboard.ingame.lines" : "scoreboard.waiting.lines");
        if (lines == null || lines.isEmpty()) {
            lines = java.util.Arrays.asList(
                    ChatColor.GRAY + "Arena: " + ChatColor.YELLOW + "%arena%",
                    ChatColor.GRAY + "Mode: " + ChatColor.YELLOW + "%mode%",
                    ChatColor.GRAY + "State: " + ChatColor.YELLOW + "%state%",
                    ChatColor.GRAY + "Players: " + ChatColor.YELLOW + "%current%/%max%",
                    ChatColor.DARK_GRAY + " ",
                    ChatColor.GOLD + "Pillars"
            );
        }
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            Scoreboard sb = scoreboards.get(u);
            if (sb == null) continue;
            Objective obj = sb.getObjective(DisplaySlot.SIDEBAR);
            if (obj == null) continue;
            obj.setDisplayName(Msg.tr((state == GameState.INGAME || state == GameState.ENDING) ? "scoreboard.ingame.title" : "scoreboard.waiting.title"));
            for (String entry : new ArrayList<>(sb.getEntries())) sb.resetScores(entry);
            int score = lines.size();
            java.util.HashSet<String> used = new java.util.HashSet<>();
            for (String raw : lines) {
                String line = raw
                        .replace("%arena%", id)
                        .replace("%mode%", size.key())
                        .replace("%state%", String.valueOf(state))
                        .replace("%current%", String.valueOf(aliveCount))
                        .replace("%max%", String.valueOf(total))
                        .replace("%elapsed%", String.valueOf(timeDisplaySec))
                        .replace("%timeleft%", String.valueOf(timeDisplaySec))
                        .replace("%freeze%", String.valueOf(freezeSec))
                        .replace("%countdown%", String.valueOf(countdownSec))
                        .replace("%kills%", String.valueOf(kills.getOrDefault(u, 0)));
                while (used.contains(line)) line = line + ChatColor.RESET;
                used.add(line);
                addLine(obj, line, score--);
            }
        }
    }

    private void addLine(Objective obj, String text, int score) { obj.getScore(text).setScore(score); }

    public void setTeamSpawn(int idx, Location loc) { teamSpawns.put(idx, loc); }

    public void save(ConfigurationSection sec) {
        sec.set("size", size.key());
        sec.set("world", world.getName());
        if (waitingSpawn != null) {
            sec.set("waiting.x", waitingSpawn.getX());
            sec.set("waiting.y", waitingSpawn.getY());
            sec.set("waiting.z", waitingSpawn.getZ());
            sec.set("waiting.yaw", (double) waitingSpawn.getYaw());
            sec.set("waiting.pitch", (double) waitingSpawn.getPitch());
        }
        // team spawns
        for (Map.Entry<Integer, Location> e : teamSpawns.entrySet()) {
            String key = "team." + e.getKey();
            Location l = e.getValue();
            sec.set(key + ".x", l.getX());
            sec.set(key + ".y", l.getY());
            sec.set(key + ".z", l.getZ());
            sec.set(key + ".yaw", (double) l.getYaw());
            sec.set(key + ".pitch", (double) l.getPitch());
        }
    }

    public static Arena load(org.jlakctep.santaPillars.SantaPillars plugin, String id, ConfigurationSection sec) {
        GameSize size = GameSize.fromKey(sec.getString("size", "1x4"));
        World world = Bukkit.getWorld(sec.getString("world", "world"));
        if (size == null || world == null) return null;
        Arena a = new Arena(plugin, id, size, world);

        if (sec.isConfigurationSection("waiting")) {
            double x = sec.getDouble("waiting.x", 0.5);
            double y = sec.getDouble("waiting.y", 1.0);
            double z = sec.getDouble("waiting.z", 0.5);
            float yaw = (float) sec.getDouble("waiting.yaw", 0.0);
            float pitch = (float) sec.getDouble("waiting.pitch", 0.0);
            a.waitingSpawn = new Location(world, x, y, z, yaw, pitch);
        }

        if (sec.isConfigurationSection("team")) {
            for (String k : sec.getConfigurationSection("team").getKeys(false)) {
                int idx;
                try { idx = Integer.parseInt(k); } catch (Exception e) { continue; }
                double x = sec.getDouble("team."+k+".x");
                double y = sec.getDouble("team."+k+".y");
                double z = sec.getDouble("team."+k+".z");
                float yaw = (float) sec.getDouble("team."+k+".yaw", 0.0);
                float pitch = (float) sec.getDouble("team."+k+".pitch", 0.0);
                a.teamSpawns.put(idx, new Location(world, x, y, z, yaw, pitch));
            }
        }
        return a;
    }

    public void shutdown() {
        if (loopTask != null) loopTask.cancel();
        if (bossBar != null) bossBar.removeAll();
    }

    private record BlockPos(int x, int y, int z) {}

    // Запись последнего удара по игроку (для засчёта килла при падении/добивании)
    public void recordHit(UUID victim, UUID attacker) {
        if (victim == null || attacker == null) return;
        lastHit.put(victim, new AttackInfo(attacker, System.currentTimeMillis()));
    }

    private static final class AttackInfo {
        private final UUID attacker;
        private final long time;
        private AttackInfo(UUID attacker, long time) { this.attacker = attacker; this.time = time; }
    }
}