# pillars-jlakctep

Pillars — Pillars minigame for Paper/Bukkit
Lightweight arenas where players spawn, get random items, build, and fight. Includes waiting lobby, freeze start, time limit, kill tracking, Vault rewards, spectators, NPC menus, and fully configurable scoreboard/messages.

#Commands
pillars join <1x1|1x4|1x8>: Join an available arena of the given mode
pillars leave: Leave arena/spectate and return to lobby
pillars start: Force-start the current arena (if inside)
pillars spectate [name]: Spectate a player (or show usage if omitted)
pillars lobby: Teleport to lobby
pillars lobby set: Save current position as lobby (admin)
pillars create <id> [1x1|1x4|1x8]: Create arena and enter editor (admin)
pillars edit <id>: Enter arena editor (admin)
pillars addspawn: Save waiting lobby point (admin, in editor)
pillars editleave: Save arena, back it up, and leave editor (admin)
pillars arenaremove <id>: Remove arena and its world (when inactive) (admin)
pillars npc create <id> <1x1|1x4|1x8> [type]: Create NPC for the mode (admin)
pillars npc remove <id>: Remove NPC (admin)
pillars team1..team8: Set team spawns 1..8 (admin, in editor)
NPC right-click opens a GUI with mode-specific arenas and a “Random Game” button.
Permissions (LuckPerms)

#Players:
pillars.join: Join arena / NPC / GUI
pillars.leave: Leave arena
pillars.spectate: Spectate from lobby
pillars.lobby: Teleport to lobby
Special:
pillars.start: Force-start arena
Admin:
pillars.admin: All admin commands
pillars.lobby.set: Set lobby
pillars.create: Create arena
pillars.edit: Edit arena
pillars.editleave: Exit editor with save
pillars.addspawn: Set waiting spawn
pillars.team: Set team1..team8 spawns
pillars.arenaremove: Remove arena/world
pillars.npc.create: Create NPC
pillars.npc.remove: Remove NPC
Extras
Economy (Vault): rewards for win/lose/kills/top placements.
Scoreboard: separate templates for waiting and in-game; placeholders: %arena%, %mode%, %current%/%max%, %kills%, %timeleft%, %countdown%, etc.
All behavior and messages are configurable via config.yml and messages.yml.



