package de.meinplugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AiChat implements CommandExecutor, Listener {

    private static final URI API = URI.create("https://api.anthropic.com/v1/messages");

    private final MeinPlugin plugin;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Set<UUID> chatMode = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Deque<JsonObject>> history = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRequest = new ConcurrentHashMap<>();

    public AiChat(MeinPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Nur für Spieler.");
            return true;
        }
        UUID id = p.getUniqueId();

        if (args.length == 0) {
            if (chatMode.remove(id)) {
                p.sendMessage(Component.text("KI-Chat beendet. Du schreibst wieder normal im Chat.", NamedTextColor.YELLOW));
            } else {
                chatMode.add(id);
                p.sendMessage(Component.text("KI-Chat aktiv: Alles, was du schreibst, geht an die KI. Mit /ai beendest du ihn.", NamedTextColor.LIGHT_PURPLE));
            }
        } else if (args[0].equalsIgnoreCase("reset")) {
            history.remove(id);
            p.sendMessage(Component.text("Verlauf gelöscht.", NamedTextColor.YELLOW));
        } else {
            ask(p, String.join(" ", args));
        }
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player p = event.getPlayer();
        if (!chatMode.contains(p.getUniqueId())) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> ask(p, text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        chatMode.remove(id);
        history.remove(id);
        lastRequest.remove(id);
    }

    private void ask(Player p, String text) {
        FileConfiguration cfg = plugin.getConfig();
        String key = cfg.getString("ai.api-key", "");
        if (key.isBlank()) {
            p.sendMessage(Component.text("Die KI ist nicht eingerichtet (api-key fehlt in der config.yml).", NamedTextColor.RED));
            return;
        }

        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        long cooldown = cfg.getLong("ai.cooldown-seconds", 3) * 1000L;
        Long last = lastRequest.get(id);
        if (last != null && now - last < cooldown) {
            p.sendMessage(Component.text("Bitte warte kurz zwischen Nachrichten.", NamedTextColor.RED));
            return;
        }
        lastRequest.put(id, now);

        Deque<JsonObject> hist = history.computeIfAbsent(id, k -> new ArrayDeque<>());
        JsonArray messages = new JsonArray();
        synchronized (hist) {
            hist.addLast(message("user", text));
            int max = Math.max(2, cfg.getInt("ai.history-size", 10));
            while (hist.size() > max) hist.removeFirst();
            while (!hist.isEmpty() && !"user".equals(hist.peekFirst().get("role").getAsString())) hist.removeFirst();
            hist.forEach(messages::add);
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.getString("ai.model", "claude-sonnet-4-6"));
        body.addProperty("max_tokens", cfg.getInt("ai.max-tokens", 300));
        body.addProperty("system", cfg.getString("ai.system-prompt", ""));
        body.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder(API)
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        p.sendMessage(Component.text("KI denkt nach ...", NamedTextColor.GRAY));

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(res -> {
                    if (res.statusCode() != 200) {
                        plugin.getLogger().warning("KI-API Fehler " + res.statusCode() + ": " + res.body());
                        dropLastUser(hist);
                        reply(id, Component.text("Die KI hat gerade einen Fehler (HTTP " + res.statusCode() + ").", NamedTextColor.RED));
                        return;
                    }
                    try {
                        JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
                        StringBuilder answer = new StringBuilder();
                        for (JsonElement el : json.getAsJsonArray("content")) {
                            JsonObject block = el.getAsJsonObject();
                            if ("text".equals(block.get("type").getAsString())) {
                                answer.append(block.get("text").getAsString());
                            }
                        }
                        String out = answer.toString().trim();
                        synchronized (hist) {
                            hist.addLast(message("assistant", out));
                        }
                        reply(id, Component.text("[KI] ", NamedTextColor.LIGHT_PURPLE)
                                .append(Component.text(out, NamedTextColor.WHITE)));
                    } catch (Exception e) {
                        plugin.getLogger().warning("KI-Antwort nicht lesbar: " + e.getMessage());
                        dropLastUser(hist);
                        reply(id, Component.text("Die Antwort der KI war nicht lesbar.", NamedTextColor.RED));
                    }
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("KI-Anfrage fehlgeschlagen: " + ex.getMessage());
                    dropLastUser(hist);
                    reply(id, Component.text("Die KI ist gerade nicht erreichbar.", NamedTextColor.RED));
                    return null;
                });
    }

    private void dropLastUser(Deque<JsonObject> hist) {
        synchronized (hist) {
            if (!hist.isEmpty() && "user".equals(hist.peekLast().get("role").getAsString())) {
                hist.removeLast();
            }
        }
    }

    private JsonObject message(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private void reply(UUID id, Component msg) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player pl = Bukkit.getPlayer(id);
            if (pl != null) pl.sendMessage(msg);
        });
    }
}
