# GensCore plan

The core plugin for the gens server: one 25x25 plot per online player, a conveyor belt fed by gens, a gate lock, and
belt raiding. "GensCore" is a working name until the server has one (roadmap Phase 0: the name must not reuse another
game's name or characters).

GensCore started as a copy of PvPCore (`HXSMP-PvPCore`, 2026-11-19). PvPCore itself is unchanged and stays the HXSMP
server's core. GensCore keeps PvPCore's Folia-first base (scheduler, storage, player data, commands, menus) and its
general modules, drops the HCF and survival ones, and adds the gens modules phase by phase.

## Decisions so far

| Decision | Answer |
|---|---|
| Server software | **Folia and Paper**, same jar (`folia-supported: true`). Every rule in `PORTING-GUIDE.md` applies. |
| Database | **PostgreSQL** live, SQLite for local tests. The roadmap said MariaDB/MySQL; PostgreSQL was chosen because the storage layer already supports it and the network already runs it. |
| Project | A new copy, not a change to PvPCore and not a shared library. |
| Plot world | The server's **main world**, made void with a flat preset. Folia cannot create worlds while running. |

## Roadmap rules, and how the code meets them

1. **Money is calculated from data.** Belt entities will only be a picture of the data (Phase 2).
2. **No database work on game threads.** `PlayerSection` loads at pre-login and saves on database threads; gameplay
   reads the cached copy (`PlotData`, `GensPlayer`).
3. **Every transfer of value is one atomic, logged operation.** Use the economy module's `withdraw`/`deposit`, the
   exploit checklist in `PORTING-GUIDE.md`, and `EconomyLedgerService`-style logging (Phases 2 and 3).
4. **A plot is a template plus data; blocks are never saved.** `PlotTemplate` + `PlotData`; the world's plot blocks
   are wiped on removal and after a crash.
5. **Balance numbers live in config and reload live.** `/gensadmin reload` rereads config.yml and calls every module's
   `reload()`. The plot grid is the one exception (moving it would strand blocks), so it changes only on restart.
6. **Belts are drawn only near players** (Phase 2).

## Folia design for gens

- **One platform, one region.** Factories share a platform so players can walk to each other's bases, which means a
  platform's 24 factories tick on one region thread (like a normal server with 24 players). Platforms are
  `plots.layout.platform-spacing` (1024) apart and each fits inside one 256-block region section, so separate
  platforms tick in parallel. Belt simulation (Phase 2) runs as a region task on the platform's region, never as one
  global loop over every factory.
- **Block work on the owning region.** `PlotBuilder` loads the plot's chunks (`getChunkAtAsync`), holds them with a
  plugin chunk ticket, and places or clears blocks in slices on `Tasks.region`.
- **Bounded concurrency.** At most `plots.build.max-concurrent` builds and clears run at once. Loading a plot's chunks
  also loads a ring of chunks around it; 112 clears started together ran a 2 GB test server out of memory.
- **Nothing world-related during `onEnable`.** Chunk loads and region tasks started while plugins enable never ran
  (tested). Start-up work is deferred to the first global tick.
- **Cross-plot actions** (a raider on another player's plot, Phase 3) touch two players on two threads. Copy what is
  needed on each player's thread and decide shared outcomes with one database change (`UPDATE ... WHERE state = ...`).
- **Shared chunks are counted.** Neighbouring lots share chunks, and the server keeps one plugin chunk ticket per
  chunk, so `PlotBuilder` counts holds per chunk and only drops the ticket when the last holder lets go.
- **Console commands cannot read blocks.** On Folia `/execute if block` from the console fails (the console does not
  own the region); `/plotadmin verify` checks builds on each region's own thread instead.
- **Pre-generate the platform area** before launch (e.g. Chunky, which supports Folia) so building a plot loads chunks
  instead of generating them.

## The platform

Every online player gets a factory on a shared platform: a 61x61 plaza in the middle (spawn, sell point, the Phase 4
conveyor shop), four 7-wide roads running out of it, and 3 factory lots on each side of every road, so 24 factories
per platform, 229 blocks across. Each 25x25 factory is the template turned so its front faces its road (templates are
built facing south). Lots fill from the plaza outwards; when a platform is full the next one is built, 1024 blocks
away. The platform floor, roads and an optional hub build (`hub.nbt`) stay in the world; factories come and go.

## Modules

### Kept from PvPCore

economy, teleport, essentials, chat, staff, staffmode, kits, vaults, announcements, settings, mail, vote, polls,
crates, vouchers, daily, leaderboards, wipe, lagwatch, antihack, sus, info, store, ranks, kitalerts, shards,
keycrates, reputation, wordle, chatgames, worldrules, maintenance, sit, cosmetics.

Kept but **off** (after launch in the roadmap): `trade` (player trading), `seasonpass` (seasons).

### Removed

arena, drills, spawners, mobspawns, structures, powers, combat, auction, order, gamble, holidays, homeslots, rtp,
antixray, sell, sellplus, shop. They are HCF or survival features that do not fit a void plot server. The conveyor
shop (Phase 4) is new code, not PvPCore's shop. PvPCore's `antixray/DiscordWebhook` moved to `util/` because
lagwatch and wipe use it.

### Gens modules by roadmap phase

| Module | Package | Phase | Status |
|---|---|---|---|
| Core: config, commands, permissions, `/gensadmin reload` | `GensCore`, `command/` | 1 | done |
| Data: database, cache, saving | `storage/`, `player/` (from PvPCore) | 1 | done |
| Plots: slot grid, template, build and clear, lifecycle | `plots/` | 1 | done, tested on Folia (see below) |
| Belt | `belt/` | 2 | |
| Economy: sell point, prices | `economy/` (exists) + belt sell point | 2 | balance exists |
| Lock | `lock/` | 3 | |
| Raid | `raid/` | 3 | |
| Session: /logout, disconnect timer | `session/` | 3 | `plots.remove-delay-seconds` is the placeholder |
| Progression: upgrades, machines, rebirth | `progression/` | 4 | `gens_players.rebirths` exists |
| Shop: central conveyor | `conveyorshop/` | 4 | |
| Pets | `pets/` | 6 | |
| Live content | `daily/` (exists), quests, events, NPC bases | 6 | daily rewards exist |

## Data model

| Table | Holds | Owner |
|---|---|---|
| `pvp_players` | UUID, name, first join, last seen, playtime | core |
| `pvp_balances`, `pvp_economy_ledger` | balance, and a log of every money change | economy |
| `gens_players` | UUID, rebirth count | plots |
| `gens_plots` | owner, belt length, lock level, shield expiry | plots |
| `gens_slots` | slot, owner, since: which slots may hold blocks (crash recovery) | plots |
| `gens_segments`, `gens_gens`, `gens_pets`, `gens_raid_log` | | Phases 2 to 6 |

The `pvp_` prefix on tables inherited from PvPCore was left as is. Renaming it would touch every module's SQL for no
gain on a new database.

## Phase 1 status (2026-11-19)

Done:

- [x] Repository, build (`check-compile.sh`, or `mvn package`) and a local Folia test server (`C:\Users\madgp\genscore-test`)
- [x] Config loader with a reload command (`/gensadmin reload`)
- [x] Database schema with migrations (`plots v1`)
- [x] Player data loaded off the main thread at login, cached, saved on change, on quit and on a timer
- [x] Void world with shared platforms (plaza, four roads, 24 factory lots each), and an allocator that hands out and frees lots
- [x] Factories turned to face their road; extra platforms built when one fills
- [x] Plot paste from the template and plot clear, both spread across ticks
- [x] Plot lifecycle: create on join, save and remove on quit, crash recovery on start-up
- [x] `/plot` to go to your own plot; falling into the void returns you to it
- [x] Admin: `/plotadmin info|save|reset|tp|slots|stress|verify|dummy|template pos1|pos2|save [factory|hub]|reload`,
  `/plotadmin platform tp|rebuild|hub <number>`

Tested on the local Folia 26.3 server (console only, no player has joined yet):

- `plotadmin stress 50` (50 simulated joins and quits, half quitting mid-build): PASSED, 0 orphaned slots, 0 leftover rows,
  also when it spills onto a new platform.
- `plotadmin dummy 30` then `plotadmin verify`: every block of 30 factories on 2 platforms matched the template in all
  four rotations, every factory front was on a road, and both platform floors were complete.
- Stopping the server with 200 plots built, then starting it again: the leftover slots were cleared and freed.
- `gensadmin reload`, `plotadmin slots`, and a clean stop afterwards with no errors.

Not tested yet:

- A real player joining, getting a plot, relogging and finding it restored (the first half of the Phase 1 gate).
- `/plotadmin template save` with a hand-built factory or hub template, and the spawn, belt and pad markers.
- How the platform looks and feels in game (nobody has walked it yet).
- PostgreSQL (the test server uses SQLite).

## Phase 0 decisions still open

These come from the roadmap and must be answered before Phase 2 and 3 code: lock behaviour (fixed 45 s re-lock, or a
duration plus a cooldown), what a raid costs the owner, raid limits (segments per raid, shield length), the disconnect
timer length, offline income, launch upgrade paths and prices, and the name and theme. Also: the economy spreadsheet,
the 25x25 template with belt path and gen pads marked, and the launch targets.
