package org.jlakctep.santaPillars.game;

import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.WorldCreator;
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
import org.jlakctep.santaPillars.util.VoidGenerator;
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
    // Участники матча: фиксируются на старте игры для объявлений топа
    private final Set<UUID> participants = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> placedBlocks = ConcurrentHashMap.newKeySet();
    // Временные блоки клеток (строятся только на время фриза)
    private final Set<BlockPos> cageBlocks = ConcurrentHashMap.newKeySet();
    // Блоки, изменённые режимами пола (лава/аблокалипсис) для очистки после игры
    private final Set<BlockPos> floorBlocks = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> kills = new HashMap<>();
    // Последний нанесший урон: жертва -> (атакующий, время удара)
    private final Map<UUID, AttackInfo> lastHit = new HashMap<>();
    // Закреплённые места игроков (team1..teamN) до старта и во время игры
    private final Map<UUID, Integer> assignedTeams = new HashMap<>();

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

    // Голосование: режим игры и пол
    public enum GameModeOption { NORMAL, SHUFFLE, BALANCE, EXCHANGE }
    public enum FloorModeOption { NORMAL, RISING_LAVA, APOCALYPSE, FRAGILE }
    private final Map<UUID, GameModeOption> gameModeVotes = new HashMap<>();
    private final Map<UUID, FloorModeOption> floorVotes = new HashMap<>();
    private GameModeOption gameModeActive = GameModeOption.NORMAL;
    private FloorModeOption floorModeActive = FloorModeOption.NORMAL;
    // Состояние пола: управление лавой
    private int lavaCurrentY = Integer.MIN_VALUE;
    // Текущая высота лавы по столбцам XZ (ключ = packXZ)
    private final java.util.Map<Long, Integer> lavaTop = new java.util.HashMap<>();
    private int lavaBatchCounter = 0;
    private final java.util.Random rng = new java.util.Random();
    private int exchangeCounter = 0;
    private int exchangePeriodTicks = 0;

    public Arena(SantaPillars plugin, String id, GameSize size, World world) {
        this.plugin = plugin; this.id = id; this.size = size; this.world = world;
        if (plugin.getConfig().getBoolean("ui.bossbar", true)) {
            bossBar = Bukkit.createBossBar("Pillars", BarColor.YELLOW, BarStyle.SEGMENTED_12);
            bossBar.setVisible(false);
        }
    }

    public GameState getState() { return state; }
    public FloorModeOption getActiveFloorMode() { return floorModeActive; }
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

        // Сообщение о входе игрока на арену
        try {
            String joinMsg = Msg.tr("join.arena");
            if (joinMsg == null || joinMsg.isEmpty()) {
                joinMsg = "&a%player% &7присоединился к арене &e%arena%";
            }
            broadcast(joinMsg.replace("%player%", p.getName()).replace("%arena%", id).replace("%size%", size.key()));
        } catch (Exception ignored) {}

        p.getInventory().clear();
        p.setGameMode(GameMode.ADVENTURE);
        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.setFireTicks(0);
        // Сразу отправляем игрока на командный спавн (на +5 блоков) и строим клетку
        int idx = assignTeamIndexFor(p.getUniqueId());
        Location tl = teamSpawns.get(idx);
        if (tl == null) tl = waitingSpawn != null ? waitingSpawn : plugin.lobbySpawn();
        Location above = centerOfBlock(tl, 5);
        p.teleport(above);
        buildCageAt(above);

        // Предметы ожидания: Кровать (выход) и Колокол (Голосование)
        try {
            org.bukkit.inventory.ItemStack bell = new org.bukkit.inventory.ItemStack(Material.BELL);
            var im1 = bell.getItemMeta();
            if (im1 != null) { im1.displayName(net.kyori.adventure.text.Component.text("Голосование").color(net.kyori.adventure.text.format.NamedTextColor.GOLD).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)); bell.setItemMeta(im1); }
            p.getInventory().setItem(0, bell);

            org.bukkit.inventory.ItemStack bed = new org.bukkit.inventory.ItemStack(Material.RED_BED);
            var im2 = bed.getItemMeta();
            if (im2 != null) { im2.displayName(net.kyori.adventure.text.Component.text("Покинуть арену").color(net.kyori.adventure.text.format.NamedTextColor.GREEN).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)); bed.setItemMeta(im2); }
            p.getInventory().setItem(8, bed);
        } catch (Exception ignored) {}

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
        assignedTeams.remove(uuid);
        // Сброс голосов игрока при выходе с арены
        gameModeVotes.remove(uuid);
        floorVotes.remove(uuid);
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
                    im.displayName(net.kyori.adventure.text.Component.text("Рандомная игра").color(net.kyori.adventure.text.format.NamedTextColor.GOLD).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                    it.setItemMeta(im);
                }
                p.getInventory().setItem(4, it);
            } catch (Exception ignored) {}
            clearScoreboard(p);
        }
        if (state == GameState.INGAME || state == GameState.STARTING) {
            // Сообщение об уходе/отключении
            String nm = p != null ? p.getName() : ("Игрок-" + uuid.toString().substring(0, 4));
            String key = eliminate ? "left.disconnected" : "left.left";
            broadcast(Msg.tr(key).replace("%player%", nm));
            checkWin();
        }
        if (players.isEmpty() && state != GameState.WAITING) end(null);
        updateScoreboards();
        // В ожидании поддерживаем клетки в актуальном состоянии
        if (state == GameState.WAITING || state == GameState.STARTING) rebuildWaitingCages();
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

        // Без стартового фриза
        freezeTicks = 0;
        ticksToNextItem = Math.max(1, plugin.getConfig().getInt("timings.item-period-ticks", 40));
        int hardSec = Math.max(0, plugin.getConfig().getInt("timings.hard-time-limit-seconds", 0));
        hardLimitTicks = hardSec > 0 ? hardSec * 20 : 0;

        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            p.getInventory().clear();
            p.setGameMode(GameMode.SURVIVAL);
        }
        // Зафиксировать участников для итоговой таблицы
        participants.clear();
        participants.addAll(players);

        // Вычисляем победителей голосования
        resolveVotes();

        // Клетки перед стартом были, при старте сразу убираем и показываем титр
        clearCages();
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                p.sendTitle(ChatColor.GREEN + Msg.tr("started-title"), "", 10, 30, 10);
                // Эффект медленного падения на 3 секунды
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING, 3 * 20, 0, false, false, false));
            }
        }
        broadcast(Msg.tr("started"));

        if (bossBar != null) {
            bossBar.setVisible(true);
            for (UUID u : players) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) bossBar.addPlayer(p);
            }
        }
        updateScoreboards();
    }

    private void resolveVotes() {
        gameModeActive = tallyGameMode();
        floorModeActive = tallyFloorMode();
        if (gameModeActive != GameModeOption.NORMAL) broadcast("&eРежим: &6" + gameModeActiveToDisplay(gameModeActive));
        if (floorModeActive != FloorModeOption.NORMAL) broadcast("&eПол: &6" + floorModeToDisplay(floorModeActive));
        // Пол: подготовка
        if (floorModeActive == FloorModeOption.RISING_LAVA) prepareRisingLava();
        if (gameModeActive == GameModeOption.EXCHANGE) {
            exchangePeriodTicks = Math.max(20, plugin.getConfig().getInt("modes.exchange.swap_period_ticks", 200));
            exchangeCounter = 0;
        }
    }

    private GameModeOption tallyGameMode() {
        int n = 0, sh = 0, b = 0, ex = 0;
        for (GameModeOption v : gameModeVotes.values()) {
            switch (v) { case NORMAL -> n++; case SHUFFLE -> sh++; case BALANCE -> b++; case EXCHANGE -> ex++; }
        }
        if (n==0 && sh==0 && b==0 && ex==0) return GameModeOption.NORMAL;
        int best = -1; GameModeOption win = GameModeOption.NORMAL;
        if (n>best) { best=n; win=GameModeOption.NORMAL; }
        if (sh>best) { best=sh; win=GameModeOption.SHUFFLE; }
        if (b>best) { best=b; win=GameModeOption.BALANCE; }
        if (ex>best) { best=ex; win=GameModeOption.EXCHANGE; }
        return win;
    }

    private FloorModeOption tallyFloorMode() {
        int n = 0, rl = 0, ap = 0, fr = 0;
        for (FloorModeOption v : floorVotes.values()) {
            switch (v) { case NORMAL -> n++; case RISING_LAVA -> rl++; case APOCALYPSE -> ap++; case FRAGILE -> fr++; }
        }
        if (n==0 && rl==0 && ap==0 && fr==0) return FloorModeOption.NORMAL;
        int best = -1; FloorModeOption win = FloorModeOption.NORMAL;
        if (n>best) { best=n; win=FloorModeOption.NORMAL; }
        if (rl>best) { best=rl; win=FloorModeOption.RISING_LAVA; }
        if (ap>best) { best=ap; win=FloorModeOption.APOCALYPSE; }
        if (fr>best) { best=fr; win=FloorModeOption.FRAGILE; }
        return win;
    }

    private String gameModeActiveToDisplay(GameModeOption m) {
        return switch (m) { case NORMAL -> "Нормальный"; case SHUFFLE -> "Перетасовка"; case BALANCE -> "Баланс"; case EXCHANGE -> "Обмен"; };
    }
    private String floorModeToDisplay(FloorModeOption m) {
        return switch (m) { case NORMAL -> "Нормальный"; case RISING_LAVA -> "Поднимающаяся лава"; case APOCALYPSE -> "Аблокалипсис"; case FRAGILE -> "Хрупкие блоки"; };
    }

    // Публичные версии для отображения выбора в сообщениях
    public static String displayGameMode(GameModeOption m) {
        return switch (m) { case NORMAL -> "Нормальный"; case SHUFFLE -> "Перетасовка"; case BALANCE -> "Баланс"; case EXCHANGE -> "Обмен"; };
    }
    public static String displayFloorMode(FloorModeOption m) {
        return switch (m) { case NORMAL -> "Нормальный"; case RISING_LAVA -> "Поднимающаяся лава"; case APOCALYPSE -> "Аблокалипсис"; case FRAGILE -> "Хрупкие блоки"; };
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
            // Эффекты пола
            if (floorModeActive == FloorModeOption.RISING_LAVA) tickRisingLava();
            else if (floorModeActive == FloorModeOption.APOCALYPSE) tickApocalypse();
            // Обмен инвентарями по таймеру
            if (gameModeActive == GameModeOption.EXCHANGE && exchangePeriodTicks > 0) {
                if (++exchangeCounter % exchangePeriodTicks == 0) performInventoryExchange();
            }
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
                    // Снимаем клетки и выдаём медленное падение на 3 секунды
                    clearCages();
                    for (UUID u : new java.util.ArrayList<>(alive)) {
                        Player p = Bukkit.getPlayer(u);
                        if (p != null) {
                            p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING, 3 * 20, 0, false, false, false));
                        }
                    }
                }
            }
            // Объявления оставшегося времени: раз в секунду, сразу с начала игры
            if (hardLimitTicks > 0 && hardLimitTicks % 20 == 0) {
                int hardLimitSecCfg = Math.max(0, plugin.getConfig().getInt("timings.hard-time-limit-seconds", 0));
                if (hardLimitSecCfg > 0) {
                    int rawElapsed = (int)((System.currentTimeMillis() - startTimeMs) / 1000);
                    int effectiveElapsed = Math.max(0, rawElapsed);
                    int timeLeft = Math.max(hardLimitSecCfg - Math.max(0, effectiveElapsed), 0);
                    if (timeLeft != lastTimeLeftAnnounced) {
                        if (timeLeft >= 60 && timeLeft % 60 == 0) {
                            int mins = timeLeft / 60;
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

    private void performInventoryExchange() {
        java.util.List<Player> lst = new java.util.ArrayList<>();
        for (UUID u : alive) { Player p = Bukkit.getPlayer(u); if (p != null) lst.add(p); }
        if (lst.size() < 2) return;
        java.util.List<org.bukkit.inventory.ItemStack[]> contents = new java.util.ArrayList<>();
        for (Player p : lst) contents.add(p.getInventory().getContents());
        // циклический сдвиг инвентарей вправо
        for (int i = 0; i < lst.size(); i++) {
            org.bukkit.inventory.ItemStack[] from = contents.get((i - 1 + lst.size()) % lst.size());
            lst.get(i).getInventory().setContents(copyItems(from));
        }
    }

    private org.bukkit.inventory.ItemStack[] copyItems(org.bukkit.inventory.ItemStack[] src) {
        org.bukkit.inventory.ItemStack[] dst = new org.bukkit.inventory.ItemStack[src.length];
        for (int i = 0; i < src.length; i++) dst[i] = (src[i] == null ? null : src[i].clone());
        return dst;
    }

    private void prepareRisingLava() {
        // Стартовый Y = 0 (как просили)
        lavaCurrentY = 0;
        lavaTop.clear();
        lavaBatchCounter = 0;
        // База: заполняем от y=0 вниз на depth-1 по всей области (радиус из конфига)
        int radius = Math.max(16, plugin.getConfig().getInt("floor.effect_radius", 100));
        int depth = Math.max(1, plugin.getConfig().getInt("floor.rising_lava.depth", 7));
        org.bukkit.Location center = world.getSpawnLocation();
        int cx = center.getBlockX();
        int cz = center.getBlockZ();
        int baseBottomY = Math.max(world.getMinHeight(), 0 - (depth - 1));
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                long key = packXZ(x, z);
                for (int y = 0; y >= baseBottomY; y--) {
                    org.bukkit.block.Block b = world.getBlockAt(x, y, z);
                    if (b.getType() == org.bukkit.Material.AIR) {
                        b.setType(org.bukkit.Material.LAVA, false);
                        floorBlocks.add(new BlockPos(x, y, z));
                    }
                }
                lavaTop.put(key, 0);
            }
        }
    }

    private void tickRisingLava() {
        int period = Math.max(1, plugin.getConfig().getInt("floor.rising_lava.batch_period_ticks", 20));
        int columnsPerBatch = Math.max(1, plugin.getConfig().getInt("floor.rising_lava.blocks_per_second", 1));
        int maxColumnHeight = Math.max(1, plugin.getConfig().getInt("floor.rising_lava.max_column_height", 3));
        int radius = Math.max(1, plugin.getConfig().getInt("floor.effect_radius", 50));

        if (lavaCurrentY == Integer.MIN_VALUE) prepareRisingLava();

        lavaBatchCounter++;
        if (lavaBatchCounter % period != 0) return;

        org.bukkit.Location center = world.getSpawnLocation();
        int cx = center.getBlockX();
        int cz = center.getBlockZ();

        for (int i = 0; i < columnsPerBatch; i++) {
            int x = cx + rng.nextInt(radius * 2 + 1) - radius;
            int z = cz + rng.nextInt(radius * 2 + 1) - radius;
            long key = packXZ(x, z);
            int currentTop = lavaTop.containsKey(key) ? lavaTop.get(key) : findTopAt(x, z);
            int height = 1 + rng.nextInt(Math.max(1, maxColumnHeight)); // 1..max
            int placedUpTo = currentTop;
            for (int dy = 1; dy <= height; dy++) {
                int y = currentTop + dy;
                if (y > world.getMaxHeight() - 1) break;
                org.bukkit.block.Block b = world.getBlockAt(x, y, z);
                if (b.getType() == org.bukkit.Material.AIR) {
                    b.setType(org.bukkit.Material.LAVA, false);
                    floorBlocks.add(new BlockPos(x, y, z));
                    placedUpTo = y;
                } else if (b.getType() == org.bukkit.Material.LAVA) {
                    placedUpTo = y;
                } else {
                    break; // упёрлись в твёрдый блок
                }
            }
            lavaTop.put(key, placedUpTo);
            lavaCurrentY = Math.max(lavaCurrentY, placedUpTo);
        }
    }

    private int findTopAt(int x, int z) {
        // Найти самую верхнюю лаву на столбце XZ начиная с 0 вверх
        int y = 0;
        int maxY = world.getMaxHeight() - 1;
        while (y + 1 <= maxY && world.getBlockAt(x, y + 1, z).getType() == org.bukkit.Material.LAVA) y++;
        return y;
    }

    // (устаревшая очередь для лавы удалена — теперь рандомные колонки)

    private static long packXZ(int x, int z) { return (((long)x) << 32) ^ (z & 0xffffffffL); }

    private void tickApocalypse() {
        int radius = Math.max(16, plugin.getConfig().getInt("floor.effect_radius", 100));
        int perTick = Math.max(1, plugin.getConfig().getInt("floor.apocalypse.blocks_per_tick", 10));
        org.bukkit.Location center = world.getSpawnLocation();
        for (int i = 0; i < perTick; i++) {
            int rx = center.getBlockX() + rng.nextInt(radius * 2 + 1) - radius;
            int rz = center.getBlockZ() + rng.nextInt(radius * 2 + 1) - radius;
            int ry = Math.min(Math.max(world.getMinHeight(), center.getBlockY() + rng.nextInt(60) - 30), world.getMaxHeight()-1);
            org.bukkit.block.Block b = world.getBlockAt(rx, ry, rz);
            if (b.getType() == org.bukkit.Material.AIR) {
                // Случайный твёрдый блок
                org.bukkit.Material[] mats = new org.bukkit.Material[]{ org.bukkit.Material.STONE, org.bukkit.Material.COBBLESTONE, org.bukkit.Material.DIRT, org.bukkit.Material.NETHERRACK, org.bukkit.Material.OBSIDIAN };
                b.setType(mats[rng.nextInt(mats.length)], false);
                floorBlocks.add(new BlockPos(rx, ry, rz));
            }
        }
    }

    public org.jlakctep.santaPillars.game.Arena.GameModeOption getGameModeVote(UUID u) { return gameModeVotes.get(u); }
    public org.jlakctep.santaPillars.game.Arena.FloorModeOption getFloorVote(UUID u) { return floorVotes.get(u); }
    public void setGameModeVote(UUID u, org.jlakctep.santaPillars.game.Arena.GameModeOption v) { gameModeVotes.put(u, v); }
    public void setFloorVote(UUID u, org.jlakctep.santaPillars.game.Arena.FloorModeOption v) { floorVotes.put(u, v); }

    private void buildCageAt(Location center) {
        if (center == null || center.getWorld() == null) return;
        World w = center.getWorld();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        // Пол (y-1)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                setCageBlockIfAir(w, cx + dx, cy - 1, cz + dz, Material.GLASS);
            }
        }
        // Стены (y..y+2), полая середина
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean isEdge = Math.abs(dx) == 1 || Math.abs(dz) == 1;
                    if (isEdge) setCageBlockIfAir(w, cx + dx, cy + dy, cz + dz, Material.GLASS);
                }
            }
        }
        // Крыша (y+3)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                setCageBlockIfAir(w, cx + dx, cy + 3, cz + dz, Material.GLASS);
            }
        }
    }

    private void setCageBlockIfAir(World w, int x, int y, int z, Material type) {
        org.bukkit.block.Block b = w.getBlockAt(x, y, z);
        Material cur = b.getType();
        if (cur == Material.AIR || cur == Material.CAVE_AIR || cur == Material.VOID_AIR) {
            b.setType(type, false);
            cageBlocks.add(new BlockPos(x, y, z));
        }
    }

    private void clearCages() {
        if (cageBlocks.isEmpty()) return;
        for (BlockPos bp : new java.util.ArrayList<>(cageBlocks)) {
            org.bukkit.block.Block b = world.getBlockAt(bp.x, bp.y, bp.z);
            if (b.getType() == Material.GLASS) {
                b.setType(Material.AIR, false);
            }
        }
        cageBlocks.clear();
    }

    private void giveItems() {
        boolean allowDrop = plugin.getConfig().getBoolean("build.allow-item-drop", false);
        // BALANCE: выберем один материал/кол-во для всех
        ItemStack balanced = null;
        if (gameModeActive == GameModeOption.BALANCE) {
            var mat = plugin.items().randomMaterial();
            int amt = plugin.items().randomAmount();
            balanced = new ItemStack(mat, amt);
        }
        // EXCHANGE: подготовим биекцию перестановки
        java.util.List<UUID> aliveList = new ArrayList<>(alive);
        java.util.Map<UUID, UUID> exchangeMap = new java.util.HashMap<>();
        if (gameModeActive == GameModeOption.EXCHANGE && aliveList.size() >= 2) {
            java.util.List<UUID> src = new ArrayList<>(aliveList);
            java.util.List<UUID> dst = new ArrayList<>(aliveList);
            java.util.Collections.shuffle(dst, rng);
            // гарантировать отсутствие неподвижных точек сложно без цикла; примем возможные совпадения
            for (int i = 0; i < src.size(); i++) exchangeMap.put(src.get(i), dst.get(i));
        }
        for (UUID u : aliveList) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            ItemStack it;
            if (gameModeActive == GameModeOption.BALANCE && balanced != null) {
                it = balanced.clone();
            } else {
            var mat = plugin.items().randomMaterial();
            int amt = plugin.items().randomAmount();
                it = new ItemStack(mat, amt);
            }
            // EXCHANGE: заменим на предмет, вычисленный для другого игрока
            if (gameModeActive == GameModeOption.EXCHANGE && exchangeMap.containsKey(u)) {
                UUID from = exchangeMap.get(u);
                // сгенерируем предмет для from так же, как выше
                var mat2 = plugin.items().randomMaterial();
                int amt2 = plugin.items().randomAmount();
                it = new ItemStack(mat2, amt2);
            }
            // Выдать предмет
            var left = p.getInventory().addItem(it);
            if (!left.isEmpty() && allowDrop) {
                left.values().forEach(v -> p.getWorld().dropItemNaturally(p.getLocation(), v));
            }
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.2f);
            // SHUFFLE: перемешать инвентарь игрока
            if (gameModeActive == GameModeOption.SHUFFLE) shuffleInventory(p.getInventory());
        }
    }

    private void shuffleInventory(org.bukkit.inventory.PlayerInventory inv) {
        java.util.List<Integer> slots = new java.util.ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) slots.add(i);
        java.util.Collections.shuffle(slots, rng);
        org.bukkit.inventory.ItemStack[] copy = inv.getContents().clone();
        for (int i = 0; i < slots.size(); i++) {
            inv.setItem(i, copy[slots.get(i)]);
        }
    }

    public boolean isFrozen(Player p) {
        return state == GameState.INGAME && freezeTicks > 0 && alive.contains(p.getUniqueId());
    }

    public void onBlockPlace(org.bukkit.block.Block b) { placedBlocks.add(new BlockPos(b.getX(), b.getY(), b.getZ())); }
    public void onBlockPlace(org.bukkit.block.Block b, org.bukkit.block.data.BlockData data) {
        placedBlocks.add(new BlockPos(b.getX(), b.getY(), b.getZ()));
        try {
            if (data instanceof Bed bed) {
                BlockFace facing = bed.getFacing();
                boolean isHead = (bed.getPart() == Bed.Part.HEAD);
                org.bukkit.block.Block other = b.getRelative(isHead ? facing.getOppositeFace() : facing);
                placedBlocks.add(new BlockPos(other.getX(), other.getY(), other.getZ()));
            } else if (data instanceof Door door) {
                boolean isTop = (door.getHalf() == Bisected.Half.TOP);
                org.bukkit.block.Block other = b.getRelative(isTop ? BlockFace.DOWN : BlockFace.UP);
                placedBlocks.add(new BlockPos(other.getX(), other.getY(), other.getZ()));
            }
        } catch (Throwable ignored) {}
    }

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
        onDeath(p, null);
    }

    public void onDeath(Player p, org.bukkit.event.entity.EntityDamageEvent.DamageCause cause) {
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
        String deathMsg;
        if (credited != null) {
            kills.put(credited, kills.getOrDefault(credited, 0) + 1);
            Player kp = Bukkit.getPlayer(credited);
            try { plugin.stats().incrementKills(credited, 1); } catch (Throwable ignored) {}
            if (kp != null) rewardKill(kp);
            // ActionBar убийце
            try {
                if (kp != null) {
                    String ab = Msg.tr("kill.actionbar").replace("%player%", p.getName());
                    kp.sendActionBar(ChatColor.translateAlternateColorCodes('&', ab));
                }
            } catch (Throwable ignored) {}
            String template;
            boolean fellIntoVoid = p.getLocation().getY() < p.getWorld().getMinHeight();
            boolean burned = (cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.LAVA || cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FIRE || cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FIRE_TICK || cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.HOT_FLOOR);
            if (burned) template = Msg.tr("death.burned_by");
            else template = Msg.tr(fellIntoVoid ? "death.pushed" : "death.killed");
            String killerName = kp != null ? kp.getName() : "Игрок";
            deathMsg = template.replace("%victim%", p.getName()).replace("%killer%", killerName);
        } else {
            // Причина: падение в бездну/падение/прочее без прямого убийцы
            if (cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.LAVA || cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FIRE || cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FIRE_TICK || cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.HOT_FLOOR) {
                deathMsg = Msg.tr("death.burned").replace("%victim%", p.getName());
            } else if (p.getLocation().getY() < p.getWorld().getMinHeight()) {
                deathMsg = Msg.tr("death.void").replace("%victim%", p.getName());
            } else {
                deathMsg = Msg.tr("death.generic").replace("%victim%", p.getName());
            }
        }
        if (deathMsg != null && !deathMsg.isEmpty()) broadcast(deathMsg);
        lastHit.remove(p.getUniqueId());
        p.getInventory().clear();
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport((waitingSpawn != null ? waitingSpawn : plugin.lobbySpawn()));
        showLoseTitle(p);
        Msg.send(p, "eliminated");
        // Если после этой смерти остался один игрок — не показываем кнопки/подсказку (их заменит финальный экран)
        if (alive.size() > 1) {
            try {
                p.sendMessage(Msg.tr("leave-hint")); // "&8Выберите:" (конфиг)
                String playLabel = Msg.tr("death.buttons.play.label");
                String playHover = Msg.tr("death.buttons.play.hover");
                String playCommand = Msg.tr("death.buttons.play.command");
                if (playLabel == null || playLabel.isEmpty() || "death.buttons.play.label".equals(playLabel)) playLabel = Msg.tr("end.buttons.play.label");
                if (playHover == null || playHover.isEmpty() || "death.buttons.play.hover".equals(playHover)) playHover = Msg.tr("end.buttons.play.hover");
                if (playCommand == null || playCommand.isEmpty() || "death.buttons.play.command".equals(playCommand)) playCommand = Msg.tr("end.buttons.play.command");
                if (playCommand == null || playCommand.isEmpty()) playCommand = "/join random";
                if (playLabel == null || playLabel.isEmpty() || playLabel.contains("buttons.play.label")) playLabel = "&aСыграть ещё раз";
                if (playHover == null || playHover.isEmpty() || playHover.contains("buttons.play.hover")) playHover = "&8Перейти в случайную игру";
                net.md_5.bungee.api.chat.TextComponent play = new net.md_5.bungee.api.chat.TextComponent(org.bukkit.ChatColor.translateAlternateColorCodes('&', playLabel));
                play.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, playCommand));
                play.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                        net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                        new net.md_5.bungee.api.chat.BaseComponent[]{ new net.md_5.bungee.api.chat.TextComponent(org.bukkit.ChatColor.translateAlternateColorCodes('&', playHover)) }
                ));

                String sepLabel = Msg.tr("death.buttons.separator");
                if (sepLabel == null || sepLabel.isEmpty() || sepLabel.contains("buttons.separator")) sepLabel = " &7• ";
                net.md_5.bungee.api.chat.TextComponent sep = new net.md_5.bungee.api.chat.TextComponent(org.bukkit.ChatColor.translateAlternateColorCodes('&', sepLabel));

                String leaveLabel = Msg.tr("death.buttons.leave.label");
                String leaveHover = Msg.tr("death.buttons.leave.hover");
                String leaveCommand = Msg.tr("death.buttons.leave.command");
                if (leaveLabel == null || leaveLabel.isEmpty() || "death.buttons.leave.label".equals(leaveLabel)) leaveLabel = Msg.tr("end.buttons.leave.label");
                if (leaveHover == null || leaveHover.isEmpty() || "death.buttons.leave.hover".equals(leaveHover)) leaveHover = Msg.tr("end.buttons.leave.hover");
                if (leaveCommand == null || leaveCommand.isEmpty() || "death.buttons.leave.command".equals(leaveCommand)) leaveCommand = Msg.tr("end.buttons.leave.command");
                if (leaveCommand == null || leaveCommand.isEmpty()) leaveCommand = "/leave";
                if (leaveLabel == null || leaveLabel.isEmpty() || leaveLabel.contains("buttons.leave.label")) leaveLabel = "&cВыйти";
                if (leaveHover == null || leaveHover.isEmpty() || leaveHover.contains("buttons.leave.hover")) leaveHover = "&8Выйти в лобби";
                net.md_5.bungee.api.chat.TextComponent leave = new net.md_5.bungee.api.chat.TextComponent(org.bukkit.ChatColor.translateAlternateColorCodes('&', leaveLabel));
                leave.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, leaveCommand));
                leave.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                        net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                        new net.md_5.bungee.api.chat.BaseComponent[]{ new net.md_5.bungee.api.chat.TextComponent(org.bukkit.ChatColor.translateAlternateColorCodes('&', leaveHover)) }
                ));

                p.spigot().sendMessage(play, sep, leave);
            } catch (Throwable ignored) {}
        }
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
        // Больше не выдаём предметы наблюдателю
        try { p.getInventory().clear(); } catch (Exception ignored) {}
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
        clearCages();
        if (bossBar != null) { bossBar.setVisible(false); bossBar.removeAll(); }
        sendEndSummary(winner);
        // Кнопки действий в чат (кликабельные) — только "Сыграть ещё раз"
        try {
            for (UUID u : new java.util.ArrayList<>(players)) {
                Player p = Bukkit.getPlayer(u);
                if (p == null) continue;
                String playLabel = Msg.tr("end.buttons.play.label");
                String playHover = Msg.tr("end.buttons.play.hover");
                net.md_5.bungee.api.chat.TextComponent play = new net.md_5.bungee.api.chat.TextComponent(ChatColor.translateAlternateColorCodes('&', playLabel == null ? "&a[Сыграть ещё раз]" : playLabel));
                play.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, "/join random"));
                play.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                        net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                        new net.md_5.bungee.api.chat.BaseComponent[]{ new net.md_5.bungee.api.chat.TextComponent(ChatColor.translateAlternateColorCodes('&', playHover == null ? "Нажми чтобы присоединиться к случайной арене" : playHover)) }
                ));
                p.spigot().sendMessage(play);
            }
        } catch (Throwable ignored) {}
        try { handlePayoutsOnEnd(winner); } catch (Exception ignored) {}
        try { if (winner != null) plugin.stats().incrementWins(winner.getUniqueId(), 1); } catch (Throwable ignored) {}
        try { plugin.stats().save(); } catch (Throwable ignored) {}

        // Показать титры участникам (наблюдателям ничего не показываем)
        if (winner != null) {
            showVictoryTitle(winner);
            for (UUID u : new ArrayList<>(alive)) {
                if (u.equals(winner.getUniqueId())) continue;
                Player p = Bukkit.getPlayer(u);
                if (p != null) showLoseTitle(p);
            }
        }

        // ActionBar: победитель/проигравшие
        try {
            String abWin = Msg.tr("actionbar.win");
            String abLose = Msg.tr("actionbar.lose");
            for (UUID u : new ArrayList<>(players)) {
                Player p = Bukkit.getPlayer(u);
                if (p == null) continue;
                if (winner != null && u.equals(winner.getUniqueId())) {
                    if (abWin != null && !abWin.isEmpty()) p.sendActionBar(ChatColor.translateAlternateColorCodes('&', abWin));
                } else {
                    if (abLose != null && !abLose.isEmpty()) p.sendActionBar(ChatColor.translateAlternateColorCodes('&', abLose));
                }
            }
        } catch (Throwable ignored) {}

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
                            im.displayName(net.kyori.adventure.text.Component.text("Рандомная игра").color(net.kyori.adventure.text.format.NamedTextColor.GOLD).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
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
            assignedTeams.clear();
            // Очистка голосов и сброс активных режимов
            gameModeVotes.clear();
            floorVotes.clear();
            gameModeActive = GameModeOption.NORMAL;
            floorModeActive = FloorModeOption.NORMAL;
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
        clearCages();
        // Очистка блоков пола, созданных режимами
        if (!floorBlocks.isEmpty()) {
            for (BlockPos bp : new java.util.ArrayList<>(floorBlocks)) {
            var b = world.getBlockAt(bp.x, bp.y, bp.z);
                if (b.getType() == org.bukkit.Material.LAVA || b.getType().isSolid()) {
                    b.setType(org.bukkit.Material.AIR, false);
                }
            }
            floorBlocks.clear();
        }
        for (BlockPos bp : placedBlocks) {
            removeBlockAndLinked(bp.x, bp.y, bp.z);
        }
        placedBlocks.clear();
    }

    private void removeBlockAndLinked(int x, int y, int z) {
        org.bukkit.block.Block base = world.getBlockAt(x, y, z);
        BlockData data = base.getBlockData();
        if (data instanceof Bed bed) {
            // Удалить обе части кровати
            BlockFace facing = bed.getFacing();
            boolean isHead = (bed.getPart() == Bed.Part.HEAD);
            org.bukkit.block.Block other = base.getRelative(isHead ? facing.getOppositeFace() : facing);
            base.setType(Material.AIR, false);
            if (other.getBlockData() instanceof Bed) other.setType(Material.AIR, false);
            return;
        }
        if (data instanceof Door door) {
            // Удалить обе половины двери
            boolean isTop = (door.getHalf() == Bisected.Half.TOP);
            org.bukkit.block.Block other = base.getRelative(isTop ? BlockFace.DOWN : BlockFace.UP);
            base.setType(Material.AIR, false);
            if (other.getBlockData() instanceof Door) other.setType(Material.AIR, false);
            return;
        }
        // По умолчанию — просто очистить
        base.setType(Material.AIR, false);
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

    private java.util.List<UUID> sortParticipantsByKills() {
        java.util.ArrayList<UUID> order = new java.util.ArrayList<>(participants);
        order.sort((a, b) -> Integer.compare(kills.getOrDefault(b, 0), kills.getOrDefault(a, 0)));
        return order;
    }

    private void announceTopByKills(Player winner) {
        java.util.List<UUID> order = sortParticipantsByKills();
        int topN = Math.min(3, order.size());
        if (topN == 0) return;
        broadcast(Msg.tr("win-top-header"));
        for (int i = 0; i < topN; i++) {
            UUID u = order.get(i);
            Player p = Bukkit.getPlayer(u);
            String name = p != null ? p.getName() : ("Игрок-" + u.toString().substring(0, 4));
            int k = kills.getOrDefault(u, 0);
            String line = Msg.tr("win-top-line")
                    .replace("%place%", String.valueOf(i + 1))
                    .replace("%player%", name)
                    .replace("%kills%", String.valueOf(k));
            broadcast(line);
        }
    }

    private void sendEndSummary(Player winner) {
        java.util.List<String> lines = Msg.trList("end.summary");
        if (lines == null || lines.isEmpty()) {
            if (winner != null) broadcast(Msg.tr("win").replace("%player%", winner.getName()));
            else broadcast(Msg.tr("draw"));
            announceTopByKills(winner);
            return;
        }
        // Подготовить данные для подстановки
        String winnerName = winner != null ? winner.getName() : "-";
        java.util.List<UUID> tops = sortParticipantsByKills();
        int k1 = tops.size() > 0 ? kills.getOrDefault(tops.get(0), 0) : 0;
        int k2 = tops.size() > 1 ? kills.getOrDefault(tops.get(1), 0) : 0;
        int k3 = tops.size() > 2 ? kills.getOrDefault(tops.get(2), 0) : 0;
        String n1 = tops.size() > 0 ? (Bukkit.getPlayer(tops.get(0)) != null ? Bukkit.getPlayer(tops.get(0)).getName() : "-") : "-";
        String n2 = tops.size() > 1 ? (Bukkit.getPlayer(tops.get(1)) != null ? Bukkit.getPlayer(tops.get(1)).getName() : "-") : "-";
        String n3 = tops.size() > 2 ? (Bukkit.getPlayer(tops.get(2)) != null ? Bukkit.getPlayer(tops.get(2)).getName() : "-") : "-";
        for (String raw : lines) {
            String line = raw
                    .replace("%winner%", winnerName)
                    .replace("%top1_name%", n1).replace("%top1_kills%", String.valueOf(k1))
                    .replace("%top2_name%", n2).replace("%top2_kills%", String.valueOf(k2))
                    .replace("%top3_name%", n3).replace("%top3_kills%", String.valueOf(k3));
            broadcast(line);
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
        // Закрепляем индексы для всех и телепортируем на их спавны
        java.util.HashSet<Integer> used = new java.util.HashSet<>(assignedTeams.values());
        for (UUID u : new ArrayList<>(players)) {
            if (!assignedTeams.containsKey(u)) {
                int idx = 1;
                while (used.contains(idx) && idx <= size.max()) idx++;
                if (idx <= size.max()) { assignedTeams.put(u, idx); used.add(idx); }
            }
        }
        for (UUID u : new ArrayList<>(players)) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            int idx = assignedTeams.getOrDefault(u, 1);
            Location tl = teamSpawns.get(idx);
            if (tl == null) tl = waitingSpawn != null ? waitingSpawn : plugin.lobbySpawn();
            p.teleport(tl);
        }
    }

    private int assignTeamIndexFor(UUID u) {
        Integer cur = assignedTeams.get(u);
        if (cur != null) return cur;
        java.util.HashSet<Integer> used = new java.util.HashSet<>(assignedTeams.values());
        int idx = 1;
        while (used.contains(idx) && idx <= size.max()) idx++;
        assignedTeams.put(u, idx);
        return idx;
    }

    private void rebuildWaitingCages() {
        clearCages();
        for (UUID u : new ArrayList<>(players)) {
            Player pl = Bukkit.getPlayer(u);
            if (pl == null) continue;
            int idx = assignedTeams.getOrDefault(u, assignTeamIndexFor(u));
            Location tl = teamSpawns.get(idx);
            if (tl == null) tl = waitingSpawn != null ? waitingSpawn : plugin.lobbySpawn();
            Location above = centerOfBlock(tl, 5);
            // Подтянем игрока точно в центр клетки, чтобы не "вылетал"
            try { pl.teleport(above); } catch (Exception ignored) {}
            buildCageAt(above);
        }
    }

    private Location centerOfBlock(Location base, int yOffset) {
        if (base == null || base.getWorld() == null) return base;
        int bx = base.getBlockX();
        int by = base.getBlockY() + yOffset;
        int bz = base.getBlockZ();
        return new Location(base.getWorld(), bx + 0.5, by, bz + 0.5, base.getYaw(), base.getPitch());
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
        // Время идёт сразу с момента старта игры (без задержки)
        int effectiveElapsed = state == GameState.INGAME ? Math.max(0, rawElapsed) : 0;
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
                // %balance% из Vault, если доступен
                try {
                    Player up = Bukkit.getPlayer(u);
                    if (up != null) {
                        double bal = getPlayerBalance(up);
                        line = line.replace("%balance%", String.valueOf((long) bal));
                    }
                } catch (Throwable ignored) { line = line.replace("%balance%", "0"); }
                while (used.contains(line)) line = line + ChatColor.RESET;
                used.add(line);
                addLine(obj, line, score--);
            }
        }
    }

    private void addLine(Objective obj, String text, int score) { obj.getScore(text).setScore(score); }

    // Получение баланса игрока через Vault (если подключено)
    private double getPlayerBalance(Player p) {
        try {
            SantaPillars pl = this.plugin;
            java.lang.reflect.Field ecoF = SantaPillars.class.getDeclaredField("economy");
            ecoF.setAccessible(true);
            Object economy = ecoF.get(pl);
            if (economy == null || p == null) return 0.0;
            // Предпочитаем OfflinePlayer API
            try {
                java.lang.reflect.Method m = economy.getClass().getMethod("getBalance", org.bukkit.OfflinePlayer.class);
                Object res = m.invoke(economy, (org.bukkit.OfflinePlayer) p);
                if (res instanceof Number n) return n.doubleValue();
            } catch (NoSuchMethodException ignored) {}
            try {
                java.lang.reflect.Method m2 = economy.getClass().getMethod("getBalance", String.class);
                Object res = m2.invoke(economy, p.getName());
                if (res instanceof Number n) return n.doubleValue();
            } catch (NoSuchMethodException ignored) {}
        } catch (Throwable ignored) {}
        return 0.0;
    }

    public void setTeamSpawn(int idx, Location loc) {
        int max = size.max();
        if (idx < 1 || idx > max) {
            return; // проверка производится на уровне команды, тут просто игнор
        }
        teamSpawns.put(idx, loc);
    }

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
        String worldName = sec.getString("world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            try {
                if (org.jlakctep.santaPillars.util.WorldUtil.worldFolderExists(worldName)) {
                    WorldCreator wc = new WorldCreator(worldName).generator(new VoidGenerator());
                    world = Bukkit.createWorld(wc);
                }
            } catch (Throwable ignored) {}
        }
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
