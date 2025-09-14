package org.jlakctep.santaPillars.papi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jlakctep.santaPillars.SantaPillars;

public class PillarsExpansion extends PlaceholderExpansion {

	private final SantaPillars plugin;

	public PillarsExpansion(SantaPillars plugin) { this.plugin = plugin; }

	@Override public String getIdentifier() { return "pillars"; }
	@Override public String getAuthor() { return plugin.getDescription().getAuthors().isEmpty() ? "unknown" : plugin.getDescription().getAuthors().get(0); }
	@Override public String getVersion() { return plugin.getDescription().getVersion(); }
	@Override public boolean persist() { return true; }

	@Override
	public String onRequest(OfflinePlayer player, String params) {
		if (params == null) return "";
		String p = params.toLowerCase();
		return switch (p) {
			case "kills" -> String.valueOf(player != null ? plugin.stats().getKills(player.getUniqueId()) : 0);
			case "wins" -> String.valueOf(player != null ? plugin.stats().getWins(player.getUniqueId()) : 0);
			// economy-related placeholders removed on request
			default -> "";
		};
	}

	// number formatter no longer used
}


