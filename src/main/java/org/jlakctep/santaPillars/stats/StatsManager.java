package org.jlakctep.santaPillars.stats;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jlakctep.santaPillars.SantaPillars;

import java.io.File;
import java.util.UUID;

public class StatsManager {

	private final SantaPillars plugin;
	private final File statsFile;
	private YamlConfiguration cfg;

	public StatsManager(SantaPillars plugin) {
		this.plugin = plugin;
		this.statsFile = new File(plugin.getDataFolder(), "stats.yml");
	}

	public void load() {
		try {
			if (!statsFile.exists()) {
				statsFile.getParentFile().mkdirs();
				statsFile.createNewFile();
			}
			this.cfg = YamlConfiguration.loadConfiguration(statsFile);
		} catch (Exception e) {
			plugin.getLogger().warning("Failed to load stats.yml: " + e.getMessage());
			this.cfg = new YamlConfiguration();
		}
	}

	public void save() {
		try {
			if (cfg != null) cfg.save(statsFile);
		} catch (Exception e) {
			plugin.getLogger().warning("Failed to save stats.yml: " + e.getMessage());
		}
	}

	public int getKills(UUID uuid) {
		if (cfg == null || uuid == null) return 0;
		return cfg.getInt("players." + uuid + ".kills", 0);
	}

	public int getWins(UUID uuid) {
		if (cfg == null || uuid == null) return 0;
		return cfg.getInt("players." + uuid + ".wins", 0);
	}

	public void incrementKills(UUID uuid, int delta) {
		if (cfg == null || uuid == null || delta == 0) return;
		String base = "players." + uuid + ".kills";
		cfg.set(base, Math.max(0, cfg.getInt(base, 0) + delta));
	}

	public void incrementWins(UUID uuid, int delta) {
		if (cfg == null || uuid == null || delta == 0) return;
		String base = "players." + uuid + ".wins";
		cfg.set(base, Math.max(0, cfg.getInt(base, 0) + delta));
	}
}


