# Porting a BlockvilleCore feature into GensCore

Read `PLAN.md` first. This guide is what every port must follow.

## Where things are

- New plugin: `GensCore/src/main/java/me/Evan52098/pvpcore/`. One package per module.
- Source to port: `BlockvilleCore/src/main/java/me/Evan52098/bvcore/` (decompiled; `command/`, `service/`, `data/`,
  `gui/`, and feature packages) and `blockville-common/src/main/java/me/Evan52098/` (shared helpers, `bvprofiles`).
- Reference modules already done, copy their patterns: `economy/` (money, offline changes, Vault), `kits/`
  (player data section, database-backed definitions, editor chest), `vaults/` (load on open, save on close, one viewer
  at a time, save open vaults on disable), `staff/InvHistory` (menus, snapshots, restore, offline queue),
  `menu/Menu` + `menu/Lore` (menus in BlockvilleCore's look).

## Building blocks

| Need | Use |
|---|---|
| Schedule anything | `scheduler/Tasks` (`entity`, `region`, `global`, `async`, `onEntity`, `forSender`). Never `Bukkit.getScheduler()`. |
| Teleport | `player.teleportAsync(...)`, or `TeleportModule.service().teleport(...)` for player-initiated teleports with warmup. |
| Database | `storage/Database` (`async`, `asyncRun`, `sync` only at startup/shutdown, `transaction`), `storage/Migrations` (append new steps, never edit shipped ones), `db.autoIdColumn("id")`. SQL must work on PostgreSQL and SQLite. |
| Per-player data | `player/PlayerSection<T>`, registered in `enable()`. The data object must be thread-safe. `PlayerDataService.runSerialized` for out-of-band writes. |
| Money | `EconomyModule.service()`: `withdraw` (atomic, returns false if not enough), `deposit`, `balance`. Amounts are cents (`util/Money`). Get the module with `plugin.modules().get(EconomyModule.class)`; it may be null (module off). |
| Commands | Lamp annotations (see any `*Commands` class), registered with `plugin.commands().register(...)`. |
| Messages | `util/Msg` (`good`, `bad`, `neutral`, `white`, `escape`, `parse`). Keep BlockvilleCore's wording. |
| Menus | `menu/Menu` (every click cancelled, buttons run code) and `menu/Lore` (item look). Not InventoryFramework. |
| Items as text | `util/Items` (`encode`, `decode`, `nonEmpty`). |
| Cooldowns | `util/Cooldowns`. |
| Module shape | A class implementing `module/GensModule` (`id`, `enable`, `disable`, `reload`). |

## Folia rules

1. Touch a player (inventory, health, location, open menus, messages) only on that player's thread:
   `Tasks.onEntity(player, ...)`. Event handlers for a player already run on that player's thread.
2. Touch blocks only on the thread that owns that location: `Tasks.region.run(location, ...)`.
3. Two players are often on two different threads. Never read player B's inventory or location from player A's
   thread. Copy what you need on B's thread, then continue on A's.
4. Database and web work: `db.async(...)`, then hop back with `Tasks.entity.run(player, ...)` before touching the game.
5. Shared maps are `ConcurrentHashMap`; per-object state is `synchronized` or atomic.

## Exploits and dupes: required checklist

Every port that moves items or money must handle all of these. Write the protection in code, and say in a comment
which item of this list a guard covers.

1. **Take before you give.** Remove the item or money from the player first (on their thread), then record it.
   Give the reward only after the record is confirmed. Never "give, then try to take".
2. **Single winner on shared things.** Buying a listing, claiming a reward, accepting a trade: one atomic database
   change decides who wins, e.g. `UPDATE ... SET state = 'SOLD' WHERE id = ? AND state = 'ACTIVE'` and check that
   exactly one row changed. Two players on two threads clicking at the same moment must not both succeed.
3. **No double clicks.** A player can click a button many times before the first async result returns. Keep an
   in-flight flag per player (or per object) and ignore clicks while it is set; clear it in every exit path.
4. **Menus.** `Menu` cancels every click and drag. If you build an inventory players may put items into (trade,
   editor), handle shift-click, number keys, off-hand swap (F), double-click collect, drag, and drop; validate the
   final contents on close rather than trusting the click stream.
5. **Disconnects, deaths, kicks and server stops** in the middle of anything: items held in escrow must be returned
   or stored in a claim box, never lost and never duplicated. Handle `InventoryCloseEvent`, `PlayerQuitEvent`,
   `PlayerDeathEvent`, and `disable()` (close events arrive after the plugin is disabled at shutdown).
6. **Full inventories.** Never lose items when giving: drop at the player's feet or put them in a claim box.
7. **Crash safety.** If the server dies between two steps, the database must say what happened. Prefer one
   transaction; otherwise write state transitions (PENDING, DONE) so a restart can finish or roll back.
8. **Money.** Check amounts: positive, below a sane maximum, cents only (no NaN, negative, or overflow). Withdraw
   first, refund on failure.
9. **Item validity.** Refuse air, refuse amounts above the stack size, re-read the real item from the inventory on
   the player's thread instead of trusting a stored copy.
10. **Rate limits** on commands that hit the database (cooldowns), and caps on how many listings or entries one
    player can create.
11. **Log** every money or item movement worth auditing (the economy ledger already logs money).
12. **Persist the take before the give.** A player's inventory reaches disk only when the player file saves, and
    wallets only when player data saves, while database rows are written at once. A crash in between duplicates
    things. So: after taking items from an inventory call `util/PlayerFiles.save(player)` before writing the row that
    stores them; after giving items from a row, mark the row taken first, give, then `PlayerFiles.save(player)`.
    When money taken from a wallet pays for something written to the database, write it only after
    `economy.persist(uuid)` completes. Done this way, a crash can lose at most the one move in flight, never
    duplicate it.

## How to hand your work back

- **Do not edit** `GensCore.java`, `config.yml`, `plugin.yml`, `PLAN.md`, this guide, or another module's package.
  Several people work at once.
- Write `GensCore/integration/<your-group>.md` with: the exact `modules.register(...)` lines in load order (and which
  modules they need enabled first), the `config.yml` section to add, the `plugin.yml` permission lines, and a short
  list of what you did not port and why.
- **Do not run Maven** (it clashes with the others). Check your code compiles with
  `sh GensCore/check-compile.sh <your-package-name>` (shows only errors mentioning that text; run it with no
  argument for everything). Other people's half-written files may show errors too; only yours must be clean.
- Test on the local Folia server `C:\Users\madgp\genscore-test` (port 25567, void world, SQLite): copy the jar into
  `plugins/GensCore.jar`, start it with `sh start.sh > server.out 2>&1`, send console commands with
  `echo "<command>" >> console.in`. Say what you tested and what you did not; any "not owned by the current region"
  line in the log is a Folia bug.
