package me.Evan52098.genscore.plots;

import me.Evan52098.genscore.economy.EconomyModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Money;
import me.Evan52098.genscore.util.Msg;
import me.Evan52098.genscore.wipe.WipeCommands;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.structure.Structure;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Default;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Single;
import revxrsal.commands.annotation.SuggestWith;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** {@code /plot} for players and {@code /plotadmin} for staff. */
public final class PlotCommands {

    private static final int STRESS_MAX = 200;

    private final PlotsModule module;
    private final Map<UUID, Location[]> corners = new ConcurrentHashMap<>();
    private final AtomicBoolean stressRunning = new AtomicBoolean();

    PlotCommands(PlotsModule module) {
        this.module = module;
    }

    private PlotService service() {
        return module.service();
    }

    // ------------------------------------------------------------------ players

    @Command({"plot", "base"})
    @CommandPermission("genscore.plot")
    @Description("Go to your plot")
    public void plot(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        Plot plot = service().plotOf(player.getUniqueId());
        if (plot == null) {
            // Built on join; if that failed, try again now.
            player.sendMessage(Msg.neutral("Building your plot..."));
            service().create(player.getUniqueId()).whenComplete((p, error) -> Tasks.entity.run(player, () -> {
                if (error != null) {
                    player.sendMessage(Msg.bad("Your plot could not be built: " + Msg.escape(rootMessage(error))));
                } else {
                    player.teleportAsync(service().spawnOf(p), PlayerTeleportEvent.TeleportCause.COMMAND);
                }
            }));
            return;
        }
        if (!plot.isReady()) {
            player.sendMessage(Msg.neutral("Your plot is still being built. Try again in a moment."));
            return;
        }
        player.teleportAsync(service().spawnOf(plot), PlayerTeleportEvent.TeleportCause.COMMAND)
                .thenAccept(ok -> {
                    if (ok) {
                        Tasks.entity.run(player, () -> player.sendMessage(Msg.good("Welcome back to your plot.")));
                    }
                });
    }

    // ------------------------------------------------------------------ admin: players

    @Command("plotadmin info")
    @CommandPermission(PlotsModule.ADMIN)
    @Description("Show a player's plot and gens data")
    public void info(BukkitCommandActor actor, @Named("player") @Single @SuggestWith(WipeCommands.OnlinePlayers.class) String name) {
        CommandSender sender = actor.sender();
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            UUID id = online.getUniqueId();
            PlotData data = module.data(id);
            GensPlayer gens = module.gensPlayer(id);
            Plot plot = service().plotOf(id);
            String plotLine = plot == null ? "none loaded"
                    : "slot " + plot.slot() + " on platform " + service().grid().platformOf(plot.slot()) + " (" + plot.state().name().toLowerCase() + ") at " + service().grid().minX(plot.slot())
                    + ", " + service().grid().floorY() + ", " + service().grid().minZ(plot.slot());
            sendInfo(sender, online.getName() + " (online)", plotLine,
                    data == null ? null : new long[]{data.beltLength(), data.lockLevel(), data.shieldUntil()},
                    gens == null ? -1 : gens.rebirths(), id);
            return;
        }
        module.plugin().playerData().findUuid(name).thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            UUID id = found.get();
            return module.plugin().database().async(c -> {
                long[] plotRow = null;
                int rebirths = 0;
                try (PreparedStatement ps = c.prepareStatement("SELECT belt_length, lock_level, shield_until FROM gens_plots WHERE uuid = ?")) {
                    ps.setString(1, id.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            plotRow = new long[]{rs.getInt(1), rs.getInt(2), rs.getLong(3)};
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT rebirths FROM gens_players WHERE uuid = ?")) {
                    ps.setString(1, id.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            rebirths = rs.getInt(1);
                        }
                    }
                }
                return new Object[]{id, plotRow, rebirths};
            });
        }).whenComplete((row, error) -> Tasks.forSender(sender, () -> {
            if (error != null) {
                sender.sendMessage(Msg.bad("Lookup failed: " + Msg.escape(rootMessage(error))));
            } else if (row == null) {
                sender.sendMessage(Msg.bad("Player not found."));
            } else {
                UUID id = (UUID) row[0];
                Plot plot = service().plotOf(id);
                sendInfo(sender, name + " (offline)", plot == null ? "none loaded" : "slot " + plot.slot() + " (being removed)",
                        (long[]) row[1], (Integer) row[2], id);
            }
        }));
    }

    private void sendInfo(CommandSender sender, String who, String plotLine, long @Nullable [] data, int rebirths, UUID id) {
        sender.sendMessage(Msg.neutral("Plot data for " + Msg.white(Msg.escape(who)) + ":"));
        sender.sendMessage(Msg.parse("<gray>Plot: <white>" + Msg.escape(plotLine)));
        if (data == null) {
            sender.sendMessage(Msg.parse("<gray>Saved plot data: <white>none yet"));
        } else {
            long shield = data[2] - System.currentTimeMillis();
            sender.sendMessage(Msg.parse("<gray>Belt length: <white>" + data[0] + "</white>  Lock level: <white>" + data[1]
                    + "</white>  Shield: <white>" + (shield > 0 ? (shield / 1000) + "s left" : "none")));
        }
        sender.sendMessage(Msg.parse("<gray>Rebirths: <white>" + Math.max(0, rebirths)));
        EconomyModule eco = module.plugin().modules().get(EconomyModule.class);
        if (eco != null) {
            eco.service().balanceAsync(id).thenAccept(cents -> Tasks.forSender(sender, () ->
                    sender.sendMessage(Msg.parse("<gray>Balance: <white>" + Money.format(cents)))));
        }
    }

    @Command("plotadmin save")
    @CommandPermission(PlotsModule.ADMIN)
    @Description("Save an online player's data now")
    public void save(BukkitCommandActor actor, @Named("player") @Single @SuggestWith(WipeCommands.OnlinePlayers.class) String name) {
        CommandSender sender = actor.sender();
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(Msg.bad("That player is not online (offline players are already saved)."));
            return;
        }
        module.plugin().playerData().saveNow(target.getUniqueId()).whenComplete((v, error) -> Tasks.forSender(sender, () ->
                sender.sendMessage(error == null ? Msg.good("Saved " + Msg.white(target.getName()) + "'s data.")
                        : Msg.bad("Save failed: " + Msg.escape(rootMessage(error))))));
    }

    @Command("plotadmin reset")
    @CommandPermission(PlotsModule.ADMIN)
    @Description("Rebuild a player's plot from the template; add 'data' to also reset their plot data")
    public void reset(BukkitCommandActor actor, @Named("player") @Single @SuggestWith(WipeCommands.OnlinePlayers.class) String name,
                      @Named("what") @Optional @Nullable String what) {
        CommandSender sender = actor.sender();
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(Msg.bad("That player is not online, so they have no plot loaded."));
            return;
        }
        Plot plot = service().plotOf(target.getUniqueId());
        if (plot == null || !plot.isReady()) {
            sender.sendMessage(Msg.bad("Their plot is not ready (building, rebuilding or being removed)."));
            return;
        }
        if ("data".equalsIgnoreCase(what)) {
            PlotData data = module.data(target.getUniqueId());
            if (data != null) {
                data.reset(module.live().defaultBeltLength());
                module.plugin().playerData().saveSoon(target.getUniqueId());
            }
        }
        sender.sendMessage(Msg.neutral("Rebuilding " + Msg.white(target.getName()) + "'s plot..."));
        service().rebuild(plot).whenComplete((ok, error) -> Tasks.forSender(sender, () -> {
            if (error != null || !Boolean.TRUE.equals(ok)) {
                sender.sendMessage(Msg.bad("The rebuild did not finish; see the server log."));
                return;
            }
            sender.sendMessage(Msg.good("Rebuilt " + Msg.white(target.getName()) + "'s plot"
                    + ("data".equalsIgnoreCase(what) ? " and reset their plot data." : ".")));
            Tasks.entity.run(target, () -> target.teleportAsync(service().spawnOf(plot), PlayerTeleportEvent.TeleportCause.PLUGIN));
        }));
    }

    @Command("plotadmin tp")
    @CommandPermission(PlotsModule.ADMIN)
    @Description("Go to a player's plot")
    public void tp(BukkitCommandActor actor, @Named("player") @Single @SuggestWith(WipeCommands.OnlinePlayers.class) String name) {
        Player admin = actor.requirePlayer();
        Player target = Bukkit.getPlayerExact(name);
        Plot plot = target == null ? null : service().plotOf(target.getUniqueId());
        if (plot == null) {
            admin.sendMessage(Msg.bad("That player has no plot loaded."));
            return;
        }
        admin.teleportAsync(service().spawnOf(plot), PlayerTeleportEvent.TeleportCause.COMMAND);
    }

    @Command("plotadmin slots")
    @CommandPermission(PlotsModule.ADMIN)
    @Description("Show how many plot slots are free, used and being cleared")
    public void slots(BukkitCommandActor actor) {
        SlotAllocator.Counts c = service().counts();
        actor.sender().sendMessage(Msg.neutral("Plot slots: " + Msg.white(String.valueOf(c.used())) + " used, "
                + Msg.white(String.valueOf(c.clearing())) + " clearing, " + Msg.white(String.valueOf(c.free())) + " free; "
                + Msg.white(String.valueOf(service().loadedCount())) + " plots loaded. Template: "
                + Msg.white(Msg.escape(module.template().source())) + ", " + Msg.white(String.valueOf(service().queuedJobs())) + " builds/clears queued, "
                + Msg.white(String.valueOf(service().builtPlatforms())) + " platform(s) built."));
    }

    // ------------------------------------------------------------------ admin: stress test (phase 1 gate)

    /**
     * Builds {@code count} plots for made-up owners, makes half of them "quit" while still building and the rest once
     * built, then checks that every slot came back and no rows were left. This is the phase 1 gate check
     * ("fifty simulated joins and quits leave no orphaned slots"). It really builds plots: run it on a test server.
     */
    @Command("plotadmin stress")
    @CommandPermission(PlotsModule.ADMIN)
    @Description("Simulate joins and quits and check for orphaned slots (test servers only)")
    public void stress(BukkitCommandActor actor, @Named("count") @Default("50") int count) {
        CommandSender sender = actor.sender();
        if (count < 1 || count > STRESS_MAX) {
            sender.sendMessage(Msg.bad("Count must be 1 to " + STRESS_MAX + "."));
            return;
        }
        if (!stressRunning.compareAndSet(false, true)) {
            sender.sendMessage(Msg.bad("A stress test is already running."));
            return;
        }
        SlotAllocator.Counts before = service().counts();
        long start = System.currentTimeMillis();
        Set<UUID> owners = new HashSet<>();
        List<CompletableFuture<Void>> runs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID owner = UUID.nameUUIDFromBytes(("gens-stress-" + start + "-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            owners.add(owner);
            CompletableFuture<Plot> built = service().create(owner);
            if (i % 2 == 0) {
                service().ownerLeft(owner); // quits while the plot is still building
            }
            runs.add(built.handle((plot, error) -> null).thenCompose(v -> {
                Plot plot = service().plotOf(owner);
                return plot == null ? CompletableFuture.completedFuture(null) : service().remove(plot, plot.removalToken());
            }));
        }
        sender.sendMessage(Msg.neutral("Stress test: " + Msg.white(String.valueOf(count)) + " simulated joins and quits started."));
        CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new))
                .thenCompose(v -> waitForClears(owners, 0))
                .thenCompose(v -> module.plugin().database().async(c -> {
                    int rows = 0;
                    try (PreparedStatement ps = c.prepareStatement("SELECT owner FROM gens_slots");
                         ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            if (owners.contains(UUID.fromString(rs.getString(1)))) {
                                rows++;
                            }
                        }
                    }
                    return rows;
                }))
                .whenComplete((rows, error) -> {
                    stressRunning.set(false);
                    SlotAllocator.Counts after = service().counts();
                    int held = service().heldBy(owners);
                    long took = System.currentTimeMillis() - start;
                    Tasks.forSender(sender, () -> {
                        if (error != null) {
                            sender.sendMessage(Msg.bad("Stress test failed: " + Msg.escape(rootMessage(error))));
                            return;
                        }
                        boolean pass = held == 0 && rows == 0 && after.free() == before.free();
                        sender.sendMessage((pass ? Msg.good("Stress test PASSED") : Msg.bad("Stress test FAILED"))
                                .append(Msg.parse("<gray> in " + took + " ms: orphaned slots <white>" + held + "</white>, leftover rows <white>"
                                        + rows + "</white>, free slots before/after <white>" + before.free() + "/" + after.free())));
                    });
                });
    }

    @Command("plotadmin verify")
    @CommandPermission(PlotsModule.ADMIN)
    @Description("Check every loaded factory against its template and every built platform's floor")
    public void verify(BukkitCommandActor actor) {
        CommandSender sender = actor.sender();
        List<CompletableFuture<String>> checks = new ArrayList<>();
        for (Plot plot : service().loaded()) {
            if (!plot.isReady()) {
                continue;
            }
            int slot = plot.slot();
            String rotation = service().grid().lot(slot).rotation().name().toLowerCase();
            checks.add(service().verifyFactory(slot).handle((c, e) -> e != null ? "factory " + slot + " ERROR " + rootMessage(e)
                    : c.wrong() > 0 || !c.frontOnRoad() ? "factory " + slot + " (" + rotation + ") " + c.wrong() + "/" + c.checked()
                    + " blocks wrong, front on road: " + c.frontOnRoad() : null));
        }
        for (int id : service().builtPlatformIds()) {
            checks.add(service().verifyPlatform(id).handle((c, e) -> e != null ? "platform " + id + " ERROR " + rootMessage(e)
                    : c.wrong() > 0 ? "platform " + id + " floor " + c.wrong() + "/" + c.checked() + " blocks wrong" : null));
        }
        int total = checks.size();
        CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).whenComplete((v, e) -> {
            List<String> problems = new ArrayList<>();
            for (CompletableFuture<String> c : checks) {
                String r = c.getNow("not finished");
                if (r != null) {
                    problems.add(r);
                }
            }
            Tasks.forSender(sender, () -> {
                if (problems.isEmpty()) {
                    sender.sendMessage(Msg.good("Verify PASSED: " + total + " factories and platforms match, every factory front is on a road."));
                } else {
                    sender.sendMessage(Msg.bad("Verify FAILED (" + problems.size() + " of " + total + "):"));
                    problems.stream().limit(15).forEach(line -> sender.sendMessage(Msg.parse("<gray>" + Msg.escape(line))));
                }
            });
        });
    }

    /** Made-up owners of factories built with /plotadmin dummy; they stay until /plotadmin dummy clear or a restart. */
    private final Set<UUID> dummies = ConcurrentHashMap.newKeySet();

    @Command("plotadmin dummy")
    @CommandPermission(PlotsModule.ADMIN)
    @Description("Build factories for made-up owners and keep them (test servers), or 'clear' to remove them")
    public void dummy(BukkitCommandActor actor, @Named("count|clear") String arg) {
        CommandSender sender = actor.sender();
        if (arg.equalsIgnoreCase("clear")) {
            List<CompletableFuture<Void>> removals = new ArrayList<>();
            for (UUID owner : List.copyOf(dummies)) {
                Plot plot = service().plotOf(owner);
                if (plot != null) {
                    removals.add(service().remove(plot, plot.removalToken()));
                }
                dummies.remove(owner);
            }
            sender.sendMessage(Msg.neutral("Removing " + Msg.white(String.valueOf(removals.size())) + " dummy factories..."));
            CompletableFuture.allOf(removals.toArray(CompletableFuture[]::new)).whenComplete((v, e) -> Tasks.forSender(sender, () ->
                    sender.sendMessage(e == null ? Msg.good("Dummy factories removed.") : Msg.bad("Some removals failed; see the log."))));
            return;
        }
        int count;
        try {
            count = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            sender.sendMessage(Msg.bad("Give a number, or " + Msg.white("clear") + "."));
            return;
        }
        if (count < 1 || count > STRESS_MAX) {
            sender.sendMessage(Msg.bad("Count must be 1 to " + STRESS_MAX + "."));
            return;
        }
        List<CompletableFuture<Plot>> builds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID owner = UUID.randomUUID();
            dummies.add(owner);
            builds.add(service().create(owner));
        }
        sender.sendMessage(Msg.neutral("Building " + Msg.white(String.valueOf(count)) + " dummy factories..."));
        CompletableFuture.allOf(builds.toArray(CompletableFuture[]::new)).whenComplete((v, e) -> {
            StringBuilder slotsText = new StringBuilder();
            for (CompletableFuture<Plot> b : builds) {
                Plot p = b.getNow(null);
                if (p != null) {
                    PlotSettings.Lot lot = service().grid().lot(p.slot());
                    slotsText.append(slotsText.isEmpty() ? "" : "; ").append(p.slot()).append("@").append(lot.minX()).append(",")
                            .append(lot.minZ()).append(" ").append(lot.rotation().name().toLowerCase());
                }
            }
            Tasks.forSender(sender, () -> sender.sendMessage(e == null
                    ? Msg.good("Built " + count + " dummy factories: " + Msg.escape(slotsText.toString()))
                    : Msg.bad("Some dummy builds failed: " + Msg.escape(rootMessage(e)))));
        });
    }

    /** Polls (async, every half second, up to 60 s) until none of the owners hold a slot. */
    private CompletableFuture<Void> waitForClears(Set<UUID> owners, int attempt) {
        if (service().heldBy(owners) == 0 || attempt >= 120) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> next = new CompletableFuture<>();
        Tasks.async.later(() -> waitForClears(owners, attempt + 1).whenComplete((v, e) -> next.complete(null)),
                500, java.util.concurrent.TimeUnit.MILLISECONDS);
        return next;
    }

    // ------------------------------------------------------------------ admin: template authoring

    @Command("plotadmin template pos1")
    @CommandPermission(PlotsModule.ADMIN)
    @Description("Set the first corner of the plot template to the block you stand on")
    public void pos1(BukkitCommandActor actor) {
        setCorner(actor.requirePlayer(), 0);
    }

    @Command("plotadmin template pos2")
    @CommandPermission(PlotsModule.ADMIN)
    @Description("Set the second corner of the plot template to the block you stand on")
    public void pos2(BukkitCommandActor actor) {
        setCorner(actor.requirePlayer(), 1);
    }

    private void setCorner(Player player, int index) {
        Location at = player.getLocation().getBlock().getLocation();
        corners.computeIfAbsent(player.getUniqueId(), k -> new Location[2])[index] = at;
        player.sendMessage(Msg.good("Corner " + (index + 1) + " set to " + Msg.white(at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ()) + "."));
    }

    @Command("plotadmin template save")
    @CommandPermission(PlotsModule.ADMIN)
    @Description("Save the blocks between the two corners as the factory template (front facing south) or the hub")
    public void saveTemplate(BukkitCommandActor actor, @Named("which") @Default("factory") String which) {
        Player player = actor.requirePlayer();
        boolean isHub = which.equalsIgnoreCase("hub");
        if (!isHub && !which.equalsIgnoreCase("factory")) {
            player.sendMessage(Msg.bad("Use " + Msg.white("factory") + " or " + Msg.white("hub") + "."));
            return;
        }
        Location[] c = corners.get(player.getUniqueId());
        if (c == null || c[0] == null || c[1] == null || !c[0].getWorld().equals(c[1].getWorld())) {
            player.sendMessage(Msg.bad("Set both corners in one world first: " + Msg.white("/plotadmin template pos1") + " and "
                    + Msg.white("pos2") + "."));
            return;
        }
        PlotSettings.Grid grid = module.grid();
        int dx = Math.abs(c[0].getBlockX() - c[1].getBlockX()) + 1;
        int dz = Math.abs(c[0].getBlockZ() - c[1].getBlockZ()) + 1;
        int plaza = grid.plazaHalf() * 2 + 1;
        if (!isHub && (dx != grid.size() || dz != grid.size())) {
            player.sendMessage(Msg.bad("The area is " + dx + "x" + dz + "; factories are " + grid.size() + "x" + grid.size() + "."));
            return;
        }
        if (isHub && (dx > plaza || dz > plaza)) {
            player.sendMessage(Msg.bad("The area is " + dx + "x" + dz + "; the hub must fit the " + plaza + "x" + plaza + " plaza."));
            return;
        }
        Location lo = new Location(c[0].getWorld(), Math.min(c[0].getX(), c[1].getX()), Math.min(c[0].getY(), c[1].getY()),
                Math.min(c[0].getZ(), c[1].getZ()));
        Location hi = new Location(c[0].getWorld(), Math.max(c[0].getX(), c[1].getX()), Math.max(c[0].getY(), c[1].getY()),
                Math.max(c[0].getZ(), c[1].getZ()));
        File file = isHub ? module.hubFile() : module.templateFile();
        // Reading blocks must happen on the region that owns them.
        Tasks.region.run(lo, () -> {
            Structure structure;
            try {
                structure = Bukkit.getStructureManager().createStructure();
                structure.fill(lo, new org.bukkit.util.BlockVector(dx, hi.getBlockY() - lo.getBlockY() + 1, dz), false);
            } catch (RuntimeException e) {
                Tasks.entity.run(player, () -> player.sendMessage(Msg.bad("Could not read the area (stand inside it): "
                        + Msg.escape(String.valueOf(e.getMessage())))));
                return;
            }
            Tasks.async.run(() -> {
                String result;
                try {
                    Files.createDirectories(file.getParentFile().toPath());
                    if (file.isFile()) {
                        Files.copy(file.toPath(), new File(file.getParentFile(), file.getName() + ".bak-" + System.currentTimeMillis()).toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                    Bukkit.getStructureManager().saveStructure(file, structure);
                    module.reloadTemplates();
                    result = null;
                } catch (IOException | RuntimeException e) {
                    result = String.valueOf(e.getMessage());
                }
                String error = result;
                Tasks.entity.run(player, () -> player.sendMessage(error == null
                        ? Msg.good("Saved the " + which.toLowerCase() + " template (" + Msg.white(file.getName()) + "). "
                        + (isHub ? Msg.white("/plotadmin platform hub <number>") + " puts it on a built platform."
                        : "New factories use it; " + Msg.white("/plotadmin reset") + " rebuilds an existing one."))
                        : Msg.bad("Could not save the template: " + Msg.escape(error))));
            });
        });
    }

    @Command("plotadmin template reload")
    @CommandPermission(PlotsModule.ADMIN)
    @Description("Reload the factory and hub template files")
    public void reloadTemplate(BukkitCommandActor actor) {
        CommandSender sender = actor.sender();
        Tasks.async.run(() -> {
            module.reloadTemplates();
            Tasks.forSender(sender, () -> sender.sendMessage(Msg.good("Templates reloaded. Factory: "
                    + Msg.white(Msg.escape(module.template().source())) + " (markers "
                    + Msg.white(Msg.escape(String.valueOf(module.template().markers().keySet()))) + "), hub: "
                    + Msg.white(Msg.escape(module.hub().source())) + ".")));
        });
    }

    // ------------------------------------------------------------------ admin: platforms

    @Command("plotadmin platform tp")
    @CommandPermission(PlotsModule.ADMIN)
    @Description("Go to the hub of a platform")
    public void platformTp(BukkitCommandActor actor, @Named("number") @Default("0") int number) {
        Player player = actor.requirePlayer();
        PlotSettings.Grid grid = module.grid();
        if (number < 0 || number >= grid.maxPlatforms()) {
            player.sendMessage(Msg.bad("Platforms are numbered 0 to " + (grid.maxPlatforms() - 1) + "."));
            return;
        }
        player.teleportAsync(new Location(service().world(), grid.centerX(number) + 0.5, grid.floorY(), grid.centerZ(number) + 0.5),
                PlayerTeleportEvent.TeleportCause.COMMAND);
    }

    @Command("plotadmin platform rebuild")
    @CommandPermission(PlotsModule.ADMIN)
    @Description("Build a platform's floor, roads and hub again (factories on it are not touched)")
    public void platformRebuild(BukkitCommandActor actor, @Named("number") @Default("0") int number) {
        runPlatformJob(actor.sender(), number, "Rebuilt platform ", () -> service().rebuildPlatform(number));
    }

    @Command("plotadmin platform hub")
    @CommandPermission(PlotsModule.ADMIN)
    @Description("Clear a platform's plaza and build the current hub template on it")
    public void platformHub(BukkitCommandActor actor, @Named("number") @Default("0") int number) {
        runPlatformJob(actor.sender(), number, "Rebuilt the hub on platform ", () -> service().rebuildHub(number));
    }

    private void runPlatformJob(CommandSender sender, int number, String done,
                                java.util.function.Supplier<CompletableFuture<Void>> job) {
        if (number < 0 || number >= module.grid().maxPlatforms()) {
            sender.sendMessage(Msg.bad("Platforms are numbered 0 to " + (module.grid().maxPlatforms() - 1) + "."));
            return;
        }
        sender.sendMessage(Msg.neutral("Working on platform " + Msg.white(String.valueOf(number)) + "..."));
        job.get().whenComplete((v, error) -> Tasks.forSender(sender, () -> sender.sendMessage(error == null
                ? Msg.good(done + Msg.white(String.valueOf(number)) + ".")
                : Msg.bad("That did not finish: " + Msg.escape(rootMessage(error))))));
    }

    private static String rootMessage(Throwable error) {
        Throwable t = error;
        while ((t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        return String.valueOf(t.getMessage());
    }
}
