package de.meinplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ItemManager {

    private final MeinPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final NamespacedKey idKey;
    private final Map<String, ItemStack> items = new LinkedHashMap<>();
    private final List<NamespacedKey> recipeKeys = new ArrayList<>();

    public ItemManager(MeinPlugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "item_id");
    }

    public void load() {
        unload();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("items");
        if (root == null) return;

        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            try {
                ItemStack item = build(id, s);
                items.put(id.toLowerCase(), item);
                registerRecipe(id.toLowerCase(), item, s.getConfigurationSection("recipe"));
            } catch (Exception e) {
                plugin.getLogger().warning("Item '" + id + "' ist fehlerhaft: " + e.getMessage());
            }
        }
        plugin.getLogger().info(items.size() + " Custom-Items geladen.");
    }

    public void unload() {
        for (NamespacedKey key : recipeKeys) Bukkit.removeRecipe(key);
        recipeKeys.clear();
        items.clear();
    }

    private ItemStack build(String id, ConfigurationSection s) {
        Material mat = Material.matchMaterial(s.getString("material", ""));
        if (mat == null || !mat.isItem()) {
            throw new IllegalArgumentException("Unbekanntes Material '" + s.getString("material") + "'");
        }
        ItemStack item = new ItemStack(mat, Math.max(1, s.getInt("amount", 1)));

        item.editMeta(meta -> {
            String name = s.getString("name");
            if (name != null) {
                meta.displayName(mm.deserialize(name).decoration(TextDecoration.ITALIC, false));
            }
            List<String> lore = s.getStringList("lore");
            if (!lore.isEmpty()) {
                List<Component> lines = new ArrayList<>();
                for (String l : lore) lines.add(mm.deserialize(l).decoration(TextDecoration.ITALIC, false));
                meta.lore(lines);
            }
            if (s.contains("custom-model-data")) {
                meta.setCustomModelData(s.getInt("custom-model-data"));
            }
            meta.setUnbreakable(s.getBoolean("unbreakable", false));

            ConfigurationSection ench = s.getConfigurationSection("enchants");
            if (ench != null) {
                for (String k : ench.getKeys(false)) {
                    Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(k.toLowerCase()));
                    if (e != null) meta.addEnchant(e, ench.getInt(k), true);
                    else plugin.getLogger().warning("Unbekannte Verzauberung: " + k);
                }
            }
            meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id);
        });
        return item;
    }

    private void registerRecipe(String id, ItemStack result, ConfigurationSection r) {
        if (r == null) return;
        NamespacedKey key = new NamespacedKey(plugin, id);
        Recipe recipe;

        if (r.contains("shape")) {
            ShapedRecipe shaped = new ShapedRecipe(key, result.clone());
            shaped.shape(r.getStringList("shape").toArray(new String[0]));
            ConfigurationSection ing = r.getConfigurationSection("ingredients");
            if (ing == null) throw new IllegalArgumentException("'ingredients' fehlt");
            for (String k : ing.getKeys(false)) {
                shaped.setIngredient(k.charAt(0), material(ing.getString(k, "")));
            }
            recipe = shaped;
        } else {
            ShapelessRecipe shapeless = new ShapelessRecipe(key, result.clone());
            for (String m : r.getStringList("shapeless")) shapeless.addIngredient(material(m));
            recipe = shapeless;
        }

        if (Bukkit.addRecipe(recipe)) recipeKeys.add(key);
    }

    private Material material(String name) {
        Material m = Material.matchMaterial(name);
        if (m == null) throw new IllegalArgumentException("Unbekannte Zutat '" + name + "'");
        return m;
    }

    public ItemStack get(String id) {
        ItemStack i = items.get(id.toLowerCase());
        return i == null ? null : i.clone();
    }

    public Set<String> ids() {
        return items.keySet();
    }

    public List<NamespacedKey> recipeKeys() {
        return recipeKeys;
    }
}
