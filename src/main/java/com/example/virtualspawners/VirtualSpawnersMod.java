package com.example.virtualspawners;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Virtual Spawners (server side only).
 *
 * A "virtual spawner" is a normal spawner block that never spawns mobs. Instead it
 * builds up drops and XP over time. Right-click it to open a storage menu.
 * Shift-right-click with the same spawner item to stack more into it.
 * No custom blocks or items are registered, so vanilla clients can join.
 */
public class VirtualSpawnersMod implements ModInitializer {

    public static final String MOD_ID = "virtualspawners";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Marker stored in the item's custom_data: {vspawner:"skeleton"}. */
    static final String TAG = "vspawner";
    private static final Pattern TAG_PATTERN = Pattern.compile(TAG + "\\s*:\\s*[\"']([a-z0-9_]+)[\"']");

    // ------------------------------------------------------------ data classes

    /** Settings for one spawner type (all editable in config/virtualspawners/config.json). */
    public static class TypeCfg {
        public String name;
        public String entity;               // entity shown inside the cage
        public String icon;                 // item shown in menus
        public String color;                // name colour (chat colour name, e.g. dark_purple)
        public double xp;                   // XP per generation cycle (one spawner)
        public Map<String, Double> drops = new LinkedHashMap<>(); // item id -> items per cycle

        TypeCfg color(String c) {
            this.color = c;
            return this;
        }

        TypeCfg(String name, String entity, String icon, double xp, Object... dropPairs) {
            this.name = name;
            this.entity = entity;
            this.icon = icon;
            this.xp = xp;
            for (int i = 0; i + 1 < dropPairs.length; i += 2) {
                drops.put((String) dropPairs[i], ((Number) dropPairs[i + 1]).doubleValue());
            }
        }
    }

    public static class Config {
        public int intervalSeconds = 60;              // one generation cycle
        public double stackScalingExponent = 0.85;    // output = cycles * stack^exponent (1.0 = linear)
        public int maxStoredHours = 24;               // production stops accumulating after this long
        public int maxItemsPerClick = 2304;           // cap per "collect" click
        public double maxStoredPerItem = 1_000_000;
        public double maxStoredXp = 1_000_000;
        public boolean ownerOnly = true;              // only the placer can open / break it
        public Map<String, TypeCfg> types = defaultTypes();

        static Map<String, TypeCfg> defaultTypes() {
            Map<String, TypeCfg> t = new LinkedHashMap<>();
            t.put("skeleton", new TypeCfg("Skeleton Spawner", "minecraft:skeleton", "bone", 40,
                    "bone", 8, "arrow", 8).color("white"));
            t.put("zombie", new TypeCfg("Zombie Spawner", "minecraft:zombie", "rotten_flesh", 40,
                    "rotten_flesh", 8, "iron_ingot", 0.2, "carrot", 0.1, "potato", 0.1).color("dark_green"));
            t.put("creeper", new TypeCfg("Creeper Spawner", "minecraft:creeper", "gunpowder", 40,
                    "gunpowder", 8).color("green"));
            t.put("wither_skeleton", new TypeCfg("Wither Skeleton Spawner", "minecraft:wither_skeleton", "wither_skeleton_skull", 40,
                    "bone", 8, "coal", 4, "wither_skeleton_skull", 0.2).color("dark_gray"));
            t.put("blaze", new TypeCfg("Blaze Spawner", "minecraft:blaze", "blaze_rod", 40,
                    "blaze_rod", 4).color("gold"));
            t.put("slime", new TypeCfg("Slime Spawner", "minecraft:slime", "slime_ball", 16,
                    "slime_ball", 12).color("green"));
            t.put("enderman", new TypeCfg("Enderman Spawner", "minecraft:enderman", "ender_pearl", 40,
                    "ender_pearl", 2).color("dark_purple"));
            return t;
        }
    }

    /** One placed virtual spawner (saved in config/virtualspawners/spawners.json). */
    public static class SpawnerData {
        public String type;
        public String owner;
        public String ownerName;
        public int count = 1;
        public String holo;   // UUID of the floating text above the spawner
        public long lastTick;
        public double xp;
        public Map<String, Double> drops = new LinkedHashMap<>();
    }

    private record Pending(ServerLevel level, UUID player, InteractionHand hand, String type,
                           BlockPos clicked, BlockPos adjacent, int countBefore) {}

    // ------------------------------------------------------------ state

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type SPAWNERS_TYPE = new TypeToken<Map<String, SpawnerData>>() {}.getType();

    static Config config = new Config();
    static Map<String, SpawnerData> spawners = new HashMap<>();
    private static Path dir;
    private static final List<Pending> PENDING = new CopyOnWriteArrayList<>();

    // ------------------------------------------------------------ init

    @Override
    public void onInitialize() {
        dir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        loadConfig();
        loadSpawners();

        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl)) {
                return InteractionResult.PASS;
            }
            if (!Util.authed(sp)) return InteractionResult.PASS; // login mod handles the blocking
            BlockPos pos = hit.getBlockPos();
            String key = key(sl, pos);
            SpawnerData sd = spawners.get(key);
            ItemStack held = sp.getItemInHand(hand);
            String heldType = typeOf(held);

            if (sd != null) {
                if (!sl.getBlockState(pos).is(Blocks.SPAWNER)) { // block vanished (explosion etc.)
                    removeHolo(sl, sd);
                    spawners.remove(key);
                    saveSpawners();
                    return InteractionResult.PASS;
                }
                if (sp.isShiftKeyDown()) {
                    // Shift + same spawner item = stack it in.
                    if (heldType != null && heldType.equals(sd.type)) {
                        if (!permitted(sp, sd)) return InteractionResult.FAIL;
                        produce(sd);
                        if (!sp.isCreative()) held.shrink(1);
                        sd.count++;
                        refreshHolo(sl, pos, sd);
                        burst(sl, pos);
                        saveSpawners();
                        sp.sendSystemMessage(Util.text("Stacked! " + cfgName(sd.type) + " x" + sd.count,
                                ChatFormatting.GREEN), true);
                        sp.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f);
                        return InteractionResult.SUCCESS;
                    }
                    return InteractionResult.PASS; // sneaking: let players build against it
                }
                if (!permitted(sp, sd)) {
                    sp.sendSystemMessage(Util.text("This spawner belongs to " + sd.ownerName + ".", ChatFormatting.RED), true);
                    return InteractionResult.FAIL;
                }
                open(sp, sl, pos, key, sd);
                return InteractionResult.SUCCESS;
            }

            // Placing a virtual spawner item: let vanilla place it, then register it next tick.
            if (heldType != null && config.types.containsKey(heldType)) {
                PENDING.add(new Pending(sl, sp.getUUID(), hand, heldType, pos,
                        pos.relative(hit.getDirection()), held.getCount()));
            }
            return InteractionResult.PASS;
        });

        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl)) return true;
            String key = key(sl, pos);
            SpawnerData sd = spawners.get(key);
            if (sd == null) return true;
            if (!state.is(Blocks.SPAWNER)) {
                removeHolo(sl, sd);
                spawners.remove(key);
                saveSpawners();
                return true;
            }
            if (!permitted(sp, sd)) {
                sp.sendSystemMessage(Util.text("This spawner belongs to " + sd.ownerName + ".", ChatFormatting.RED), true);
                return false;
            }
            pickUp(sp, sl, pos, key, sd);
            return false; // we handle removal ourselves (no vanilla XP drop)
        });

        ServerTickEvents.END_SERVER_TICK.register(VirtualSpawnersMod::tick);
        LOGGER.info("Virtual Spawners loaded: {} types, {} placed spawners.", config.types.size(), spawners.size());
    }

    // ------------------------------------------------------------ helpers

    static String key(ServerLevel level, BlockPos pos) {
        return level.dimension().toString() + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
    }

    static String cfgName(String type) {
        TypeCfg t = config.types.get(type);
        return t != null ? t.name : type;
    }

    /** Returns the virtual spawner type stored in an item, or null if it is not one. */
    static String typeOf(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.SPAWNER)) return null;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return null;
        Matcher m = TAG_PATTERN.matcher(cd.copyTag().toString());
        return m.find() ? m.group(1) : null;
    }

    static ItemStack createItem(String type, int count) {
        TypeCfg t = config.types.get(type);
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG, type);
        ItemStack stack = Util.named(Items.SPAWNER, count,
                Util.text(t != null ? t.name : type, ChatFormatting.LIGHT_PURPLE),
                Util.text("Stackable virtual spawner", colorOf(type)),
                Util.text("Virtual drop generator. No mobs spawn.", ChatFormatting.GRAY),
                Util.text("Right-click placed spawner to open storage.", ChatFormatting.GRAY),
                Util.text("Shift-right-click with same type to stack.", ChatFormatting.GRAY));
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    static boolean permitted(ServerPlayer sp, SpawnerData sd) {
        return !config.ownerOnly || sd.owner == null || sd.owner.equals(sp.getUUID().toString());
    }

    private static void open(ServerPlayer sp, ServerLevel sl, BlockPos pos, String key, SpawnerData sd) {
        produce(sd);
        ensureHolo(sl, pos, sd);
        sp.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new SpawnerMenu(id, inv, sl, pos, key, sd),
                Component.literal(cfgName(sd.type)).withStyle(ChatFormatting.LIGHT_PURPLE)));
        sp.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 0.8f);
    }


    // ------------------------------------------------------------ floating text (hologram)

    static String colorName(String type) {
        TypeCfg t = config.types.get(type);
        return t != null && t.color != null && !t.color.isBlank() ? t.color : "light_purple";
    }

    static ChatFormatting colorOf(String type) {
        ChatFormatting f = ChatFormatting.getByName(colorName(type));
        return f != null ? f : ChatFormatting.LIGHT_PURPLE;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void runCommand(ServerLevel level, String cmd) {
        try {
            MinecraftServer server = level.getServer();
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withLevel(level).withSuppressedOutput(), cmd);
        } catch (Exception e) {
            LOGGER.error("Command failed: {}", cmd, e);
        }
    }

    /** Removes the floating text of a spawner (if its chunk is loaded). */
    static void removeHolo(ServerLevel level, SpawnerData sd) {
        if (sd.holo == null) return;
        runCommand(level, "kill " + sd.holo);
    }

    /** Makes sure the floating text exists, recreating it if it was lost. */
    static void ensureHolo(ServerLevel level, BlockPos pos, SpawnerData sd) {
        try {
            if (sd.holo != null && level.getEntity(UUID.fromString(sd.holo)) != null) return;
        } catch (Exception ignored) {
            // fall through and recreate
        }
        refreshHolo(level, pos, sd);
    }

    /** (Re)creates the floating text above the spawner: "10x Zombie Spawner" + owner. */
    static void refreshHolo(ServerLevel level, BlockPos pos, SpawnerData sd) {
        TypeCfg t = config.types.get(sd.type);
        if (t == null) return;
        removeHolo(level, sd);
        UUID id = UUID.randomUUID();
        sd.holo = id.toString();
        long msb = id.getMostSignificantBits();
        long lsb = id.getLeastSignificantBits();
        String uuidNbt = "[I;" + (int) (msb >> 32) + "," + (int) msb + "," + (int) (lsb >> 32) + "," + (int) lsb + "]";
        String col = colorName(sd.type);
        String text = "[{text:\"\u2726 \",color:\"gold\"},"
                + "{text:\"" + sd.count + "x \",color:\"yellow\",bold:true},"
                + "{text:\"" + esc(t.name) + "\",color:\"" + col + "\",bold:true},"
                + "{text:\" \u2726\",color:\"gold\"},"
                + "{text:\"\\nOwner: " + esc(sd.ownerName) + "\",color:\"gray\",bold:false}]";
        String cmd = String.format(Locale.ROOT,
                "summon minecraft:text_display %.2f %.2f %.2f {UUID:%s,Tags:[\"vspawner_holo\"],billboard:\"center\","
                        + "shadow:1b,see_through:0b,background:0,line_width:220,text:%s,"
                        + "transformation:{translation:[0f,0f,0f],scale:[1.1f,1.1f,1.1f],"
                        + "left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f]}}",
                pos.getX() + 0.5, pos.getY() + 1.45, pos.getZ() + 0.5, uuidNbt, text);
        runCommand(level, cmd);
    }

    /** Little particle burst for placing / stacking. */
    private static void burst(ServerLevel level, BlockPos pos) {
        level.sendParticles(ParticleTypes.PORTAL, pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5,
                40, 0.4, 0.5, 0.4, 0.4);
    }

    // ------------------------------------------------------------ production (lazy, on access)

    static double effectiveFactor(SpawnerData sd) {
        return Math.pow(Math.max(1, sd.count), config.stackScalingExponent);
    }

    /** Adds the drops and XP generated since the last time this spawner was touched. */
    static void produce(SpawnerData sd) {
        TypeCfg t = config.types.get(sd.type);
        if (t == null) return;
        long now = System.currentTimeMillis();
        long intervalMs = Math.max(1, config.intervalSeconds) * 1000L;
        long elapsed = now - sd.lastTick;
        if (elapsed < 0) {
            sd.lastTick = now;
            return;
        }
        long cycles = elapsed / intervalMs;
        if (cycles <= 0) return;
        long maxCycles = Math.max(1, config.maxStoredHours * 3600L / Math.max(1, config.intervalSeconds));
        if (cycles > maxCycles) {
            cycles = maxCycles;
            sd.lastTick = now;
        } else {
            sd.lastTick += cycles * intervalMs;
        }
        double factor = effectiveFactor(sd) * cycles;
        for (Map.Entry<String, Double> e : t.drops.entrySet()) {
            double v = sd.drops.getOrDefault(e.getKey(), 0.0) + e.getValue() * factor;
            sd.drops.put(e.getKey(), Math.min(v, config.maxStoredPerItem));
        }
        sd.xp = Math.min(sd.xp + t.xp * factor, config.maxStoredXp);
    }

    /**
     * Moves stored drops into the player's inventory (at most maxItems).
     * Returns the number of items moved. Whatever does not fit stays stored.
     */
    static int giveDrops(ServerPlayer sp, SpawnerData sd, int maxItems) {
        int moved = 0;
        for (Map.Entry<String, Double> e : new ArrayList<>(sd.drops.entrySet())) {
            Item item = Util.item(e.getKey());
            int whole = (int) Math.floor(e.getValue());
            while (whole > 0 && moved < maxItems) {
                int batch = Math.min(Math.min(whole, new ItemStack(item).getMaxStackSize()), maxItems - moved);
                ItemStack stack = new ItemStack(item, batch);
                sp.getInventory().add(stack);
                int taken = batch - stack.getCount();
                if (taken <= 0) return moved; // inventory full
                moved += taken;
                whole -= taken;
                sd.drops.put(e.getKey(), sd.drops.get(e.getKey()) - taken);
                if (taken < batch) return moved;
            }
            if (moved >= maxItems) break;
        }
        return moved;
    }

    static boolean hasStoredItems(SpawnerData sd) {
        for (double v : sd.drops.values()) if (v >= 1) return true;
        return false;
    }

    /** Gives all whole stored XP points to the player. Returns the amount. */
    static int giveXp(ServerPlayer sp, SpawnerData sd) {
        int whole = (int) Math.floor(sd.xp);
        if (whole <= 0) return 0;
        sp.giveExperiencePoints(whole);
        sd.xp -= whole;
        return whole;
    }

    // ------------------------------------------------------------ pick up / placing

    private static void pickUp(ServerPlayer sp, ServerLevel sl, BlockPos pos, String key, SpawnerData sd) {
        produce(sd);
        giveDrops(sp, sd, Integer.MAX_VALUE);
        if (hasStoredItems(sd)) {
            saveSpawners();
            sp.sendSystemMessage(Util.text("Make inventory space and collect the stored drops before picking this up.",
                    ChatFormatting.RED), true);
            return;
        }
        giveXp(sp, sd);

        int remaining = sd.count;
        while (remaining > 0) {
            int n = Math.min(64, remaining);
            ItemStack stack = createItem(sd.type, n);
            sp.getInventory().add(stack);
            if (!stack.isEmpty()) Block.popResource(sl, pos, stack); // inventory full: drop it
            remaining -= n;
        }
        sl.removeBlock(pos, false);
        removeHolo(sl, sd);
        spawners.remove(key);
        saveSpawners();
        sp.sendSystemMessage(Util.text("Picked up " + sd.count + "x " + cfgName(sd.type) + ".", ChatFormatting.GREEN), true);
        sp.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 1.0f);
    }

    private static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        List<Pending> work = new ArrayList<>(PENDING);
        PENDING.clear();
        for (Pending p : work) {
            try {
                resolve(server, p);
            } catch (Exception e) {
                LOGGER.error("Could not register spawner placement", e);
            }
        }
    }

    private static void resolve(MinecraftServer server, Pending p) {
        ServerPlayer sp = server.getPlayerList().getPlayer(p.player());
        if (sp == null) return;
        ItemStack now = sp.getItemInHand(p.hand());
        boolean consumed = sp.isCreative() || now.isEmpty() || now.getCount() < p.countBefore();
        if (!consumed) return; // placement did not happen
        for (BlockPos pos : new BlockPos[] {p.adjacent(), p.clicked()}) {
            String key = key(p.level(), pos);
            if (spawners.containsKey(key)) continue;
            if (!p.level().getBlockState(pos).is(Blocks.SPAWNER)) continue;

            SpawnerData sd = new SpawnerData();
            sd.type = p.type();
            sd.owner = sp.getUUID().toString();
            sd.ownerName = sp.getName().getString();
            sd.count = 1;
            sd.lastTick = System.currentTimeMillis();
            spawners.put(key, sd);
            saveSpawners();
            neutralize(p.level(), pos, p.type());
            refreshHolo(p.level(), pos, sd);
            burst(p.level(), pos);

            sp.sendSystemMessage(Util.text(cfgName(p.type()) + " placed. Right-click it to open storage.",
                    ChatFormatting.LIGHT_PURPLE), true);
            p.level().sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                    12, 0.3, 0.3, 0.3, 0.02);
            return;
        }
    }

    /**
     * Turns the freshly placed vanilla spawner into a harmless display block:
     * required player range 0 means it can never activate, so no mobs spawn.
     * The mob shown spinning in the cage is set to the configured entity.
     */
    private static void neutralize(ServerLevel level, BlockPos pos, String type) {
        TypeCfg t = config.types.get(type);
        if (t == null) return;
        String ent = t.entity;
        String nbt = "{RequiredPlayerRange:0s,SpawnData:{entity:{id:\"" + ent + "\"}},"
                + "SpawnPotentials:[{weight:1,data:{entity:{id:\"" + ent + "\"}}}]}";
        String cmd = "data merge block " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + nbt;
        try {
            MinecraftServer server = level.getServer();
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withLevel(level).withSuppressedOutput(), cmd);
        } catch (Exception e) {
            LOGGER.error("Could not neutralize spawner at {}", pos, e);
        }
    }

    // ------------------------------------------------------------ storage

    private static void loadConfig() {
        Path file = dir.resolve("config.json");
        try {
            Files.createDirectories(dir);
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file)) {
                    Config loaded = GSON.fromJson(r, Config.class);
                    if (loaded != null && loaded.types != null) config = loaded;
                    // add any new built-in types (e.g. enderman) and colours to older config files
                    for (Map.Entry<String, TypeCfg> e : Config.defaultTypes().entrySet()) {
                        TypeCfg have = config.types.get(e.getKey());
                        if (have == null) config.types.put(e.getKey(), e.getValue());
                        else if (have.color == null) have.color = e.getValue().color;
                    }
                    try (Writer w = Files.newBufferedWriter(file)) {
                        GSON.toJson(config, w);
                    }
                }
            } else {
                try (Writer w = Files.newBufferedWriter(file)) {
                    GSON.toJson(config, w);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Could not read config.json, using defaults", e);
        }
    }

    private static void loadSpawners() {
        Path file = dir.resolve("spawners.json");
        try {
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file)) {
                    Map<String, SpawnerData> loaded = GSON.fromJson(r, SPAWNERS_TYPE);
                    if (loaded != null) spawners = loaded;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Could not read spawners.json", e);
        }
    }

    static void saveSpawners() {
        try {
            Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(dir.resolve("spawners.json"))) {
                GSON.toJson(spawners, w);
            }
        } catch (IOException e) {
            LOGGER.error("Could not write spawners.json", e);
        }
    }
}
