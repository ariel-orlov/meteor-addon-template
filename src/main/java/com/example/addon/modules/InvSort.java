package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.packets.InventoryEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.ISlot;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.HopperScreen;
import net.minecraft.client.gui.screen.ingame.HorseScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.SwordItem;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistent inventory sorter. Enable the module, then press the sort
 * keybind while any inventory or container is open to trigger a sort.
 * Mode and direction can be changed with hotkeys at any time.
 *
 * Also supports PvP hotbar arrangement (with optional container pull),
 * steal/dump between container and inventory, and cross-stacking
 * identical partial stacks across both sections.
 *
 * Supported containers: chests, barrels, ender chests (generic),
 * shulker boxes, dispensers/droppers (3x3), hoppers, horses with chests.
 */
public class InvSort extends Module {

    // ── Action type constants ──────────────────────────────────────────────
    // action[] = {arg0, arg1, type}
    private static final int ACT_MOVE        = 0; // InvUtils.move().fromId(a[0]).toId(a[1])
    private static final int ACT_QUICK_SWAP  = 1; // InvUtils.quickSwap().fromHotbar(a[0]).toId(a[1])
    private static final int ACT_SHIFT_CLICK = 2; // InvUtils.shiftClick().slotId(a[0])

    // ── Sort mode ──────────────────────────────────────────────────────────

    public enum SortMode {
        REGISTRY("Registry ID"),
        NAME("Display Name"),
        COUNT("Stack Count");

        private final String label;
        SortMode(String label) { this.label = label; }

        @Override public String toString() { return label; }

        public SortMode next() {
            SortMode[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    // ── PvP item categories ────────────────────────────────────────────────

    public enum PvpItem {
        NONE("None"),
        SWORD("Sword"),
        AXE("Axe"),
        BOW("Bow"),
        CROSSBOW("Crossbow"),
        TRIDENT("Trident"),
        PICKAXE("Pickaxe"),
        SHIELD("Shield"),
        FOOD("Food"),
        GOLDEN_APPLE("Golden Apple"),
        ENCHANTED_GOLDEN_APPLE("Enchanted G.Apple"),
        ENDER_PEARL("Ender Pearl"),
        BLOCKS("Blocks"),
        POTION("Potion"),
        WATER_BUCKET("Water Bucket"),
        LAVA_BUCKET("Lava Bucket"),
        TOTEM("Totem"),
        FISHING_ROD("Fishing Rod"),
        FLINT_AND_STEEL("Flint and Steel");

        private final String label;
        PvpItem(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    // ── Settings groups ────────────────────────────────────────────────────

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSort    = settings.createGroup("Sort Behaviour");
    private final SettingGroup sgPvp     = settings.createGroup("PvP Sort");
    private final SettingGroup sgHotkeys = settings.createGroup("Hotkeys");

    // --- General ---

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between inventory actions. Raise on servers with anti-cheat (4-6 is safe).")
        .defaultValue(2)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> sortContainers = sgGeneral.add(new BoolSetting.Builder()
        .name("sort-containers")
        .description("Sort the open container when one is available.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sortPlayer = sgGeneral.add(new BoolSetting.Builder()
        .name("sort-player")
        .description("Sort your main inventory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sortHotbar = sgGeneral.add(new BoolSetting.Builder()
        .name("sort-hotbar")
        .description("Include hotbar slots in the player inventory sort and dump operations.")
        .defaultValue(false)
        .visible(sortPlayer::get)
        .build()
    );

    private final Setting<Boolean> autoSort = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-sort")
        .description("Automatically sort supported containers when you open them.")
        .defaultValue(false)
        .build()
    );

    // --- Sort Behaviour ---

    private final Setting<Boolean> stackOnly = sgSort.add(new BoolSetting.Builder()
        .name("stack-only")
        .description("Only merge partial stacks; skip reordering entirely.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SortMode> sortMode = sgSort.add(new EnumSetting.Builder<SortMode>()
        .name("sort-mode")
        .description("Primary criterion used to order items.")
        .defaultValue(SortMode.REGISTRY)
        .visible(() -> !stackOnly.get())
        .build()
    );

    private final Setting<Boolean> reverseSort = sgSort.add(new BoolSetting.Builder()
        .name("reverse")
        .description("Reverse the sort order (Z->A, or fewest items first for Count mode).")
        .defaultValue(false)
        .visible(() -> !stackOnly.get())
        .build()
    );

    // --- PvP Sort ---

    private final Setting<PvpItem> pvpSlot1 = sgPvp.add(new EnumSetting.Builder<PvpItem>()
        .name("slot-1").description("Target item category for hotbar slot 1.")
        .defaultValue(PvpItem.SWORD).build());

    private final Setting<PvpItem> pvpSlot2 = sgPvp.add(new EnumSetting.Builder<PvpItem>()
        .name("slot-2").description("Target item category for hotbar slot 2.")
        .defaultValue(PvpItem.AXE).build());

    private final Setting<PvpItem> pvpSlot3 = sgPvp.add(new EnumSetting.Builder<PvpItem>()
        .name("slot-3").description("Target item category for hotbar slot 3.")
        .defaultValue(PvpItem.BOW).build());

    private final Setting<PvpItem> pvpSlot4 = sgPvp.add(new EnumSetting.Builder<PvpItem>()
        .name("slot-4").description("Target item category for hotbar slot 4.")
        .defaultValue(PvpItem.POTION).build());

    private final Setting<PvpItem> pvpSlot5 = sgPvp.add(new EnumSetting.Builder<PvpItem>()
        .name("slot-5").description("Target item category for hotbar slot 5.")
        .defaultValue(PvpItem.FOOD).build());

    private final Setting<PvpItem> pvpSlot6 = sgPvp.add(new EnumSetting.Builder<PvpItem>()
        .name("slot-6").description("Target item category for hotbar slot 6.")
        .defaultValue(PvpItem.BLOCKS).build());

    private final Setting<PvpItem> pvpSlot7 = sgPvp.add(new EnumSetting.Builder<PvpItem>()
        .name("slot-7").description("Target item category for hotbar slot 7.")
        .defaultValue(PvpItem.ENDER_PEARL).build());

    private final Setting<PvpItem> pvpSlot8 = sgPvp.add(new EnumSetting.Builder<PvpItem>()
        .name("slot-8").description("Target item category for hotbar slot 8.")
        .defaultValue(PvpItem.TOTEM).build());

    private final Setting<PvpItem> pvpSlot9 = sgPvp.add(new EnumSetting.Builder<PvpItem>()
        .name("slot-9").description("Target item category for hotbar slot 9.")
        .defaultValue(PvpItem.NONE).build());

    private final Setting<Boolean> pvpPullFromContainer = sgPvp.add(new BoolSetting.Builder()
        .name("pull-from-container")
        .description("Pull needed items from an open container before arranging the hotbar.")
        .defaultValue(true)
        .build()
    );

    // --- Hotkeys ---

    private final Setting<Keybind> sortKey = sgHotkeys.add(new KeybindSetting.Builder()
        .name("sort-key")
        .description("Press to trigger a normal sort of the current screen.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> pvpSortKey = sgHotkeys.add(new KeybindSetting.Builder()
        .name("pvp-sort-key")
        .description("Arrange hotbar into PvP loadout. Pulls from container first if one is open.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> stealKey = sgHotkeys.add(new KeybindSetting.Builder()
        .name("steal-key")
        .description("Shift-click all items from the open container into your inventory.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> dumpKey = sgHotkeys.add(new KeybindSetting.Builder()
        .name("dump-key")
        .description("Shift-click all items from your inventory into the open container.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> crossStackKey = sgHotkeys.add(new KeybindSetting.Builder()
        .name("cross-stack-key")
        .description("Merge partial stacks of identical items that exist in both container and inventory.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> cycleModeKey = sgHotkeys.add(new KeybindSetting.Builder()
        .name("cycle-mode")
        .description("Cycle sort mode: Registry ID -> Display Name -> Stack Count -> repeat.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> toggleReverseKey = sgHotkeys.add(new KeybindSetting.Builder()
        .name("toggle-reverse")
        .description("Toggle between normal and reversed sort direction.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> toggleStackOnlyKey = sgHotkeys.add(new KeybindSetting.Builder()
        .name("toggle-stack-only")
        .description("Toggle stack-only mode on or off.")
        .defaultValue(Keybind.none())
        .build()
    );

    // ── State ──────────────────────────────────────────────────────────────

    private final Deque<int[]> actionQueue = new ArrayDeque<>();
    private int timer = 0;
    private int autoSortCountdown = -1;
    private boolean pvpPullingPhase = false;

    public InvSort() {
        super(AddonTemplate.CATEGORY, "inv-sort",
            "Persistent inventory sorter. Keep enabled and press the sort keybind.");
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public void onDeactivate() {
        cancelSort();
        autoSortCountdown = -1;
    }

    // ── Input handling ─────────────────────────────────────────────────────

    @EventHandler
    private void onKey(KeyEvent event) {
        if (event.action != KeyAction.Press) return;
        if      (sortKey.get().matches(event.input))       { triggerSort();       event.cancel(); }
        else if (pvpSortKey.get().matches(event.input))    { triggerPvpSort(); }
        else if (stealKey.get().matches(event.input))      { triggerSteal(); }
        else if (dumpKey.get().matches(event.input))       { triggerDump(); }
        else if (crossStackKey.get().matches(event.input)) { triggerCrossStack(); }
        else if (cycleModeKey.get().matches(event.input))       cycleMode();
        else if (toggleReverseKey.get().matches(event.input))   toggleReverse();
        else if (toggleStackOnlyKey.get().matches(event.input)) toggleStackOnly();
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (event.action != KeyAction.Press) return;
        if      (sortKey.get().matches(event.input))       { triggerSort();       event.cancel(); }
        else if (pvpSortKey.get().matches(event.input))    { triggerPvpSort(); }
        else if (stealKey.get().matches(event.input))      { triggerSteal(); }
        else if (dumpKey.get().matches(event.input))       { triggerDump(); }
        else if (crossStackKey.get().matches(event.input)) { triggerCrossStack(); }
        else if (cycleModeKey.get().matches(event.input))       cycleMode();
        else if (toggleReverseKey.get().matches(event.input))   toggleReverse();
        else if (toggleStackOnlyKey.get().matches(event.input)) toggleStackOnly();
    }

    // ── Screen open — auto-sort trigger ───────────────────────────────────

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!autoSort.get()) return;
        if (event.screen instanceof CreativeInventoryScreen) return;

        boolean supported = event.screen instanceof GenericContainerScreen
            || event.screen instanceof ShulkerBoxScreen
            || event.screen instanceof Generic3x3ContainerScreen
            || event.screen instanceof HopperScreen
            || event.screen instanceof HorseScreen;

        if (supported) {
            cancelSort();
            autoSortCountdown = 3;
        }
    }

    // ── Inventory packet — desync cancel ──────────────────────────────────

    @EventHandler
    private void onInventoryUpdate(InventoryEvent event) {
        if (actionQueue.isEmpty()) return;
        if (event.packet.getSyncId() == mc.player.currentScreenHandler.syncId) {
            cancelSort();
            info("Sort cancelled: inventory updated by server. Re-trigger to sort again.");
        }
    }

    // ── Tick — countdown, phase transition, action execution ─────────────

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (autoSortCountdown > 0) {
            autoSortCountdown--;
            if (autoSortCountdown == 0) {
                autoSortCountdown = -1;
                triggerSort();
            }
        }

        // PvP phase 2: pull complete, now arrange the hotbar
        if (pvpPullingPhase && actionQueue.isEmpty()) {
            pvpPullingPhase = false;
            if (mc.currentScreen instanceof HandledScreen<?> screen) {
                actionQueue.addAll(buildPvpArrangePlan(screen));
                if (!actionQueue.isEmpty()) timer = delay.get();
            }
            if (actionQueue.isEmpty()) {
                info("PvP sort: hotbar already arranged.");
                return;
            }
        }

        if (actionQueue.isEmpty()) return;

        if (!(mc.currentScreen instanceof HandledScreen<?>)) {
            cancelSort();
            return;
        }

        if (timer < delay.get()) { timer++; return; }
        timer = 0;

        int[] action = actionQueue.poll();
        switch (action[2]) {
            case ACT_QUICK_SWAP  -> InvUtils.quickSwap().fromHotbar(action[0]).toId(action[1]);
            case ACT_SHIFT_CLICK -> InvUtils.shiftClick().slotId(action[0]);
            default              -> InvUtils.move().fromId(action[0]).toId(action[1]);
        }
    }

    // ── Normal sort trigger ────────────────────────────────────────────────

    private void triggerSort() {
        if (mc.player == null) return;

        if (mc.currentScreen instanceof CreativeInventoryScreen) {
            info("Sorting is not supported in the creative inventory.");
            return;
        }

        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            info("Open your inventory or a container first.");
            return;
        }

        cancelSort();
        clearCursor();

        if (sortContainers.get()) actionQueue.addAll(buildSortPlan(getContainerSlots(screen)));
        if (sortPlayer.get())     actionQueue.addAll(buildSortPlan(getPlayerSlots(screen)));

        timer = delay.get();

        if (actionQueue.isEmpty()) info("Nothing to sort.");
    }

    // ── PvP sort trigger ───────────────────────────────────────────────────

    private void triggerPvpSort() {
        if (mc.player == null) return;

        if (mc.currentScreen instanceof CreativeInventoryScreen) {
            info("PvP sort is not supported in the creative inventory.");
            return;
        }

        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            info("Open your inventory or a container first.");
            return;
        }

        cancelSort();
        clearCursor();

        boolean hasContainer = !getContainerSlots(screen).isEmpty();

        if (hasContainer && pvpPullFromContainer.get()) {
            List<int[]> pullPlan = buildPvpPullPlan(screen);
            if (!pullPlan.isEmpty()) {
                actionQueue.addAll(pullPlan);
                pvpPullingPhase = true;
                timer = delay.get();
                return;
            }
        }

        // No pull needed — go straight to hotbar arrangement
        actionQueue.addAll(buildPvpArrangePlan(screen));
        timer = delay.get();
        if (actionQueue.isEmpty()) info("PvP sort: hotbar already arranged.");
    }

    // ── Cross-container triggers ───────────────────────────────────────────

    private void triggerSteal() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            info("Open a container first."); return;
        }
        List<SlotEntry> containerSlots = getContainerSlots(screen);
        if (containerSlots.isEmpty()) { info("No sortable container open."); return; }

        cancelSort();
        for (SlotEntry entry : containerSlots) {
            if (!entry.stack.isEmpty()) {
                actionQueue.add(new int[]{entry.id, 0, ACT_SHIFT_CLICK});
            }
        }
        timer = delay.get();
        if (actionQueue.isEmpty()) info("Container is already empty.");
    }

    private void triggerDump() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            info("Open a container first."); return;
        }
        if (getContainerSlots(screen).isEmpty()) { info("No sortable container open."); return; }

        cancelSort();
        for (SlotEntry entry : getPlayerSlots(screen)) {
            if (!entry.stack.isEmpty()) {
                actionQueue.add(new int[]{entry.id, 0, ACT_SHIFT_CLICK});
            }
        }
        timer = delay.get();
        if (actionQueue.isEmpty()) info("Inventory is already empty.");
    }

    private void triggerCrossStack() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            info("Open a container first."); return;
        }
        List<SlotEntry> containerSlots = getContainerSlots(screen);
        if (containerSlots.isEmpty()) { info("No sortable container open."); return; }

        cancelSort();
        List<SlotEntry> playerSlots = getPlayerSlots(screen);

        Set<String> playerPartial    = new HashSet<>();
        Set<String> containerPartial = new HashSet<>();

        for (SlotEntry ps : playerSlots) {
            if (!ps.stack.isEmpty() && ps.stack.isStackable()
                    && ps.stack.getCount() < ps.stack.getMaxCount()) {
                playerPartial.add(Registries.ITEM.getId(ps.stack.getItem()).toString());
            }
        }
        for (SlotEntry cs : containerSlots) {
            if (!cs.stack.isEmpty() && cs.stack.isStackable()
                    && cs.stack.getCount() < cs.stack.getMaxCount()) {
                containerPartial.add(Registries.ITEM.getId(cs.stack.getItem()).toString());
            }
        }

        for (SlotEntry cs : containerSlots) {
            if (!cs.stack.isEmpty()
                    && playerPartial.contains(Registries.ITEM.getId(cs.stack.getItem()).toString())) {
                actionQueue.add(new int[]{cs.id, 0, ACT_SHIFT_CLICK});
            }
        }
        for (SlotEntry ps : playerSlots) {
            if (!ps.stack.isEmpty()
                    && containerPartial.contains(Registries.ITEM.getId(ps.stack.getItem()).toString())) {
                actionQueue.add(new int[]{ps.id, 0, ACT_SHIFT_CLICK});
            }
        }

        timer = delay.get();
        if (actionQueue.isEmpty()) info("No partial stacks to cross-merge.");
    }

    // ── Cancel ─────────────────────────────────────────────────────────────

    private void cancelSort() {
        actionQueue.clear();
        timer = 0;
        pvpPullingPhase = false;
        if (mc.player != null && !mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            InvUtils.dropHand();
        }
    }

    // ── Hotkey actions ─────────────────────────────────────────────────────

    private void cycleMode() {
        if (stackOnly.get()) { info("Disable stack-only first to change sort mode."); return; }
        SortMode next = sortMode.get().next();
        sortMode.set(next);
        info("Sort mode: %s.", next);
    }

    private void toggleReverse() {
        if (stackOnly.get()) { info("Disable stack-only first to change sort direction."); return; }
        boolean next = !reverseSort.get();
        reverseSort.set(next);
        info("Sort direction: %s.", next ? "reversed" : "normal");
    }

    private void toggleStackOnly() {
        boolean next = !stackOnly.get();
        stackOnly.set(next);
        info("Stack-only: %s.", next ? "on" : "off");
    }

    // ── Slot collection ────────────────────────────────────────────────────

    private List<SlotEntry> getContainerSlots(HandledScreen<?> screen) {
        if (screen instanceof GenericContainerScreen
            || screen instanceof ShulkerBoxScreen
            || screen instanceof Generic3x3ContainerScreen
            || screen instanceof HopperScreen) {
            return collectNonPlayerSlots(screen);
        }
        if (screen instanceof HorseScreen horseScreen) {
            return getHorseChestSlots(horseScreen);
        }
        return List.of();
    }

    private List<SlotEntry> collectNonPlayerSlots(HandledScreen<?> screen) {
        List<SlotEntry> result = new ArrayList<>();
        for (Slot slot : screen.getScreenHandler().slots) {
            if (!(slot.inventory instanceof PlayerInventory)) {
                result.add(new SlotEntry(((ISlot) slot).meteor$getId(), slot.getStack().copy()));
            }
        }
        return result;
    }

    private List<SlotEntry> getHorseChestSlots(HorseScreen screen) {
        List<SlotEntry> result = new ArrayList<>();
        int equipmentSeen = 0;
        boolean pastEquipment = false;

        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory) continue;
            if (equipmentSeen < 2) { equipmentSeen++; continue; }
            pastEquipment = true;
            result.add(new SlotEntry(((ISlot) slot).meteor$getId(), slot.getStack().copy()));
        }
        return pastEquipment ? result : List.of();
    }

    private List<SlotEntry> getPlayerSlots(HandledScreen<?> screen) {
        List<SlotEntry> result = new ArrayList<>();
        for (Slot slot : screen.getScreenHandler().slots) {
            if (!(slot.inventory instanceof PlayerInventory)) continue;
            int index = ((ISlot) slot).meteor$getIndex();
            if (SlotUtils.isMain(index) || (sortHotbar.get() && SlotUtils.isHotbar(index))) {
                result.add(new SlotEntry(((ISlot) slot).meteor$getId(), slot.getStack().copy()));
            }
        }
        return result;
    }

    // ── Normal sort plan ───────────────────────────────────────────────────

    private List<int[]> buildSortPlan(List<SlotEntry> slots) {
        if (slots.isEmpty()) return List.of();

        List<SlotEntry> working = new ArrayList<>(slots.size());
        for (SlotEntry s : slots) working.add(new SlotEntry(s.id, s.stack.copy()));

        List<int[]> actions = new ArrayList<>();
        stackPhase(working, actions);
        if (!stackOnly.get()) sortPhase(working, actions);
        return actions;
    }

    private void stackPhase(List<SlotEntry> slots, List<int[]> actions) {
        for (int i = 0; i < slots.size(); i++) {
            SlotEntry target = slots.get(i);
            if (target.stack.isEmpty()
                || !target.stack.isStackable()
                || target.stack.getCount() >= target.stack.getMaxCount()) continue;

            for (int j = i + 1; j < slots.size(); j++) {
                SlotEntry source = slots.get(j);
                if (source.stack.isEmpty()) continue;
                if (!ItemStack.areItemsAndComponentsEqual(target.stack, source.stack)) continue;

                actions.add(new int[]{source.id, target.id, ACT_MOVE});

                int combined = target.stack.getCount() + source.stack.getCount();
                int max = target.stack.getMaxCount();
                if (combined <= max) {
                    target.stack = target.stack.copyWithCount(combined);
                    source.stack = ItemStack.EMPTY;
                } else {
                    source.stack = source.stack.copyWithCount(combined - max);
                    target.stack = target.stack.copyWithCount(max);
                    break;
                }
                if (target.stack.getCount() >= target.stack.getMaxCount()) break;
            }
        }
    }

    private void sortPhase(List<SlotEntry> slots, List<int[]> actions) {
        for (int i = 0; i < slots.size(); i++) {
            int bestIdx = i;
            for (int j = i + 1; j < slots.size(); j++) {
                if (isBetter(slots.get(j), slots.get(bestIdx))) bestIdx = j;
            }
            if (bestIdx != i && !slots.get(bestIdx).stack.isEmpty()) {
                actions.add(new int[]{slots.get(bestIdx).id, slots.get(i).id, ACT_MOVE});
                ItemStack tmp = slots.get(i).stack;
                slots.get(i).stack = slots.get(bestIdx).stack;
                slots.get(bestIdx).stack = tmp;
            }
        }
    }

    private boolean isBetter(SlotEntry candidate, SlotEntry current) {
        ItemStack c = candidate.stack;
        ItemStack b = current.stack;
        if (b.isEmpty() && !c.isEmpty()) return true;
        if (!b.isEmpty() && c.isEmpty()) return false;

        int cmp = switch (sortMode.get()) {
            case REGISTRY -> Registries.ITEM.getId(b.getItem()).compareTo(Registries.ITEM.getId(c.getItem()));
            case NAME     -> b.getName().getString().compareToIgnoreCase(c.getName().getString());
            case COUNT    -> Integer.compare(c.getCount(), b.getCount());
        };

        if (cmp != 0) return reverseSort.get() ? cmp < 0 : cmp > 0;

        // Stable tiebreaker — unaffected by reverseSort
        cmp = Registries.ITEM.getId(b.getItem()).compareTo(Registries.ITEM.getId(c.getItem()));
        if (cmp != 0) return cmp > 0;
        if (c.getCount() != b.getCount()) return c.getCount() > b.getCount();
        return c.getDamage() > b.getDamage();
    }

    // ── PvP sort plans ─────────────────────────────────────────────────────

    private List<int[]> buildPvpPullPlan(HandledScreen<?> screen) {
        List<int[]> actions = new ArrayList<>();
        List<Setting<PvpItem>> slots = getPvpSlotSettings();

        Map<PvpItem, Integer> needed = new HashMap<>();
        for (Setting<PvpItem> slot : slots) {
            PvpItem cat = slot.get();
            if (cat != PvpItem.NONE) needed.merge(cat, 1, Integer::sum);
        }
        if (needed.isEmpty()) return actions;

        Map<PvpItem, Integer> have = new HashMap<>();
        for (Slot s : screen.getScreenHandler().slots) {
            if (!(s.inventory instanceof PlayerInventory)) continue;
            ItemStack stack = s.getStack();
            if (stack.isEmpty()) continue;
            for (PvpItem cat : needed.keySet()) {
                if (matchesCategory(stack, cat)) { have.merge(cat, 1, Integer::sum); break; }
            }
        }

        for (Map.Entry<PvpItem, Integer> entry : needed.entrySet()) {
            PvpItem cat = entry.getKey();
            int toPull = entry.getValue() - have.getOrDefault(cat, 0);
            if (toPull <= 0) continue;

            List<SlotEntry> candidates = new ArrayList<>();
            for (Slot s : screen.getScreenHandler().slots) {
                if (s.inventory instanceof PlayerInventory) continue;
                if (!s.getStack().isEmpty() && matchesCategory(s.getStack(), cat)) {
                    candidates.add(new SlotEntry(((ISlot) s).meteor$getId(), s.getStack().copy()));
                }
            }
            candidates.sort((a, b) -> scoreItem(b.stack) - scoreItem(a.stack));

            for (int i = 0; i < Math.min(toPull, candidates.size()); i++) {
                actions.add(new int[]{candidates.get(i).id, 0, ACT_SHIFT_CLICK});
            }
        }

        return actions;
    }

    private List<int[]> buildPvpArrangePlan(HandledScreen<?> screen) {
        List<int[]> actions = new ArrayList<>();
        List<Setting<PvpItem>> slots = getPvpSlotSettings();

        Map<Integer, ItemStack> virtualInv    = new HashMap<>();
        Map<Integer, Integer>   indexToSlotId = new HashMap<>();

        for (Slot s : screen.getScreenHandler().slots) {
            if (!(s.inventory instanceof PlayerInventory)) continue;
            int idx = ((ISlot) s).meteor$getIndex();
            if (idx < 0 || idx > 35) continue;
            virtualInv.put(idx, s.getStack().copy());
            indexToSlotId.put(idx, ((ISlot) s).meteor$getId());
        }

        Set<Integer> satisfied = new HashSet<>();
        for (int h = 0; h < 9; h++) {
            PvpItem target = slots.get(h).get();
            if (target == PvpItem.NONE) { satisfied.add(h); continue; }
            ItemStack cur = virtualInv.getOrDefault(h, ItemStack.EMPTY);
            if (!cur.isEmpty() && matchesCategory(cur, target)) satisfied.add(h);
        }

        for (int h = 0; h < 9; h++) {
            if (satisfied.contains(h)) continue;
            PvpItem target = slots.get(h).get();

            int bestIdx   = -1;
            int bestScore = -1;

            for (int i = 0; i <= 35; i++) {
                if (i == h) continue;
                if (i < 9 && !satisfied.contains(i) && slots.get(i).get() != PvpItem.NONE) continue;

                ItemStack stack = virtualInv.getOrDefault(i, ItemStack.EMPTY);
                if (stack.isEmpty() || !matchesCategory(stack, target)) continue;
                // Prefer main inventory (9-35) over unconfigured hotbar slots
                int score = scoreItem(stack) * 2 + (i >= 9 ? 1 : 0);
                if (score > bestScore) { bestScore = score; bestIdx = i; }
            }

            if (bestIdx >= 0 && indexToSlotId.containsKey(bestIdx)) {
                actions.add(new int[]{h, indexToSlotId.get(bestIdx), ACT_QUICK_SWAP});

                ItemStack tmp = virtualInv.getOrDefault(h, ItemStack.EMPTY);
                virtualInv.put(h, virtualInv.getOrDefault(bestIdx, ItemStack.EMPTY));
                virtualInv.put(bestIdx, tmp);
                satisfied.add(h);
            }
        }

        return actions;
    }

    // ── PvP helpers ────────────────────────────────────────────────────────

    private boolean matchesCategory(ItemStack stack, PvpItem category) {
        return switch (category) {
            case NONE                   -> false;
            case SWORD                  -> stack.getItem() instanceof SwordItem;
            case AXE                    -> stack.getItem() instanceof AxeItem;
            case BOW                    -> stack.getItem() == Items.BOW;
            case CROSSBOW               -> stack.getItem() == Items.CROSSBOW;
            case TRIDENT                -> stack.getItem() == Items.TRIDENT;
            case PICKAXE                -> stack.getItem() instanceof PickaxeItem;
            case SHIELD                 -> stack.getItem() == Items.SHIELD;
            case GOLDEN_APPLE           -> stack.getItem() == Items.GOLDEN_APPLE;
            case ENCHANTED_GOLDEN_APPLE -> stack.getItem() == Items.ENCHANTED_GOLDEN_APPLE;
            case ENDER_PEARL            -> stack.getItem() == Items.ENDER_PEARL;
            case BLOCKS                 -> stack.getItem() instanceof BlockItem;
            case POTION                 -> stack.getItem() == Items.POTION
                                       || stack.getItem() == Items.SPLASH_POTION
                                       || stack.getItem() == Items.LINGERING_POTION;
            case WATER_BUCKET           -> stack.getItem() == Items.WATER_BUCKET;
            case LAVA_BUCKET            -> stack.getItem() == Items.LAVA_BUCKET;
            case TOTEM                  -> stack.getItem() == Items.TOTEM_OF_UNDYING;
            case FISHING_ROD            -> stack.getItem() == Items.FISHING_ROD;
            case FLINT_AND_STEEL        -> stack.getItem() == Items.FLINT_AND_STEEL;
            case FOOD -> {
                if (stack.getItem() == Items.GOLDEN_APPLE
                    || stack.getItem() == Items.ENCHANTED_GOLDEN_APPLE) yield false;
                yield stack.get(DataComponentTypes.FOOD) != null;
            }
        };
    }

    private int scoreItem(ItemStack stack) {
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        int tier = 0;
        if      (path.startsWith("netherite_")) tier = 5;
        else if (path.startsWith("diamond_"))   tier = 4;
        else if (path.startsWith("iron_"))      tier = 3;
        else if (path.startsWith("stone_"))     tier = 2;
        else if (path.startsWith("golden_"))    tier = 1;

        int maxDmg = stack.getMaxDamage();
        int durability = maxDmg > 0 ? (maxDmg - stack.getDamage()) * 100 / maxDmg : 100;
        return tier * 1000 + durability;
    }

    private List<Setting<PvpItem>> getPvpSlotSettings() {
        return List.of(pvpSlot1, pvpSlot2, pvpSlot3, pvpSlot4, pvpSlot5,
                       pvpSlot6, pvpSlot7, pvpSlot8, pvpSlot9);
    }

    // ── Cursor helper ──────────────────────────────────────────────────────

    private void clearCursor() {
        if (mc.player.currentScreenHandler.getCursorStack().isEmpty()) return;
        FindItemResult empty = InvUtils.findEmpty();
        if (empty.found()) InvUtils.click().slot(empty.slot());
        else InvUtils.click().slotId(-999);
    }

    // ── Inner types ────────────────────────────────────────────────────────

    private static class SlotEntry {
        final int id;
        ItemStack stack;

        SlotEntry(int id, ItemStack stack) {
            this.id = id;
            this.stack = stack;
        }
    }
}
