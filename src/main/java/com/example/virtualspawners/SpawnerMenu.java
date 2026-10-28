package com.example.virtualspawners;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/**
 * The storage GUI of a virtual spawner (a 3-row chest menu that works with vanilla clients).
 *
 *   top row     : info card in the middle
 *   middle row  : Collect Drops | Collect XP | Collect All
 *   bottom row  : the drops currently stored
 */
class SpawnerMenu extends ChestMenu {

    private static final int INFO_SLOT = 4;
    private static final int DROPS_SLOT = 10;
    private static final int XP_SLOT = 13;
    private static final int ALL_SLOT = 16;
    private static final int[] STORED_SLOTS = {19, 20, 21, 22, 23, 24, 25};

    private final Container container;
    private final ServerLevel level;
    private final BlockPos pos;
    private final String key;
    private final VirtualSpawnersMod.SpawnerData data;

    SpawnerMenu(int containerId, Inventory inv, ServerLevel level, BlockPos pos, String key,
                VirtualSpawnersMod.SpawnerData data) {
        this(containerId, inv, new SimpleContainer(27), level, pos, key, data);
    }

    private SpawnerMenu(int containerId, Inventory inv, Container container, ServerLevel level, BlockPos pos,
                        String key, VirtualSpawnersMod.SpawnerData data) {
        super(MenuType.GENERIC_9x3, containerId, inv, container, 3);
        this.container = container;
        this.level = level;
        this.pos = pos;
        this.key = key;
        this.data = data;
        // Replace the chest slots with display-only slots that trigger actions when clicked.
        for (int i = 0; i < 27; i++) {
            this.slots.set(i, new ActionSlot(this, container, i));
        }
        refresh();
    }

    private ItemStack pane() {
        return Util.named(Util.item("gray_stained_glass_pane"), Component.literal(" "));
    }

    void refresh() {
        VirtualSpawnersMod.produce(data);
        VirtualSpawnersMod.TypeCfg t = VirtualSpawnersMod.config.types.get(data.type);
        for (int i = 0; i < 27; i++) container.setItem(i, pane());
        if (t == null) return;

        int interval = Math.max(1, VirtualSpawnersMod.config.intervalSeconds);
        double factor = VirtualSpawnersMod.effectiveFactor(data);

        // Info card
        List<Component> info = new ArrayList<>();
        info.add(Util.text("Stack size: " + data.count, ChatFormatting.YELLOW));
        info.add(Util.text("Effective output: " + String.format("%.2f", factor) + "x", ChatFormatting.GRAY));
        info.add(Util.text("Every " + interval + "s it produces:", ChatFormatting.GRAY));
        for (Map.Entry<String, Double> e : t.drops.entrySet()) {
            info.add(Util.text(" - " + String.format("%.1f", e.getValue() * factor) + " " + prettyName(e.getKey()), ChatFormatting.WHITE));
        }
        info.add(Util.text(" - " + String.format("%.0f", t.xp * factor) + " XP", ChatFormatting.GREEN));
        info.add(Util.text("", ChatFormatting.GRAY));
        info.add(Util.text("No mobs spawn. Hoppers do not pull drops.", ChatFormatting.DARK_GRAY));
        container.setItem(INFO_SLOT, Util.named(Util.item(t.icon),
                Util.text(t.name, ChatFormatting.LIGHT_PURPLE), info.toArray(new Component[0])));

        long totalItems = 0;
        for (double v : data.drops.values()) totalItems += (long) Math.floor(v);

        container.setItem(DROPS_SLOT, Util.named(Util.item("chest"),
                Util.text("Collect Drops", ChatFormatting.GREEN),
                Util.text("Stored items: " + Util.fmt(totalItems), ChatFormatting.GRAY),
                Util.text("Click to move them to your inventory.", ChatFormatting.YELLOW),
                Util.text("Up to " + Util.fmt(VirtualSpawnersMod.config.maxItemsPerClick) + " items per click.", ChatFormatting.DARK_GRAY)));
        container.setItem(XP_SLOT, Util.named(Util.item("experience_bottle"),
                Util.text("Collect XP", ChatFormatting.YELLOW),
                Util.text("Stored XP: " + Util.fmt(data.xp), ChatFormatting.GRAY),
                Util.text("Click to collect.", ChatFormatting.YELLOW)));
        container.setItem(ALL_SLOT, Util.named(Util.item("hopper"),
                Util.text("Collect Everything", ChatFormatting.AQUA),
                Util.text("Drops and XP at once.", ChatFormatting.GRAY)));

        // Stored drops preview
        int idx = 0;
        for (Map.Entry<String, Double> e : data.drops.entrySet()) {
            long whole = (long) Math.floor(e.getValue());
            if (whole <= 0 || idx >= STORED_SLOTS.length) continue;
            container.setItem(STORED_SLOTS[idx++], Util.named(Util.item(e.getKey()), (int) Math.min(64, whole),
                    Util.text(prettyName(e.getKey()), ChatFormatting.WHITE),
                    Util.text("Stored: " + Util.fmt(whole), ChatFormatting.GRAY)));
        }
        if (idx == 0) {
            container.setItem(STORED_SLOTS[0], Util.named(Util.item("barrier"),
                    Util.text("No drops stored yet", ChatFormatting.RED)));
        }
    }

    private static String prettyName(String id) {
        String s = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        StringBuilder out = new StringBuilder();
        for (String part : s.split("_")) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    /** Called (server side) when a player clicks one of the GUI slots. */
    void onSlotClick(Player player, int slot) {
        if (!(player instanceof ServerPlayer sp)) return;
        if (!Util.authed(sp)) {
            sp.closeContainer();
            return;
        }
        // The block may have been destroyed while the menu was open.
        if (!level.getBlockState(pos).is(Blocks.SPAWNER) || !VirtualSpawnersMod.spawners.containsKey(key)) {
            VirtualSpawnersMod.spawners.remove(key);
            VirtualSpawnersMod.saveSpawners();
            sp.closeContainer();
            return;
        }

        VirtualSpawnersMod.produce(data);
        int max = VirtualSpawnersMod.config.maxItemsPerClick;
        if (slot == DROPS_SLOT || slot == ALL_SLOT) {
            int moved = VirtualSpawnersMod.giveDrops(sp, data, max);
            if (moved > 0) {
                sp.sendSystemMessage(Util.text("Collected " + Util.fmt(moved) + " items.", ChatFormatting.GREEN), true);
                sp.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
            } else if (slot == DROPS_SLOT) {
                sp.sendSystemMessage(Util.text(VirtualSpawnersMod.hasStoredItems(data)
                        ? "Your inventory is full." : "No drops stored.", ChatFormatting.RED), true);
                sp.playSound(SoundEvents.VILLAGER_NO, 0.8f, 1.0f);
            }
        }
        if (slot == XP_SLOT || slot == ALL_SLOT) {
            int xp = VirtualSpawnersMod.giveXp(sp, data);
            if (xp > 0) {
                sp.sendSystemMessage(Util.text("Collected " + Util.fmt(xp) + " XP.", ChatFormatting.GREEN), true);
                sp.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 1.0f);
            } else if (slot == XP_SLOT) {
                sp.sendSystemMessage(Util.text("No XP stored.", ChatFormatting.RED), true);
                sp.playSound(SoundEvents.VILLAGER_NO, 0.8f, 1.0f);
            }
        }
        VirtualSpawnersMod.saveSpawners();
        refresh();
    }

    /** A display-only slot: picking it up runs the menu action instead of moving an item. */
    private static class ActionSlot extends Slot {
        private final SpawnerMenu menu;
        private final int slotIndex;

        ActionSlot(SpawnerMenu menu, Container container, int slotIndex) {
            super(container, slotIndex, 0, 0);
            this.menu = menu;
            this.slotIndex = slotIndex;
        }

        @Override
        public boolean mayPickup(Player player) {
            menu.onSlotClick(player, slotIndex);
            return false;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }
}
