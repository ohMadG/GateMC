package me.Evan52098.genscore.punish;

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

final class ChatInput implements Listener {

    private record Waiting(Consumer<String> handler, long expiresAt) {
    }

    private final Map<UUID, Waiting> waiting = new ConcurrentHashMap<>();

    void expect(Player player, long millis, Consumer<String> handler) {
        waiting.put(player.getUniqueId(), new Waiting(handler, System.currentTimeMillis() + millis));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Waiting w = waiting.remove(player.getUniqueId());
        if (w == null || w.expiresAt() < System.currentTimeMillis()) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Tasks.entity.run(player, () -> w.handler().accept(text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        waiting.remove(event.getPlayer().getUniqueId());
    }
}
