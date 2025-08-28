package org.jlakctep.santaPillars.util;

import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;

public class WorldUtil {

    private static final List<String> IGNORE = Arrays.asList("uid.dat", "session.lock");

    public static boolean worldFolderExists(String worldName) {
        File container = Bukkit.getWorldContainer();
        File f = new File(container, worldName);
        return f.exists();
    }

    public static World createArenaWorld(JavaPlugin plugin, String worldName) {
        WorldCreator wc = new WorldCreator(worldName)
                .environment(World.Environment.NORMAL)
                .type(WorldType.NORMAL)
                .generator(new VoidGenerator());
        World w = Bukkit.createWorld(wc);
        if (w == null) throw new IllegalStateException("Не удалось создать мир " + worldName);

        // Настройки мира и стартовая площадка
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);

        w.getChunkAt(0, 0).load();
        w.getBlockAt(0, 0, 0).setType(Material.BEDROCK, false);
        Location spawn = new Location(w, 0.5, 1.0, 0.5, 0, 0);
        w.setSpawnLocation(spawn);

        return w;
    }

    public static void saveAndBackupWorld(JavaPlugin plugin, World world, File pluginWorldsDir, String id) {
        world.save();
        File src = world.getWorldFolder();
        File dst = new File(pluginWorldsDir, id);
        if (!dst.exists()) dst.mkdirs();
        try {
            copyDirectory(src.toPath(), dst.toPath());
        } catch (IOException e) {
            plugin.getLogger().severe("Ошибка бэкапа мира " + world.getName() + ": " + e.getMessage());
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        if (!Files.exists(target)) Files.createDirectories(target);
        Files.walk(source).forEach(path -> {
            Path rel = source.relativize(path);
            if (rel.toString().isEmpty()) return;
            File f = path.toFile();
            if (f.isDirectory()) {
                try { Files.createDirectories(target.resolve(rel)); } catch (IOException ignored) {}
                return;
            }
            if (IGNORE.contains(f.getName())) return;
            try {
                Files.copy(path, target.resolve(rel), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            } catch (IOException ignored) {}
        });
    }

    public static boolean deleteWorldFolder(JavaPlugin plugin, String worldName) {
        try {
            Bukkit.unloadWorld(worldName, false);
        } catch (Throwable ignored) {}
        File container = Bukkit.getWorldContainer();
        File folder = new File(container, worldName);
        return deleteRecursively(folder.toPath());
    }

    public static boolean deleteBackup(JavaPlugin plugin, File pluginWorldsDir, String id) {
        File folder = new File(pluginWorldsDir, id);
        return deleteRecursively(folder.toPath());
    }

    private static boolean deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return false;
        try {
            Files.walk(path)
                    .sorted((a,b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}