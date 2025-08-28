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
        if (args.length == 0) {
            s.sendMessage("""
                    Команды Pillars:
                    /pillars join <1x1|1x4|1x8>  - присоединиться к свободной арене режима
                    /pillars leave                - покинуть арену/наблюдение и вернуться в лобби
                    /pillars start                - принудительно запустить игру (если вы в арене)
                    /pillars spectate [ник]      - наблюдать: телепорт к цели или открыть навигатор (из лобби)
                    /pillars lobby               - телепорт в лобби
                    /pillars lobby set           - сохранить текущую позицию как лобби (админ)

                    Редактирование арен (админ):
                    /pillars create <id> [1x1|1x4|1x8] - создать арену, войти в редактор
                    /pillars edit <id>                  - войти в редактор существующей арены
                    /pillars addspawn                   - задать точку ожидания (в редакторе)
                    /pillars editleave                  - сохранить и выйти из редактора
                    /pillars arenaremove <id>           - удалить арену и её мир (когда не активна)
                    team1..team8                        - проставить спавны команд 1..8 (в редакторе)

                    NPC (админ):
                    /pillars npc create <id> <1x1|1x4|1x8> [type] - создать NPC этого режима
                    /pillars npc remove <id>                       - удалить NPC
                    """);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "join" -> {
                if (!(s instanceof Player p)) return true;
                if (!has(s, "pillars.join")) { s.sendMessage("Нет прав."); return true; }
                if (args.length < 2) { s.sendMessage("Укажи режим: 1x1/1x4/1x8"); return true; }
                GameSize size = GameSize.fromKey(args[1]);
                if (size == null) { s.sendMessage("Неизвестный режим."); return true; }
                var a = plugin.arenas().findJoinable(size);
                if (a == null) { s.sendMessage(Msg.tr("no-arenas").replace("%size%", size.key())); return true; }
                a.addPlayer(p);
                p.sendMessage(Msg.prefix() + Msg.tr("join-waiting")
                        .replace("%arena%", a.id)
                        .replace("%size%", size.key()));
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
                                im.displayName(net.kyori.adventure.text.Component.text("Рандомная игра").color(net.kyori.adventure.text.format.NamedTextColor.GOLD));
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
                    // Спавним на 1 блок выше точки ожидания арены
                    org.bukkit.Location base = (arena.waitingSpawn != null ? arena.waitingSpawn : plugin.lobbySpawn()).clone().add(0, 1, 0);
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
                if (args.length < 2) { s.sendMessage("Использование: /pillars create <id> [1x1|1x4|1x8]"); return true; }
                String id = args[1];
                if (plugin.arenas().get(id) != null) { s.sendMessage("Арена '" + id + "' уже существует."); return true; }

                GameSize size = args.length >= 3 ? GameSize.fromKey(args[2]) : GameSize.S4;
                if (size == null) size = GameSize.S4;

                String worldName = "sp_" + id;
                if (Bukkit.getWorld(worldName) != null) { s.sendMessage("Мир уже загружен: " + worldName); return true; }
                if (WorldUtil.worldFolderExists(worldName)) { s.sendMessage("Папка мира существует: " + worldName); return true; }

                World w = WorldUtil.createArenaWorld(plugin, worldName);
                Arena a = plugin.arenas().create(id, size, w);
                a.waitingSpawn = w.getSpawnLocation();

                plugin.arenas().beginEdit(p.getUniqueId(), id);
                p.teleport(w.getSpawnLocation());
                p.setGameMode(org.bukkit.GameMode.CREATIVE);
                // Убираем предмет лобби "Рандомная игра" на время редактирования
                try { p.getInventory().setItem(4, null); } catch (Exception ignored) {}
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
                try { p.getInventory().setItem(4, null); } catch (Exception ignored) {}
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
                // Вернём предмет "Рандомная игра" в слот 4
                try {
                    org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(org.bukkit.Material.CLOCK);
                    var im = it.getItemMeta();
                    if (im != null) {
                        im.displayName(net.kyori.adventure.text.Component.text("Рандомная игра").color(net.kyori.adventure.text.format.NamedTextColor.GOLD));
                        it.setItemMeta(im);
                    }
                    p.getInventory().setItem(4, it);
                } catch (Exception ignored) {}
                p.sendMessage(Msg.prefix() + Msg.tr("edit-saved").replace("%id%", editId));
            }

            case "addspawn" -> {
                if (!(s instanceof Player p)) return true;
                if (!has(s, "pillars.addspawn")) { s.sendMessage("Нет прав."); return true; }
                String editId = plugin.arenas().editingOf(p.getUniqueId());
                if (editId == null) { s.sendMessage("Вы не в режиме редактирования."); return true; }
                Arena a = plugin.arenas().get(editId);
                if (a == null) { s.sendMessage("Арена не найдена."); return true; }
                a.waitingSpawn = p.getLocation().clone();
                s.sendMessage(Msg.prefix() + Msg.tr("waiting-set"));
            }

            case "npc" -> {
                if (args.length < 2) { s.sendMessage("/pillars npc create|remove ..."); return true; }
                switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "create" -> {
                        if (!(s instanceof Player)) return true;
                        if (!has(s, "pillars.npc.create")) { s.sendMessage("Нет прав."); return true; }
                        if (args.length < 4) { s.sendMessage("Исп: /pillars npc create <id> <1x1|1x4|1x8> [type]"); return true; }
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
                        if (idx < 1 || idx > 8) { s.sendMessage("teamN: N = 1..8"); return true; }
                        a.setTeamSpawn(idx, p.getLocation().clone());
                        s.sendMessage(Msg.prefix() + Msg.tr("team-set").replace("%team%", String.valueOf(idx)));
                    } catch (NumberFormatException ex) {
                        s.sendMessage("Использование: /pillars team1 .. team8");
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
        if (args.length == 1) {
            out.add("join"); out.add("leave"); out.add("start");
            out.add("create"); out.add("edit"); out.add("addspawn"); out.add("editleave");
            out.add("npc");
            out.add("team1"); out.add("team2"); out.add("team3"); out.add("team4");
            out.add("team5"); out.add("team6"); out.add("team7"); out.add("team8");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("join")) {
            out.add("1x1"); out.add("1x4"); out.add("1x8");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            out.add("arena1");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("edit")) {
            for (var arena : plugin.arenas().all()) out.add(arena.id);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("arenaremove")) {
            for (var arena : plugin.arenas().all()) out.add(arena.id);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            out.add("1x1"); out.add("1x4"); out.add("1x8");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("npc")) {
            out.add("create"); out.add("remove");
        } else if (args.length == 4 && args[0].equalsIgnoreCase("npc") && args[1].equalsIgnoreCase("create")) {
            out.add("1x1"); out.add("1x4"); out.add("1x8");
        }
        return out;
    }

    private boolean has(CommandSender s, String perm) {
        return s.hasPermission(perm) || s.hasPermission("pillars.admin");
    }
}