package de.meinplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class MeinPlugin extends JavaPlugin implements Listener, TabExecutor {

    private final MiniMessage mm = MiniMessage.miniMessage();
    private ItemManager items;
    private AiChat ai;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        items = new ItemManager(this);
        items.load();
        ai = new AiChat(this);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(ai, this);

        getCommand("ai").setExecutor(ai);
        getCommand("mpgive").setExecutor(this);
        getCommand("mpreload").setExecutor(this);
        getLogger().info("MeinPlugin aktiviert.");
    }

    @Override
    public void onDisable() {
        if (items != null) items.unload();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        p.discoverRecipes(items.recipeKeys());

        FileConfiguration c = getConfig();
        String url = c.getString("resourcepack.url", "");
        if (c.getBoolean("resourcepack.enabled") && !url.isBlank()) {
            String sha = c.getString("resourcepack.sha1", "");
            p.setResourcePack(
                url,
                sha.isBlank() ? null : sha,
                c.getBoolean("resourcepack.required"),
                mm.deserialize(c.getString("resourcepack.prompt", ""))
            );
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase()) {
            case "mpreload" -> {
                reloadConfig();
                items.load();
                for (Player p : Bukkit.getOnlinePlayers()) p.discoverRecipes(items.recipeKeys());
                sender.sendMessage(Component.text("Config neu geladen.", NamedTextColor.GREEN));
                return true;
            }
            case "mpgive" -> {
                if (args.length < 1) return false;
                ItemStack item = items.get(args[0]);
                if (item == null) {
                    sender.sendMessage(Component.text("Unbekanntes Item: " + args[0], NamedTextColor.RED));
                    return true;
                }
                Player target = args.length > 1 ? Bukkit.getPlayer(args[1])
                        : (sender instanceof Player p ? p : null);
                if (target == null) {
                    sender.sendMessage(Component.text("Spieler nicht gefunden.", NamedTextColor.RED));
                    return true;
                }
                target.getInventory().addItem(item).values()
                        .forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));
                sender.sendMessage(Component.text("Item gegeben.", NamedTextColor.GREEN));
                return true;
            }
            default -> { return false; }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("mpgive")) {
            if (args.length == 1) {
                return items.ids().stream().filter(i -> i.startsWith(args[0].toLowerCase())).toList();
            }
            return null; // Standard: Spielernamen
        }
        return List.of();
    }
}
