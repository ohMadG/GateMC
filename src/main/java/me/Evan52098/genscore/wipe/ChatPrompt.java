package me.Evan52098.genscore.wipe;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.Evan52098.genscore.scheduler.Tasks;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Takes the next chat line of a player as input (blockville-common's ChatInputPrompt), used for the staff-only wipe
 * reason. The chat event is async; the answer is handed over on the player's own thread. Prompts expire after a minute.
 */
final class ChatPrompt implements Listener {

    private static final long EXPIRE_MS = 60_000L;

    private record Waiting(Consumer<String> answer, long until) {
    }

    private final Map<UUID, Waiting> waiting = new ConcurrentHashMap<>();

    /** Call on the player's thread. A newer prompt replaces an older one. */
    void await(Player player, Consumer<String> answer) {
        waiting.put(player.getUniqueId(), new Waiting(answer, System.currentTimeMillis() + EXPIRE_MS));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Waiting w = waiting.remove(player.getUniqueId());
        if (w == null) {
            return;
        }
        if (System.currentTimeMillis() > w.until()) {
            return; // expired: this is normal chat again
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Tasks.entity.run(player, () -> w.answer().accept(text));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        waiting.remove(event.getPlayer().getUniqueId());
    }
}
