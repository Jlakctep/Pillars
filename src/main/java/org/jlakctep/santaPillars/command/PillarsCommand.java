package org.jlakctep.santaPillars.command;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jlakctep.santaPillars.SantaPillars;
import org.jlakctep.santaPillars.game.Arena;
import org.jlakctep.santaPillars.game.GameSize;
import org.jlakctep.santaPillars.util.Msg;
import org.jlakctep.santaPillars.util.WorldUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PillarsCommand implements CommandExecutor, TabCompleter {

    private final SantaPillars plugin;

    public PillarsCommand(SantaPillars plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        String invoked = cmd.getName().toLowerCase(Locale.ROOT);
        if (invoked.equals("pillars") && args.length == 0) {
            s.sendMessage("""
                    Команды Pillars:
                    /join <1x16|1x4|1x8>         - присоединиться к свободной арене режима
                    /leave                        - покинуть арену/наблюдение и вернуться в лобби
                    /forcestart                   - принудительно запустить игру (если вы в арене)
                    /spectate [ник]               - наблюдать: телепорт к цели (из лобби)
                    /lobby                        - телепорт в лобби
                    /setlobby                     - сохранить текущую позицию как лобби (админ)

                    Редактирование арен (админ):
                    /arena create <id> [1x16|1x4|1x8] - создать арену, войти в редактор
                    /arena edit <id>                - войти в редактор существующей арены
                    /arena list                     - список арен
                    /editleave                      - сохранить и выйти из редактора
                    /arenaremove <id>               - удалить арену и её мир (когда не активна)
                    /team set <1..N>                - проставить спавн команды по номеру (в редакторе)
                    /floor                          - (пока пусто)

                    NPC (админ):
                    /npc create <id> <1x16|1x4|1x8> [type] - создать NPC этого режима
                    /npc remove <id>                       - удалить NPC
                    """);
            return true;
        }

        // Прямые команды-синонимы согласно новой схеме
        if (!invoked.equals("pillars")) {
            // Особая обработка для team set <id>
            if (invoked.equals("team")) {
                if (!(s instanceof Player p)) return true;
                if (!has(s, "pillars.team")) { s.sendMessage("Нет прав."); return true; }
                String editId = plugin.arenas().editingOf(p.getUniqueId());
                if (editId == null) { s.sendMessage("Вы не в режиме редактирования."); return true; }
                Arena a = plugin.arenas().get(editId);
                if (a == null) { s.sendMessage("Арена не найдена."); return true; }
                if (args.length < 2 || !args[0].equalsIgnoreCase("set")) { s.sendMessage("Использование: /team set <1..N>"); return true; }
                int idx;
                try { idx = Integer.parseInt(args[1]); } catch (NumberFormatException ex) { s.sendMessage("Использование: /team set <1..N>"); return true; }
                int max = a.size.max();
                if (idx < 1 || idx > max) { s.sendMessage(Msg.tr("team-limit").replace("%max%", String.valueOf(max))); return true; }
                a.setTeamSpawn(idx, p.getLocation().clone());
                s.sendMessage(Msg.prefix() + Msg.tr("team-set").replace("%team%", String.valueOf(idx)));
                return true;
            }

            // setlobby — это сокращение lobby set
            if (invoked.equals("setlobby")) {
                if (!(s instanceof Player p)) return true;
                if (!has(s, "pillars.lobby.set")) { s.sendMessage("Нет прав."); return true; }
                var loc = p.getLocation();
                var c = plugin.getConfig();
                c.set("lobby.world", loc.getWorld().getName());
                c.set("lobby.x", loc.getX());
                c.set("lobby.y", loc.getY());
                c.set("lobby.z", loc.getZ());
                c.set("lobby.yaw", (double) loc.getYaw());
                c.set("lobby.pitch", (double) loc.getPitch());
                plugin.saveConfig();
                s.sendMessage(org.jlakctep.santaPillars.util.Msg.prefix() + "Лобби установлено.");
                return true;
            }

            // Нормализуем в подкоманды прежнего роутера
            List<String> list = new ArrayList<>();
            switch (invoked) {
                case "join" -> { list.add("join"); for (String a : args) list.add(a); }
                case "leave" -> list.add("leave");
                case "lobby" -> list.add("lobby");
                case "forcestart" -> list.add("start");
                case "arena" -> { if (args.length == 0) { s.sendMessage("Использование: /arena <create|edit> ..."); return true; } for (String a : args) list.add(a); }
                case "floor" -> list.add("floor");
                case "editleave" -> list.add("editleave");
                case "arenaremove" -> { list.add("arenaremove"); for (String a : args) list.add(a); }
                case "npc" -> { list.add("npc"); for (String a : args) list.add(a); }
                case "config" -> { list.add("config"); for (String a : args) list.add(a); }
                case "spectate" -> { list.add("spectate"); for (String a : args) list.add(a); }
                case "perms" -> { list.add("perms"); for (String a : args) list.add(a); }
                // join random будет обработан внутри блока join
                default -> { s.sendMessage("Неизвестная команда. /pillars"); return true; }
            }
            args = list.toArray(new String[0]);
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "join" -> {
                if (!(s instanceof Player p)) return true;
                if (!has(s, "pillars.join")) { s.sendMessage("Нет прав."); return true; }
                if (args.length >= 2 && args[1].equalsIgnoreCase("random")) {
                    var any = plugin.arenas().findAnyJoinable();
                    if (any != null) {
                        any.addPlayer(p);
                        p.sendMessage(Msg.prefix() + Msg.tr("join-waiting").replace("%arena%", any.id).replace("%size%", any.size.key()));
                    } else {
                        p.sendMessage(Msg.prefix() + Msg.tr("no-arenas").replace("%size%", "любой"));
                    }
                    return true;
                }
                if (args.length < 2) { s.sendMessage("Укажи режим: 1x16/1x4/1x8 или random"); return true; }
                GameSize size = GameSize.fromKey(args[1]);
                if (size == null) { s.sendMessage("Неизвестный режим."); return true; }
                var a = plugin.arenas().findJoinable(size);
                if (a == null) { s.sendMessage(Msg.tr("no-arenas").replace("%size%", size.key())); return true; }
                a.addPlayer(p);
                p.sendMessage(Msg.prefix() + Msg.tr("join-waiting").replace("%arena%", a.id).replace("%size%", size.key()));
            }

            case "arenaremove" -> {
                if (!has(s, "pillars.arenaremove")) { s.sendMessage("Нет прав."); return true; }
                if (args.length < 2) { s.sendMessage("Использование: /pillars arenaremove <id>"); return true; }
                String id = args[1];
                var a = plugin.arenas().get(id);
                if (a == null) { s.sendMessage("Арена не найдена."); return true; }
                // Нельзя удалить, если есть игроки
                if (a.getState() != org.jlakctep.santaPillars.game.GameState.WAITING) { s.sendMessage("Нельзя удалить: арена активна."); return true; }
                // Удаляем из менеджера и сохраняем конфиг
                plugin.arenas().removeArena(id);
                // Удаляем мир и бэкап
                String worldName = a.world.getName();
                org.jlakctep.santaPillars.util.WorldUtil.deleteWorldFolder(plugin, worldName);
                org.jlakctep.santaPillars.util.WorldUtil.deleteBackup(plugin, plugin.worldsDir(), id);
                plugin.saveArenas();
                s.sendMessage(Msg.prefix() + "Арена удалена: " + id);
            }

            case "list" -> {
                // Список арен (без прав)
                java.util.List<org.jlakctep.santaPillars.game.Arena> list = new java.util.ArrayList<>(plugin.arenas().all());
                if (list.isEmpty()) { s.sendMessage("Арен нет."); return true; }
                s.sendMessage("Арены:");
                for (var ar : list) {
                    s.sendMessage("- " + ar.id + " [" + ar.size.key() + "] " + ar.getState() + " " + ar.playersCount() + "/" + ar.size.max());
                }
            }

            case "leave" -> {
                if (!(s instanceof Player p)) return true;
                if (!has(s, "pillars.leave")) { s.sendMessage("Нет прав."); return true; }
                var a = plugin.arenas().byPlayer(p.getUniqueId());
                if (a == null) {
                    // Не в арене — возможно, игрок наблюдает
                    String watchedId = plugin.arenas().spectatingArenaOf(p.getUniqueId());
                    if (watchedId != null || plugin.arenas().isGlobalSpectator(p.getUniqueId())) {
                        plugin.arenas().stopSpectating(p.getUniqueId());
                        p.getInventory().clear();
                        p.setGameMode(org.bukkit.GameMode.ADVENTURE);
                        p.removePotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
                        p.setAllowFlight(false);
                        p.setFlying(false);
                        p.teleport(plugin.lobbySpawn());
                        // Вернуть предмет рандом-игры в слот 4
                        try {
                            org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(org.bukkit.Material.CLOCK);
                            var im = it.getItemMeta();
                            if (im != null) {
                                im.displayName(net.kyori.adventure.text.Component.text("Рандомная игра").color(net.kyori.adventure.text.format.NamedTextColor.GOLD).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                                it.setItemMeta(im);
                            }
                            p.getInventory().setItem(4, it);
                        } catch (Exception ignored) {}
                        p.sendMessage(Msg.prefix() + Msg.tr("leave"));
                        return true;
                    }
                    p.sendMessage(Msg.tr("not-in"));
                    return true;
                }
                a.removePlayer(p.getUniqueId(), false);
                p.sendMessage(Msg.prefix() + Msg.tr("leave"));
            }


            case "start" -> {
                if (!(s instanceof Player p)) return true;
                if (!has(s, "pillars.start")) { p.sendMessage("Нет прав."); return true; }
                var a = plugin.arenas().byPlayer(p.getUniqueId());
                if (a == null) { p.sendMessage(Msg.tr("not-in")); return true; }
                a.forceStart();
                p.sendMessage(Msg.prefix() + Msg.tr("force-start"));
            }

            case "spectate" -> {
                if (!(s instanceof Player p)) return true;
                if (!has(s, "pillars.spectate")) { p.sendMessage("Нет прав."); return true; }
                var a = plugin.arenas().byPlayer(p.getUniqueId());
                if (a != null) { p.sendMessage("Эта команда доступна только в лобби."); return true; }
                // Если указан ник: телепорт к живому игроку
                if (args.length >= 2) {
                    var target = org.bukkit.Bukkit.getPlayerExact(args[1]);
                    if (target == null) { p.sendMessage("Игрок не найден."); return true; }
                    // Убедимся, что цель в живых и в арене
                    var arena = plugin.arenas().byPlayer(target.getUniqueId());
                    if (arena == null || arena.getState() != org.jlakctep.santaPillars.game.GameState.INGAME) { p.sendMessage("Игрок сейчас не в игре."); return true; }
                    p.setGameMode(org.bukkit.GameMode.SPECTATOR);
                    // Спавним ровно на точке ожидания/лобби
                    org.bukkit.Location base = (arena.waitingSpawn != null ? arena.waitingSpawn : plugin.lobbySpawn());
                    p.teleport(base);
                    p.sendMessage("Вы наблюдаете за " + target.getName());
                    var arenaId = plugin.arenas().byPlayer(target.getUniqueId()).id;
                    plugin.arenas().startSpectating(p.getUniqueId(), arenaId);
                    return true;
                }
                // Без ника: показать подсказку по использованию
                p.sendMessage("Использование: /pillars spectate <ник игрока>");
                return true;
            }

            case "lobby" -> {
                if (!(s instanceof Player p)) return true;
                if (!has(s, "pillars.lobby")) { s.sendMessage("Нет прав."); return true; }
                if (args.length >= 1 && args.length <= 2 && (args.length == 1 || args[1].equalsIgnoreCase("set"))) {
                    if (args.length == 2) {
                        if (!has(s, "pillars.lobby.set")) { s.sendMessage("Нет прав."); return true; }
                        var loc = p.getLocation();
                        var c = plugin.getConfig();
                        c.set("lobby.world", loc.getWorld().getName());
                        c.set("lobby.x", loc.getX());
                        c.set("lobby.y", loc.getY());
                        c.set("lobby.z", loc.getZ());
                        c.set("lobby.yaw", (double) loc.getYaw());
                        c.set("lobby.pitch", (double) loc.getPitch());
                        plugin.saveConfig();
                        s.sendMessage(org.jlakctep.santaPillars.util.Msg.prefix() + "Лобби установлено.");
                    } else {
                        p.teleport(plugin.lobbySpawn());
                    }
                } else {
                    s.sendMessage("Использование: /pillars lobby [set]");
                }
            }

            case "create" -> {
                if (!(s instanceof Player p)) return true;
                if (!has(s, "pillars.create")) { s.sendMessage("Нет прав."); return true; }
                if (args.length < 2) { s.sendMessage("Использование: /pillars create <id> [1x16|1x4|1x8]"); return true; }
                String id = args[1];
                if (plugin.arenas().get(id) != null) { s.sendMessage("Арена '" + id + "' уже существует."); return true; }

                GameSize size = args.length >= 3 ? GameSize.fromKey(args[2]) : GameSize.S4;
                if (size == null) size = GameSize.S4;

                String worldName = "sp_" + id;
                if (Bukkit.getWorld(worldName) != null) { s.sendMessage("Мир уже загружен: " + worldName); return true; }
                // Если папка есть (после рестарта) — пытаемся загрузить как мир вместо отказа
                if (WorldUtil.worldFolderExists(worldName)) {
                    org.bukkit.WorldCreator wc = new org.bukkit.WorldCreator(worldName).generator(new org.jlakctep.santaPillars.util.VoidGenerator());
                    World w = org.bukkit.Bukkit.createWorld(wc);
                    if (w == null) { s.sendMessage("Не удалось загрузить мир из папки: " + worldName); return true; }
                    Arena a = plugin.arenas().create(id, size, w);
                    a.waitingSpawn = w.getSpawnLocation();
                    plugin.arenas().beginEdit(p.getUniqueId(), id);
                    p.teleport(w.getSpawnLocation());
                    p.setGameMode(org.bukkit.GameMode.CREATIVE);
                    try { p.getInventory().setItem(4, null); } catch (Exception ignored) {}
                    p.sendMessage(Msg.prefix() + Msg.tr("arena-created").replace("%id%", id).replace("%world%", worldName) + " (загружено из папки)");
                    return true;
                }

                World w = WorldUtil.createArenaWorld(plugin, worldName);
                Arena a = plugin.arenas().create(id, size, w);
                a.waitingSpawn = w.getSpawnLocation();

                plugin.arenas().beginEdit(p.getUniqueId(), id);
                p.teleport(w.getSpawnLocation());
                p.setGameMode(org.bukkit.GameMode.CREATIVE);
                // Убираем предмет лобби "Рандомная игра" на время редактирования
                try {
                    p.getInventory().setItem(4, null); // Рандомная игра
                } catch (Exception ignored) {}
                p.sendMessage(Msg.prefix() + Msg.tr("arena-created")
                        .replace("%id%", id).replace("%world%", worldName));
            }

            case "edit" -> {
                if (!(s instanceof Player p)) return true;
                if (!has(s, "pillars.edit")) { s.sendMessage("Нет прав."); return true; }
                if (args.length < 2) { s.sendMessage("Использование: /pillars edit <id>"); return true; }
                String id = args[1];
                Arena a = plugin.arenas().get(id);
                if (a == null) { s.sendMessage("Арена не найдена."); return true; }
                plugin.arenas().beginEdit(p.getUniqueId(), id);
                if (a.waitingSpawn != null) p.teleport(a.waitingSpawn);
                else p.teleport(a.world.getSpawnLocation());
                p.setGameMode(org.bukkit.GameMode.CREATIVE);
                // Убираем предмет лобби "Рандомная игра" на время редактирования
                try {
                    p.getInventory().setItem(4, null); // Рандомная игра
                } catch (Exception ignored) {}
                s.sendMessage(Msg.prefix() + "Режим редактирования: " + id);
            }

            case "editleave" -> {
                if (!(s instanceof Player p)) return true;
                if (!has(s, "pillars.editleave")) { s.sendMessage("Нет прав."); return true; }
                String editId = plugin.arenas().editingOf(p.getUniqueId());
                if (editId == null) { p.sendMessage("Вы не в режиме редактирования."); return true; }
                Arena a = plugin.arenas().get(editId);
                if (a == null) { p.sendMessage("Арена не найдена."); return true; }
                World w = a.world;

                WorldUtil.saveAndBackupWorld(plugin, w, plugin.worldsDir(), editId);
                plugin.arenas().endEdit(p.getUniqueId());
                plugin.saveArenas();
                p.teleport(plugin.lobbySpawn());
                p.setGameMode(org.bukkit.GameMode.SURVIVAL);
                // Вернём предмет лобби (рандом-игра)
                try {
                    org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(org.bukkit.Material.CLOCK);
                    var im = it.getItemMeta();
                    if (im != null) {
                        im.displayName(net.kyori.adventure.text.Component.text("Рандомная игра").color(net.kyori.adventure.text.format.NamedTextColor.GOLD).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                        it.setItemMeta(im);
                    }
                    p.getInventory().setItem(4, it);
                } catch (Exception ignored) {}
                p.sendMessage(Msg.prefix() + Msg.tr("edit-saved").replace("%id%", editId));
            }

            case "floor" -> {
                s.sendMessage("Команда в разработке.");
            }

            case "npc" -> {
                if (args.length < 2) { s.sendMessage("/pillars npc create|remove ..."); return true; }
                switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "create" -> {
                        if (!(s instanceof Player)) return true;
                        if (!has(s, "pillars.npc.create")) { s.sendMessage("Нет прав."); return true; }
                        if (args.length < 4) { s.sendMessage("Исп: /pillars npc create <id> <1x16|1x4|1x8> [type]"); return true; }
                        String id = args[2];
                        GameSize size = GameSize.fromKey(args[3]);
                        if (size == null) { s.sendMessage("Режим неверен."); return true; }
                        EntityType type = EntityType.VILLAGER;
                        if (args.length >= 5) {
                            try { type = EntityType.valueOf(args[4].toUpperCase(Locale.ROOT)); } catch (Exception ignored) {}
                        }
                        boolean ok = plugin.npcs().createNPC(id, size, ((Player)s).getLocation(), type);
                        if (ok) s.sendMessage(Msg.prefix() + Msg.tr("npc-created").replace("%size%", size.key()));
                        plugin.saveNPCs();
                    }
                    case "remove" -> {
                        if (!has(s, "pillars.npc.remove")) { s.sendMessage("Нет прав."); return true; }
                        if (args.length < 3) { s.sendMessage("Исп: /pillars npc remove <id>"); return true; }
                        if (plugin.npcs().removeNPC(args[2])) s.sendMessage(Msg.prefix() + Msg.tr("npc-removed"));
                        plugin.saveNPCs();
                    }
                    default -> s.sendMessage("/pillars npc create|remove ...");
                }
            }

            case "config" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("reload")) {
                    if (!has(s, "pillars.admin")) { s.sendMessage("Нет прав."); return true; }
                    // Перезагрузка конфигов: config.yml, messages.yml, arenas.yml, npcs.yml
                    plugin.reloadConfig();
                    org.jlakctep.santaPillars.util.Msg.load(plugin);
                    plugin.arenas().shutdown(); // остановить циклы
                    // перечитать arenas.yml
                    try {
                        java.io.File f = new java.io.File(plugin.getDataFolder(), "arenas.yml");
                        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
                        plugin.arenas().load(yaml);
                    } catch (Exception ex) { s.sendMessage("Ошибка чтения arenas.yml"); }
                    // перечитать npcs.yml + пересоздать NPC
                    try {
                        java.io.File f2 = new java.io.File(plugin.getDataFolder(), "npcs.yml");
                        var yaml2 = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f2);
                        plugin.npcs().loadAndSpawn(yaml2);
                    } catch (Exception ex) { s.sendMessage("Ошибка чтения npcs.yml"); }
                    s.sendMessage(org.jlakctep.santaPillars.util.Msg.prefix() + org.jlakctep.santaPillars.util.Msg.tr("reloaded"));
                    return true;
                }
                s.sendMessage("Использование: /pillars config reload");
            }

            case "perms" -> {
                // /pillars perms <player> <default|donate|admin> [remove]
                if (!has(s, "pillars.admin")) { s.sendMessage("Нет прав."); return true; }
                if (args.length < 3) { s.sendMessage("Использование: /pillars perms <ник> <default|donate|admin> [remove]"); return true; }
                String target = args[1];
                String pack = args[2].toLowerCase(Locale.ROOT);
                boolean remove = (args.length >= 4) && args[3].equalsIgnoreCase("remove");
                java.util.List<String> nodes = plugin.getConfig().getStringList("permission_sets." + pack);
                if (nodes == null || nodes.isEmpty()) { s.sendMessage("Набор прав не найден: " + pack); return true; }
                if (org.bukkit.Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                    s.sendMessage("LuckPerms не установлен. Установите его, либо выдавайте права вручную.");
                    return true;
                }
                int ok = 0;
                for (String node : nodes) {
                    String cmdLine = remove
                            ? ("lp user " + target + " permission unset " + node)
                            : ("lp user " + target + " permission set " + node + " true");
                    boolean res = org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), cmdLine);
                    if (res) ok++;
                }
                s.sendMessage(Msg.prefix() + "Пакет '" + pack + "' " + (remove ? "снят" : "выдан") + ": " + ok + "/" + nodes.size());
            }

            default -> {
                if (args[0].toLowerCase(Locale.ROOT).startsWith("team")) {
                    if (!(s instanceof Player p)) return true;
                    if (!has(s, "pillars.team")) { s.sendMessage("Нет прав."); return true; }
                    String editId = plugin.arenas().editingOf(p.getUniqueId());
                    if (editId == null) { s.sendMessage("Вы не в режиме редактирования."); return true; }
                    Arena a = plugin.arenas().get(editId);
                    if (a == null) { s.sendMessage("Арена не найдена."); return true; }

                    String suffix = args[0].substring(4);
                    try {
                        int idx = Integer.parseInt(suffix);
                        int max = a.size.max();
                        if (idx < 1 || idx > max) { s.sendMessage(Msg.tr("team-limit").replace("%max%", String.valueOf(max))); return true; }
                        a.setTeamSpawn(idx, p.getLocation().clone());
                        s.sendMessage(Msg.prefix() + Msg.tr("team-set").replace("%team%", String.valueOf(idx)));
                    } catch (NumberFormatException ex) {
                        s.sendMessage("Использование: /team set <1..N>");
                    }
                } else {
                    s.sendMessage("Неизвестная команда. /pillars");
                }
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        List<String> out = new ArrayList<>();
        String invoked = c.getName().toLowerCase(Locale.ROOT);
        if (invoked.equals("pillars")) {
            if (args.length == 1) {
                out.add("join"); out.add("leave"); out.add("start");
                out.add("create"); out.add("edit"); out.add("editleave"); out.add("floor");
                out.add("npc");
                out.add("team1"); out.add("team2"); out.add("team3"); out.add("team4");
                out.add("team5"); out.add("team6"); out.add("team7"); out.add("team8");
            } else if (args.length == 2 && args[0].equalsIgnoreCase("join")) {
                out.add("1x16"); out.add("1x4"); out.add("1x8");
            } else if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
                out.add("arena1");
            } else if (args.length == 2 && args[0].equalsIgnoreCase("edit")) {
                for (var arena : plugin.arenas().all()) out.add(arena.id);
            } else if (args.length == 2 && args[0].equalsIgnoreCase("arenaremove")) {
                for (var arena : plugin.arenas().all()) out.add(arena.id);
            } else if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
                out.add("1x16"); out.add("1x4"); out.add("1x8");
            } else if (args.length == 2 && args[0].equalsIgnoreCase("npc")) {
                out.add("create"); out.add("remove");
            } else if (args.length == 4 && args[0].equalsIgnoreCase("npc") && args[1].equalsIgnoreCase("create")) {
                out.add("1x16"); out.add("1x4"); out.add("1x8");
            }
            return out;
        }

        switch (invoked) {
            case "join" -> { if (args.length == 1) { out.add("1x16"); out.add("1x4"); out.add("1x8"); } }
            case "arena" -> {
                if (args.length == 1) { out.add("create"); out.add("edit"); out.add("list"); }
                else if (args.length == 2 && args[0].equalsIgnoreCase("edit")) { for (var arena : plugin.arenas().all()) out.add(arena.id); }
                else if (args.length == 2 && args[0].equalsIgnoreCase("create")) { out.add("arena1"); }
                else if (args.length == 3 && args[0].equalsIgnoreCase("create")) { out.add("1x16"); out.add("1x4"); out.add("1x8"); }
            }
            case "npc" -> {
                if (args.length == 1) { out.add("create"); out.add("remove"); }
                else if (args.length == 3 && args[0].equalsIgnoreCase("create")) { out.add("1x16"); out.add("1x4"); out.add("1x8"); }
            }
            case "team" -> {
                if (args.length == 1) { out.add("set"); }
                else if (args.length == 2 && args[0].equalsIgnoreCase("set")) { out.add("1"); out.add("2"); out.add("3"); out.add("4"); }
            }
            case "arenaremove" -> { if (args.length == 1) { for (var arena : plugin.arenas().all()) out.add(arena.id); } }
            default -> {}
        }
        return out;
    }

    private boolean has(CommandSender s, String perm) {
        return s.hasPermission(perm) || s.hasPermission("pillars.admin");
    }
}