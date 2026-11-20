package me.Evan52098.genscore.trade;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.settings.SettingsModule;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import me.Evan52098.genscore.util.PlayerFiles;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.SuggestWith;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Player trading: /trade, /tradeaccept, /tradedeny, /tradecancel (BlockvilleCore's TradeService, TradeCommand, TradeGui,
 * TradeSession and TradeClaimData) plus /tradeclaim for items that did not fit.
 *
 * <p>Folia-safe escrow design (BlockvilleCore shared one inventory between both players, which is unsafe when the two
 * are on different threads):
 * <ol>
 *   <li>A player offers an item by clicking it in their own inventory. On their own thread it is removed from their
 *       inventory first, their player file is saved, then the item is written to {@code pvp_trade_escrow}.</li>
 *   <li>Each player sees their own {@link TradeView}, redrawn on their own thread from the session.</li>
 *   <li>Any change by either side resets both ready states and stops the countdown.</li>
 *   <li>Completion is one database transaction (OPEN to DONE, escrow to claims for the other side). Each side then
 *       receives their claims on their own thread. Anything that does not fit stays claimable with /tradeclaim.</li>
 *   <li>Cancel, closing the window, quitting, teleporting away, death and server stop all end the trade; escrow goes
 *       back to its owner as claims in one transaction, exactly once. On death the dead player's escrow drops where they
 *       died (as their inventory would have), so a trade cannot be used to keep items through death.</li>
 *   <li>Trades left open by a crash are cancelled at the next start and their escrow returned to the owners.</li>
 * </ol>
 * BlockvilleCore's trade had no money in it, so neither does this one.
 */
public final class TradeModule implements GensModule, Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private record PendingRequest(UUID requester, String requesterName, long expiresAt) {
    }

    private final Map<UUID, TradeSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private final Set<UUID> delivering = ConcurrentHashMap.newKeySet();
    private final Cooldowns cooldowns = new Cooldowns();

    private GensCore plugin;
    private Database db;
    private Logger logger;
    private volatile boolean stopping;

    private long requestMillis;
    private int countdownSeconds;
    private long requestCooldownMillis;
    private double cancelDistance;
    private boolean dropOnDeath;

    @Override
    public String id() {
        return "trade";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        this.db = plugin.database();
        this.logger = plugin.getLogger();
        Migrations.apply(db, id(), logger, TradeStore.migrations(db));
        loadConfig();
        // Checklist 5/7: trades a crash left open are cancelled and their escrow handed back to the owners.
        int recovered = db.sync(TradeStore::recoverOpen);
        if (recovered > 0) {
            logger.warning("[trade] Returned the escrow of " + recovered + " trade(s) left open by the last shutdown; owners get the items at their next login.");
        }
        plugin.commands().register(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void loadConfig() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("trade");
        if (c == null) {
            c = new MemoryConfiguration();
        }
        requestMillis = TimeUnit.SECONDS.toMillis(Math.max(5, c.getLong("request-seconds", 60)));
        countdownSeconds = Math.max(1, Math.min(30, c.getInt("countdown-seconds", 5)));
        requestCooldownMillis = TimeUnit.SECONDS.toMillis(Math.max(0, c.getLong("request-cooldown-seconds", 5)));
        cancelDistance = Math.max(0, c.getDouble("cancel-teleport-distance", 16));
        dropOnDeath = c.getBoolean("drop-escrow-on-death", true);
    }

    @Override
    public void reload() {
        loadConfig();
    }

    /** Checklist 5: server stop. Every open trade is cancelled in the database before the pool closes. */
    @Override
    public void disable() {
        stopping = true;
        Set<TradeSession> open = Set.copyOf(sessions.values());
        for (TradeSession s : open) {
            clearViews(s);
            if (s.beginCancel("server stop")) {
                try {
                    s.queue(db, c -> TradeStore.cancel(c, s.id, null)).get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "[trade] Trade " + s.id + " could not be cancelled at shutdown; its escrow is returned at the next start", e);
                }
            } else {
                try {
                    s.tail().get(10, TimeUnit.SECONDS); // a completion in flight: let it finish
                } catch (Exception ignored) {
                    // recovered at the next start
                }
            }
        }
        sessions.clear();
    }

    // ------------------------------------------------------------------ public API

    public boolean isTrading(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    /** For other modules (for example a combat tag): ends the player's trade and returns everything to its owners. */
    public void cancelTrade(UUID player, String reasonForOther) {
        TradeSession s = sessions.get(player);
        if (s != null) {
            cancel(s, null, null, reasonForOther, null);
        }
    }

    // ------------------------------------------------------------------ commands

    @Command("trade")
    @CommandPermission("genscore.trade")
    @Description("Send someone a trade request")
    public void trade(BukkitCommandActor actor, @Named("player") @SuggestWith(OnlinePlayerSuggestions.class) String targetName) {
        Player player = actor.requirePlayer();
        deliverClaims(player, false);
        sendRequest(player, targetName);
    }

    @Command("tradeaccept")
    @CommandPermission("genscore.trade")
    @Description("Accept a pending trade request")
    public void accept(BukkitCommandActor actor, @Named("player") @SuggestWith(OnlinePlayerSuggestions.class) String requesterName) {
        acceptRequest(actor.requirePlayer(), requesterName);
    }

    @Command("tradedeny")
    @CommandPermission("genscore.trade")
    @Description("Decline a pending trade request")
    public void deny(BukkitCommandActor actor, @Named("player") @SuggestWith(OnlinePlayerSuggestions.class) String requesterName) {
        Player denier = actor.requirePlayer();
        PendingRequest pending = pendingRequests.get(denier.getUniqueId());
        if (pending == null || !pending.requesterName().equalsIgnoreCase(requesterName)) {
            denier.sendMessage(Msg.bad("No pending trade request from " + Msg.escape(requesterName) + "."));
            return;
        }
        pendingRequests.remove(denier.getUniqueId(), pending);
        denier.sendMessage(Msg.good("Declined " + Msg.escape(pending.requesterName()) + "'s trade request."));
        Player requester = Bukkit.getPlayer(pending.requester());
        if (requester != null) {
            String name = denier.getName();
            Tasks.onEntity(requester, () -> requester.sendMessage(Msg.bad(Msg.escape(name) + " declined your trade request.")));
        }
    }

    @Command("tradecancel")
    @CommandPermission("genscore.trade")
    @Description("Cancel your current trade")
    public void cancelCommand(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        TradeSession s = sessions.get(player.getUniqueId());
        if (s == null) {
            player.sendMessage(Msg.bad("You aren't in a trade."));
            return;
        }
        cancelByPlayer(s, player);
    }

    @Command("tradeclaim")
    @CommandPermission("genscore.trade")
    @Description("Collect trade items that did not fit in your inventory")
    public void claim(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        // Checklist 10: a database hit per use, so rate-limited.
        if (!cooldowns.tryStart(player.getUniqueId(), "tradeclaim", 3000)) {
            player.sendMessage(Msg.bad("Slow down - try again in a moment."));
            return;
        }
        deliverClaims(player, true);
    }

    // ------------------------------------------------------------------ requests

    private void sendRequest(Player requester, String targetName) {
        if (targetName.equalsIgnoreCase(requester.getName())) {
            requester.sendMessage(Msg.bad("You can't trade with yourself."));
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            requester.sendMessage(Msg.bad(Msg.escape(targetName) + " isn't online."));
            return;
        }
        SettingsModule settings = plugin.modules().get(SettingsModule.class);
        if (settings != null && !settings.tradeRequestsEnabled(target.getUniqueId())) {
            requester.sendMessage(Msg.bad(Msg.escape(target.getName()) + " isn't accepting trade requests right now."));
            return;
        }
        if (isTrading(requester.getUniqueId())) {
            requester.sendMessage(Msg.bad("You're already in a trade."));
            return;
        }
        if (isTrading(target.getUniqueId())) {
            requester.sendMessage(Msg.bad(Msg.escape(target.getName()) + " is already in a trade."));
            return;
        }
        // Checklist 10: request spam.
        long wait = cooldowns.remaining(requester.getUniqueId(), "trade-request");
        if (wait > 0) {
            requester.sendMessage(Msg.bad("Wait " + Cooldowns.format(wait) + " before sending another trade request."));
            return;
        }
        cooldowns.start(requester.getUniqueId(), "trade-request", requestCooldownMillis);
        pendingRequests.put(target.getUniqueId(), new PendingRequest(requester.getUniqueId(), requester.getName(), System.currentTimeMillis() + requestMillis));
        requester.sendMessage(Msg.good("Trade request sent to " + Msg.escape(target.getName()) + "."));
        String name = Msg.escape(requester.getName());
        Tasks.onEntity(target, () -> target.sendMessage(Msg.neutral(name + " wants to trade with you! "
                + "<click:run_command:'/tradeaccept " + name + "'><white>/tradeaccept " + name + "</white></click> | "
                + "<click:run_command:'/tradedeny " + name + "'><white>/tradedeny " + name + "</white></click>")));
    }

    private void acceptRequest(Player accepter, String requesterName) {
        PendingRequest pending = pendingRequests.get(accepter.getUniqueId());
        if (pending == null || !pending.requesterName().equalsIgnoreCase(requesterName) || pending.expiresAt() < System.currentTimeMillis()) {
            accepter.sendMessage(Msg.bad("No pending trade request from " + Msg.escape(requesterName) + "."));
            return;
        }
        // Checklist 2/3: only one accept can consume the request.
        if (!pendingRequests.remove(accepter.getUniqueId(), pending)) {
            return;
        }
        Player requester = Bukkit.getPlayer(pending.requester());
        if (requester == null || !requester.isOnline()) {
            accepter.sendMessage(Msg.bad(Msg.escape(requesterName) + " is no longer online."));
            return;
        }
        if (stopping) {
            return;
        }
        TradeSession s = new TradeSession(UUID.randomUUID().toString(), requester.getUniqueId(), requester.getName(),
                accepter.getUniqueId(), accepter.getName());
        if (sessions.putIfAbsent(s.a, s) != null) {
            accepter.sendMessage(Msg.bad("One of you is already in a trade."));
            return;
        }
        if (sessions.putIfAbsent(s.b, s) != null) {
            sessions.remove(s.a, s);
            accepter.sendMessage(Msg.bad("One of you is already in a trade."));
            return;
        }
        s.queue(db, c -> {
            TradeStore.createTrade(c, s);
            return null;
        }).exceptionally(e -> {
            logger.log(Level.WARNING, "[trade] Could not record trade " + s.id, e);
            cancel(s, null, "The trade could not be started. Try again in a moment.", "The trade could not be started. Try again in a moment.", null);
            return null;
        });
        openView(accepter, s);
        ScheduledTask t = Tasks.entity.later(requester, () -> openView(requester, s),
                () -> cancel(s, null, null, "Trade canceled - " + Msg.escape(s.nameA) + " disconnected.", null), 1);
        if (t == null) {
            cancel(s, null, null, "Trade canceled - " + Msg.escape(s.nameA) + " disconnected.", null);
        }
    }

    private void openView(Player player, TradeSession s) {
        if (s.state() != TradeSession.State.OPEN || !player.isOnline()) {
            return;
        }
        TradeView view = new TradeView(s, player.getUniqueId());
        view.render();
        player.openInventory(view.getInventory());
        player.sendMessage(Msg.neutral("Trading with <yellow>" + Msg.escape(s.nameOf(s.other(player.getUniqueId()))) + "</yellow><gray>. "
                + "Click items in your inventory to offer them, then click your dye to confirm."));
    }

    // ------------------------------------------------------------------ view clicks (checklist 4: every click and drag is cancelled)

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof TradeView view)) {
            return;
        }
        event.setCancelled(true); // shift-click, number keys, F swap, double-click collect, drop: all refused
        if (!(event.getWhoClicked() instanceof Player player) || !view.viewer.equals(player.getUniqueId()) || stopping) {
            return;
        }
        TradeSession s = view.session;
        if (s.state() != TradeSession.State.OPEN) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return;
        }
        // Checklist 3: rapid clicking cannot queue a flood of database work.
        if (!cooldowns.tryStart(player.getUniqueId(), "trade-click", 150)) {
            return;
        }
        int raw = event.getRawSlot();
        if (raw >= 0 && raw < TradeView.SIZE) {
            if (raw == view.ownReadySlot()) {
                toggleReady(s, player);
                return;
            }
            int index = view.ownIndex(raw);
            if (index >= 0) {
                withdraw(s, player, index);
            }
        } else if (clicked instanceof PlayerInventory) {
            offer(s, player, event.getSlot(), event.isRightClick());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof TradeView) {
            event.setCancelled(true);
        }
    }

    /** Runs on the offering player's thread. */
    private void offer(TradeSession s, Player player, int slot, boolean one) {
        UUID uuid = player.getUniqueId();
        if (!s.hasRoom(uuid)) {
            player.sendMessage(Msg.bad("Your side of the trade is full."));
            return;
        }
        PlayerInventory inv = player.getInventory();
        // Checklist 9: read the real item from the inventory now, never a stored copy; refuse air and illegal stacks.
        ItemStack real = inv.getItem(slot);
        if (real == null || real.isEmpty() || real.getType().isAir()) {
            return;
        }
        if (real.getAmount() <= 0 || real.getAmount() > real.getMaxStackSize()) {
            player.sendMessage(Msg.bad("That stack can't be traded."));
            return;
        }
        int amount = one ? 1 : real.getAmount();
        ItemStack taken = real.clone();
        taken.setAmount(amount);
        // Checklist 1: take before give. The item leaves the inventory before it exists anywhere else.
        if (amount >= real.getAmount()) {
            inv.setItem(slot, null);
        } else {
            ItemStack rest = real.clone();
            rest.setAmount(real.getAmount() - amount);
            inv.setItem(slot, rest);
        }
        TradeSession.Offer offer = new TradeSession.Offer(UUID.randomUUID().toString(), taken);
        if (!s.addOffer(uuid, offer)) {
            giveBack(player, List.of(taken));
            return;
        }
        // Checklist 7: the player file no longer holds the item before the escrow row exists, so a crash can lose this
        // one stack at worst but never duplicate it.
        PlayerFiles.save(player);
        abortCountdown(s);
        String name = player.getName();
        s.queue(db, c -> TradeStore.insertEscrow(c, s.id, uuid, name, offer)).whenComplete((escrowed, error) -> {
            if (error != null) {
                logger.log(Level.SEVERE, "[trade] Could not escrow " + describe(taken) + " for " + name + "; giving it back", error);
                if (s.dropOffer(uuid, offer.id()) || !s.wasDropped(offer.id())) {
                    returnUnstored(uuid, name, taken);
                }
            } else if (!escrowed) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    deliverClaims(p, false); // the trade ended first: it became a claim for its owner
                }
            }
            refresh(s);
        });
        refresh(s);
    }

    /** Takes an offered item back: escrow -> claim -> inventory. Runs on the player's thread. */
    private void withdraw(TradeSession s, Player player, int index) {
        UUID uuid = player.getUniqueId();
        TradeSession.Offer offer = s.removeOffer(uuid, index);
        if (offer == null) {
            return;
        }
        abortCountdown(s);
        s.queue(db, c -> TradeStore.withdrawEscrow(c, s.id, offer.id())).whenComplete((ok, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "[trade] Could not take back " + describe(offer.item()) + " for " + player.getName(), error);
                if (!s.addOffer(uuid, offer)) {
                    // The trade ended meanwhile; its cancel returned the item as a claim.
                    deliverClaims(player, false);
                }
            } else {
                deliverClaims(player, false);
            }
            refresh(s);
        });
        refresh(s);
    }

    private void toggleReady(TradeSession s, Player player) {
        boolean both = s.toggleReady(player.getUniqueId());
        if (both) {
            startCountdown(s);
        } else {
            abortCountdown(s);
        }
        refresh(s);
    }

    // ------------------------------------------------------------------ countdown and completion

    private void startCountdown(TradeSession s) {
        if (s.countingDown()) {
            return;
        }
        int version = s.version();
        s.countdownTask(null, countdownSeconds);
        notifyBoth(s, "&aBoth ready - the trade completes in &e" + countdownSeconds + " seconds&a. Change your offer or click your dye to cancel.");
        ScheduledTask task = Tasks.global.timer(t -> tick(s, version, t), 1, 20);
        s.countdownTask(task, countdownSeconds);
        if (s.version() != version || !s.bothReady()) {
            s.stopCountdown(); // changed while starting
        }
    }

    private void tick(TradeSession s, int version, ScheduledTask task) {
        if (!s.bothReady() || s.version() != version) {
            task.cancel();
            return;
        }
        int left = s.countdown();
        if (left <= 0) {
            s.stopCountdown();
            complete(s, version);
            return;
        }
        refresh(s);
        float pitch = 0.8f + 0.2f * (countdownSeconds - left);
        for (UUID id : new UUID[]{s.a, s.b}) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Tasks.onEntity(p, () -> {
                    p.sendActionBar(LEGACY.deserialize("&aTrade completing in &e" + left + "&a... &7(change your offer to cancel)"));
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, pitch);
                });
            }
        }
        s.countdown(left - 1);
    }

    private void abortCountdown(TradeSession s) {
        if (!s.countingDown()) {
            return;
        }
        s.stopCountdown();
        notifyBoth(s, "&cTrade countdown canceled - both of you need to confirm again.");
    }

    /** Checklist 2: one transaction decides the outcome; the escrow in the database must be what both accepted. */
    private void complete(TradeSession s, int version) {
        Map<String, UUID> expected = s.beginCompletion(version);
        if (expected == null) {
            refresh(s);
            return;
        }
        List<TradeSession.Offer> fromA = s.offers(s.a);
        List<TradeSession.Offer> fromB = s.offers(s.b);
        s.queue(db, c -> TradeStore.complete(c, s, expected)).whenComplete((result, error) -> {
            if (error != null || result == TradeStore.CompleteResult.CHANGED) {
                if (error != null) {
                    logger.log(Level.WARNING, "[trade] Trade " + s.id + " could not be completed", error);
                }
                String pendingCancel = s.revertCompletion();
                notifyBoth(s, "&cThe trade changed before it could complete - both of you need to confirm again.");
                if (pendingCancel != null) {
                    cancel(s, null, "Trade canceled.", "Trade canceled.", null);
                } else {
                    refresh(s);
                }
                return;
            }
            if (result == TradeStore.CompleteResult.NOT_OPEN) {
                s.state(TradeSession.State.CANCELLED);
                end(s);
                deliverBoth(s);
                return;
            }
            s.state(TradeSession.State.DONE);
            end(s);
            // Checklist 11.
            logger.info("[trade] " + s.nameA + " and " + s.nameB + " traded (" + s.id + "): " + s.nameA + " gave " + describe(fromA)
                    + "; " + s.nameB + " gave " + describe(fromB));
            message(s.a, Msg.good("Trade complete with <yellow>" + Msg.escape(s.nameB) + "</yellow>!"));
            message(s.b, Msg.good("Trade complete with <yellow>" + Msg.escape(s.nameA) + "</yellow>!"));
            deliverBoth(s);
        });
    }

    // ------------------------------------------------------------------ ending a trade

    private void cancelByPlayer(TradeSession s, Player initiator) {
        cancel(s, initiator.getUniqueId(), "You canceled the trade.", Msg.escape(initiator.getName()) + " canceled the trade.", null);
    }

    /**
     * Ends an open trade exactly once and returns escrow to its owners. {@code selfMessage} goes to {@code initiator},
     * {@code otherMessage} to the other player (or to both when there is no initiator). Items owned by
     * {@code droppedOwner} were dropped at their death and are not returned.
     */
    private void cancel(TradeSession s, @Nullable UUID initiator, @Nullable String selfMessage, @Nullable String otherMessage,
                        @Nullable UUID droppedOwner) {
        if (!s.beginCancel("cancel")) {
            return; // already ended, or completion in flight (remembered and acted on if it fails)
        }
        end(s);
        for (UUID id : new UUID[]{s.a, s.b}) {
            String text = initiator != null && id.equals(initiator) ? selfMessage : otherMessage;
            if (text != null) {
                message(id, Msg.bad(text));
            }
        }
        s.queue(db, c -> TradeStore.cancel(c, s.id, droppedOwner)).whenComplete((ok, error) -> {
            if (error != null) {
                logger.log(Level.SEVERE, "[trade] Trade " + s.id + " could not be cancelled in the database; its escrow is returned at the next start", error);
                return;
            }
            logger.info("[trade] Trade " + s.id + " between " + s.nameA + " and " + s.nameB + " was canceled; escrow returned"
                    + (droppedOwner != null ? " (the items of the player who died were dropped)" : ""));
            deliverBoth(s);
        });
    }

    /** Removes the session and closes both views on their own threads. */
    private void end(TradeSession s) {
        sessions.remove(s.a, s);
        sessions.remove(s.b, s);
        for (UUID id : new UUID[]{s.a, s.b}) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Tasks.onEntity(p, () -> {
                    if (p.getOpenInventory().getTopInventory().getHolder() instanceof TradeView v && v.session == s) {
                        v.getInventory().clear();
                        p.closeInventory();
                    }
                });
            }
        }
    }

    /** Shutdown only: views must not keep showing item copies once our click listener is gone. */
    private void clearViews(TradeSession s) {
        for (UUID id : new UUID[]{s.a, s.b}) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getOpenInventory().getTopInventory().getHolder() instanceof TradeView v && v.session == s) {
                v.getInventory().clear();
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (stopping || !(event.getInventory().getHolder() instanceof TradeView view) || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        view.getInventory().clear();
        InventoryCloseEvent.Reason reason = event.getReason();
        if (reason == InventoryCloseEvent.Reason.DEATH || reason == InventoryCloseEvent.Reason.DISCONNECT) {
            return; // the death and quit handlers end the trade (death must drop, not return)
        }
        TradeSession s = view.session;
        if (sessions.get(player.getUniqueId()) == s) {
            cancelByPlayer(s, player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        pendingRequests.remove(uuid);
        pendingRequests.values().removeIf(r -> r.requester().equals(uuid));
        TradeSession s = sessions.get(uuid);
        if (s != null) {
            cancel(s, uuid, null, "Trade canceled - <yellow>" + Msg.escape(player.getName()) + "</yellow> disconnected.", null);
        }
    }

    /** Checklist 5: death. The dead player's escrow drops with their inventory unless the inventory is kept. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        TradeSession s = sessions.get(uuid);
        if (s == null) {
            return;
        }
        String self = "Trade canceled - you died.";
        String other = "Trade canceled - <yellow>" + Msg.escape(player.getName()) + "</yellow> died.";
        if (event.isCancelled() || event.getKeepInventory() || !dropOnDeath) {
            cancel(s, uuid, self, other, null);
            return;
        }
        if (!s.beginCancel("death")) {
            return;
        }
        List<TradeSession.Offer> mine = s.takeAll(uuid);
        for (TradeSession.Offer o : mine) {
            s.markDropped(o.id());
            event.getDrops().add(o.item().clone());
        }
        logger.info("[trade] " + player.getName() + " died in trade " + s.id + "; their offer drops with them: " + describe(mine));
        // beginCancel already ran, so finish the cancel by hand.
        end(s);
        message(uuid, Msg.bad(self));
        message(s.other(uuid), Msg.bad(other));
        s.queue(db, c -> TradeStore.cancel(c, s.id, uuid)).whenComplete((ok, error) -> {
            if (error != null) {
                logger.log(Level.SEVERE, "[trade] Trade " + s.id + " could not be cancelled after a death", error);
                return;
            }
            deliverBoth(s);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        TradeSession s = sessions.get(event.getPlayer().getUniqueId());
        if (s == null || cancelDistance <= 0) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() != to.getWorld() || from.distanceSquared(to) > cancelDistance * cancelDistance) {
            Player player = event.getPlayer();
            cancel(s, player.getUniqueId(), "Trade canceled - you teleported away.",
                    "Trade canceled - <yellow>" + Msg.escape(player.getName()) + "</yellow> teleported away.", null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Tasks.entity.later(player, () -> deliverClaims(player, false), 40);
    }

    // ------------------------------------------------------------------ handing items over

    private void deliverBoth(TradeSession s) {
        for (UUID id : new UUID[]{s.a, s.b}) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                deliverClaims(p, false);
            }
        }
    }

    /**
     * Gives the player every item waiting for them. Each claim is won with a single-winner UPDATE (checklist 2), only one
     * delivery runs per player at a time (checklist 3), items go in on the player's thread, and what does not fit stays
     * claimable (checklist 6). A player who left before the items could be given gets the claims back.
     */
    void deliverClaims(Player player, boolean reportEmpty) {
        UUID uuid = player.getUniqueId();
        if (!delivering.add(uuid)) {
            return;
        }
        db.async(c -> TradeStore.claimAll(c, uuid)).whenComplete((won, error) -> {
            if (error != null) {
                delivering.remove(uuid);
                logger.log(Level.WARNING, "[trade] Could not load trade claims for " + player.getName(), error);
                return;
            }
            if (won.isEmpty()) {
                delivering.remove(uuid);
                if (reportEmpty) {
                    Tasks.entity.run(player, () -> player.sendMessage(Msg.neutral("You have no trade items waiting.")));
                }
                return;
            }
            ScheduledTask t = Tasks.entity.later(player, () -> {
                try {
                    give(player, won);
                } finally {
                    delivering.remove(uuid);
                }
            }, () -> {
                release(won);
                delivering.remove(uuid);
            }, 1);
            if (t == null) {
                release(won);
                delivering.remove(uuid);
            }
        });
    }

    private void give(Player player, List<TradeStore.Claim> won) {
        if (!player.isOnline()) {
            release(won);
            return;
        }
        List<ItemStack> leftovers = new ArrayList<>();
        int given = 0;
        for (TradeStore.Claim claim : won) {
            leftovers.addAll(player.getInventory().addItem(claim.item().clone()).values());
            given++;
        }
        PlayerFiles.save(player); // checklist 7: the player file now holds what the claims gave
        logger.info("[trade] Gave " + player.getName() + " " + describeClaims(won));
        if (!leftovers.isEmpty()) {
            keepOrDrop(player, leftovers);
            player.sendMessage(Msg.neutral("Your inventory is full - " + Msg.white(String.valueOf(leftovers.size()))
                    + " stack(s) are waiting. Free up space and use " + Msg.white("/tradeclaim") + "."));
        } else if (given > 0) {
            player.sendMessage(Msg.good("You received your trade items."));
        }
    }

    /** Checklist 6: what did not fit becomes a new claim; only if that write fails is it dropped at the player's feet. */
    private void keepOrDrop(Player player, List<ItemStack> leftovers) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack i : leftovers) {
            copy.add(i.clone());
        }
        db.asyncRun(c -> Database.transaction(c, tx -> {
            for (ItemStack i : copy) {
                TradeStore.insertClaim(tx, uuid, name, TradeStore.encode(i), "trade-overflow", null);
            }
            return null;
        })).exceptionally(e -> {
            logger.log(Level.SEVERE, "[trade] Could not store overflow for " + name + "; dropping it at their feet", e);
            Tasks.entity.run(player, () -> {
                for (ItemStack i : copy) {
                    player.getWorld().dropItemNaturally(player.getLocation(), i);
                }
            });
            return null;
        });
    }

    /** An item whose escrow write failed exists only in memory: hand it straight back (or store it if they left). */
    private void returnUnstored(UUID owner, String name, ItemStack item) {
        Player p = Bukkit.getPlayer(owner);
        if (p != null) {
            ScheduledTask t = Tasks.entity.later(p, () -> giveBack(p, List.of(item)), () -> storeClaim(owner, name, item), 1);
            if (t != null) {
                return;
            }
        }
        storeClaim(owner, name, item);
    }

    private void storeClaim(UUID owner, String name, ItemStack item) {
        db.asyncRun(c -> TradeStore.insertClaim(c, owner, name, TradeStore.encode(item), "trade-return", null)).exceptionally(e -> {
            logger.log(Level.SEVERE, "[trade] LOST " + describe(item) + " for " + name + " (" + owner + "): could not store it", e);
            return null;
        });
    }

    /** On the player's thread: puts items straight into the inventory; overflow becomes a claim. */
    private void giveBack(Player player, List<ItemStack> items) {
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack i : items) {
            leftovers.addAll(player.getInventory().addItem(i.clone()).values());
        }
        PlayerFiles.save(player);
        if (!leftovers.isEmpty()) {
            keepOrDrop(player, leftovers);
        }
    }

    private void release(List<TradeStore.Claim> won) {
        db.asyncRun(c -> {
            for (TradeStore.Claim claim : won) {
                TradeStore.release(c, claim.id());
            }
        }).exceptionally(e -> {
            logger.log(Level.SEVERE, "[trade] Could not release trade claims " + won.stream().map(TradeStore.Claim::id).toList(), e);
            return null;
        });
    }

    // ------------------------------------------------------------------ helpers

    private void refresh(TradeSession s) {
        for (UUID id : new UUID[]{s.a, s.b}) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Tasks.onEntity(p, () -> {
                    if (p.getOpenInventory().getTopInventory().getHolder() instanceof TradeView v && v.session == s
                            && s.state() == TradeSession.State.OPEN) {
                        v.render();
                    }
                });
            }
        }
    }

    private void notifyBoth(TradeSession s, String legacyMessage) {
        Component line = LEGACY.deserialize(legacyMessage);
        message(s.a, line);
        message(s.b, line);
    }

    private static void message(UUID id, Component line) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            Tasks.onEntity(p, () -> p.sendMessage(line));
        }
    }

    private static String describe(ItemStack item) {
        return item.getAmount() + "x " + item.getType().name().toLowerCase(Locale.ROOT);
    }

    private static String describe(List<TradeSession.Offer> offers) {
        if (offers.isEmpty()) {
            return "nothing";
        }
        List<String> parts = new ArrayList<>();
        for (TradeSession.Offer o : offers) {
            parts.add(describe(o.item()));
        }
        return String.join(", ", parts);
    }

    private static String describeClaims(List<TradeStore.Claim> claims) {
        List<String> parts = new ArrayList<>();
        for (TradeStore.Claim c : claims) {
            parts.add(describe(c.item()));
        }
        return String.join(", ", parts);
    }
}
