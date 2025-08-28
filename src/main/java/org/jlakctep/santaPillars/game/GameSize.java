package org.jlakctep.santaPillars.game;

public enum GameSize {
    S2("1x1", 2),
    S4("1x4", 4),
    S8("1x8", 8);

    private final String key;
    private final int maxPlayers;

    GameSize(String key, int maxPlayers) { this.key = key; this.maxPlayers = maxPlayers; }

    public String key() { return key; }
    public int max() { return maxPlayers; }

    public static GameSize fromKey(String s) {
        for (var g : values()) if (g.key.equalsIgnoreCase(s)) return g;
        return null;
    }
}