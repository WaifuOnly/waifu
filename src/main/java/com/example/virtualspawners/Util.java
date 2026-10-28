package com.example.virtualspawners;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/** Small helpers shared by the mod and the menu. */
final class Util {
    private Util() {}

    static Component text(String s, ChatFormatting color) {
        return Component.literal(s).withStyle(style -> style.withColor(color).withItalic(false));
    }

    // ---- items looked up by registry id, so we never depend on field names in Items ----

    private static final Map<String, Item> ITEM_CACHE = new HashMap<>();

    static Item item(String id) {
        String full = id.contains(":") ? id : "minecraft:" + id;
        Item cached = ITEM_CACHE.get(full);
        if (cached != null) return cached;
        Item found = null;
        for (Item it : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.getKey(it).toString().equals(full)) {
                found = it;
                break;
            }
        }
        if (found == null) {
            VirtualSpawnersMod.LOGGER.warn("Unknown item '{}' in config, showing paper instead.", full);
            found = Items.PAPER;
        }
        ITEM_CACHE.put(full, found);
        return found;
    }

    static ItemStack named(Item item, int count, Component name, Component... lore) {
        ItemStack stack = new ItemStack(item, count);
        stack.set(DataComponents.CUSTOM_NAME, name);
        if (lore.length > 0) stack.set(DataComponents.LORE, new ItemLore(List.of(lore)));
        return stack;
    }

    static ItemStack named(Item item, Component name, Component... lore) {
        return named(item, 1, name, lore);
    }

    static String fmt(double n) {
        return String.format("%,d", (long) Math.floor(n));
    }

    // ---- optional integration with the Simple Login mod (if installed) ----

    private static Method authMethod;
    private static boolean authLooked;

    /** True if the player may act. If Simple Login is not installed, everyone may. */
    static boolean authed(ServerPlayer p) {
        if (!authLooked) {
            authLooked = true;
            try {
                Class<?> c = Class.forName("com.example.simplelogin.SimpleLoginMod");
                authMethod = c.getMethod("isAuthenticated", ServerPlayer.class);
            } catch (Throwable t) {
                authMethod = null;
            }
        }
        if (authMethod == null) return true;
        try {
            return (boolean) authMethod.invoke(null, p);
        } catch (Throwable t) {
            return true;
        }
    }
}
