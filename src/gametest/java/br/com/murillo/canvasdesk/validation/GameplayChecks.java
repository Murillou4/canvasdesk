package br.com.murillo.canvasdesk.validation;

import br.com.murillo.canvasdesk.CanvasDesk;
import br.com.murillo.canvasdesk.block.DeskBlock;
import br.com.murillo.canvasdesk.block.DeskBlockEntity;
import br.com.murillo.canvasdesk.server.DeskServer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Assertions run on the real integrated server, using Minecraft items, loot and blocks. */
public final class GameplayChecks {
    public static final BlockPos PRIMARY = new BlockPos(15, 80, 15);
    public static final BlockPos FOREIGN = new BlockPos(19, 80, 15);
    public static final BlockPos RECOVERY = new BlockPos(23, 80, 20);
    private static final UUID FOREIGN_OWNER = UUID.nameUUIDFromBytes("canvasdesk-offline-owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final UUID INVITED = UUID.nameUUIDFromBytes("canvasdesk-offline-editor".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static UUID workspace, foreignWorkspace, recoveryWorkspace, loadWorkspace;
    private static JsonObject expectedState;
    private static UUID pendingMerge;
    private static final JsonObject measurements = new JsonObject();
    private GameplayChecks() {}

    public static ServerPlayer player(MinecraftServer server) { return server.getPlayerList().getPlayers().getFirst(); }
    public static UUID workspace() { return workspace; }
    public static UUID foreignWorkspace() { return foreignWorkspace; }
    public static JsonObject measurements() { return measurements.deepCopy(); }

    public static void setupRectangle(MinecraftServer server) {
        ServerPlayer player = player(server); ServerLevel level = player.level();
        floor(level, 10, 10, 26, 23);
        player.teleportTo(16.5, 80, 11.5);
        player.setGameMode(GameType.SURVIVAL);
        CraftingInput input = CraftingInput.of(3, 3, List.of(
            new ItemStack(Items.PAPER), new ItemStack(Items.PAPER), new ItemStack(Items.PAPER),
            new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.REDSTONE), new ItemStack(Items.COPPER_INGOT),
            new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.SPRUCE_PLANKS), new ItemStack(Items.BIRCH_PLANKS)));
        var recipe = server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level).orElseThrow(() -> new AssertionError("Desk recipe did not match real mixed plank ingredients"));
        ItemStack crafted = recipe.value().assemble(input);
        require(crafted.is(CanvasDesk.DESK_ITEM) && crafted.getCount() == 1, "Crafting output must be exactly one Canvas Desk");
        place(player, PRIMARY, crafted);
        require(crafted.isEmpty(), "Survival BlockItem placement did not consume the crafted desk");
        workspace = desk(level, PRIMARY).workspace;
        require(workspace != null, "Placed desk has no persistent workspace");
        player.setGameMode(GameType.CREATIVE);
        place(player, PRIMARY.east(), new ItemStack(CanvasDesk.DESK_ITEM));
        place(player, PRIMARY.south(), new ItemStack(CanvasDesk.DESK_ITEM));
        place(player, PRIMARY.south().east(), new ItemStack(CanvasDesk.DESK_ITEM));
        measurements.addProperty("craftingRecipeId", recipe.id().identifier().toString());
    }

    public static void assertRectangle(MinecraftServer server) {
        ServerLevel level = player(server).level();
        for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) {
            BlockPos pos = PRIMARY.offset(x, 0, z); assertProjection(server, pos, x * 256, z * 256);
            require(level.getBlockState(pos).getValue(DeskBlock.EAST) == (x == 0), "East connection mismatch");
            require(level.getBlockState(pos).getValue(DeskBlock.SOUTH) == (z == 0), "South connection mismatch");
        }
        require((PRIMARY.getX() >> 4) != (PRIMARY.east().getX() >> 4), "Fixture must really cross a chunk border");
        require((PRIMARY.getZ() >> 4) != (PRIMARY.south().getZ() >> 4), "Fixture must cross the second chunk border too");
    }

    public static void expandRing(MinecraftServer server) {
        ServerPlayer player = player(server);
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) {
            if (x == 1 && z == 1) continue;
            BlockPos pos = PRIMARY.offset(x, 0, z);
            if (!player.level().getBlockState(pos).is(CanvasDesk.DESK)) place(player, pos, new ItemStack(CanvasDesk.DESK_ITEM));
        }
        require(player.level().destroyBlock(PRIMARY.offset(1, 0, 1), false, player, 512), "Could not remove center module");
    }

    public static void assertRing(MinecraftServer server) {
        ServerLevel level = player(server).level();
        require(level.getBlockState(PRIMARY.offset(1, 0, 1)).isAir(), "Ring hole must remain actual air");
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) if (x != 1 || z != 1) assertProjection(server, PRIMARY.offset(x, 0, z), x * 256, z * 256);
        require(!level.getBlockState(PRIMARY.east()).getValue(DeskBlock.SOUTH), "Surface connected across the empty hole");
        require(!level.getBlockState(PRIMARY.south()).getValue(DeskBlock.EAST), "Surface connected sideways across the empty hole");
    }

    public static void detachAndReposition(MinecraftServer server) {
        ServerPlayer player = player(server); ServerLevel level = player.level();
        BlockPos removed = PRIMARY.offset(2, 0, 2), isolated = new BlockPos(23, 80, 18);
        List<ItemStack> preview = net.minecraft.world.level.block.Block.getDrops(level.getBlockState(removed), level, removed, desk(level, removed));
        require(preview.size() == 1 && linkedTo(preview.getFirst(), workspace, server), "Loot table did not preserve workspace component");
        ItemStack dropped = breakAndCollect(player, removed);
        CustomData data = dropped.get(DataComponents.CUSTOM_DATA);
        require(data != null && data.copyTag().getDoubleOr("tileX", -1) == 512 && data.copyTag().getDoubleOr("tileY", -1) == 512,
            "Dropped desk lost its projection coordinates");
        place(player, isolated, dropped);
        assertProjection(server, isolated, 512, 512);
        require(count(server, workspace) > 0, "Disconnected modules lost the common document");
    }

    public static void reconnect(MinecraftServer server) {
        ServerPlayer player = player(server);
        ItemStack stack = breakAndCollect(player, new BlockPos(23, 80, 18));
        place(player, PRIMARY.offset(3, 0, 0), stack);
    }

    public static void assertReconnected(MinecraftServer server) {
        assertProjection(server, PRIMARY.offset(3, 0, 0), 768, 0);
        assertProjection(server, PRIMARY, 0, 0);
    }

    public static void permissionsAndLostRecovery(MinecraftServer server) {
        ServerPlayer player = player(server); DeskServer service = DeskServer.get(server);
        JsonObject invite = action("invite"); invite.addProperty("player", INVITED.toString()); success(service.store.action(workspace, player.getUUID(), invite));
        require(view(server, workspace, INVITED).get("editable").getAsBoolean(), "Invited editor did not receive editing access");
        JsonObject revoke = action("revoke"); revoke.addProperty("player", INVITED.toString()); success(service.store.action(workspace, player.getUUID(), revoke));
        require(!view(server, workspace, INVITED).get("editable").getAsBoolean(), "Revoked editor retained access");
        JsonObject outsiderChange = createElement("TEXT", 20, 20, "Unauthorized");
        require(!service.store.action(workspace, INVITED, edit(outsiderChange)).get("ok").getAsBoolean(), "Server accepted an unauthorized write");

        place(player, RECOVERY, new ItemStack(CanvasDesk.DESK_ITEM)); recoveryWorkspace = desk(player.level(), RECOVERY).workspace;
        success(service.store.action(recoveryWorkspace, player.getUUID(), edit(createElement("TEXT", 8, 8, "Documento recuperável: ação"))));
        ItemStack lost = breakAndCollect(player, RECOVERY); require(linkedTo(lost, recoveryWorkspace, server), "Recovery fixture drop lost its reference");
        // The recovered reference must come from the real player command, after the physical drop is gone.
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) if (linkedTo(player.getInventory().getItem(slot), recoveryWorkspace, server)) player.getInventory().setItem(slot, ItemStack.EMPTY);
        require(service.store.list(player.getUUID(), false).asList().stream().anyMatch(e -> e.getAsJsonObject().get("id").getAsString().equals(recoveryWorkspace.toString())), "Owner recovery list omitted a document with no module");
        server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "canvasdesk recover " + recoveryWorkspace);
        ItemStack recovered = ItemStack.EMPTY;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) if (linkedTo(player.getInventory().getItem(slot), recoveryWorkspace, server)) {
            recovered = player.getInventory().getItem(slot).copy(); player.getInventory().setItem(slot, ItemStack.EMPTY); break;
        }
        require(!recovered.isEmpty(), "Recovery command did not return a linked desk");
        place(player, RECOVERY, recovered);
        require(count(server, recoveryWorkspace) == 1, "Recovery command lost or duplicated document data");

        foreignWorkspace = service.store.create(FOREIGN_OWNER);
        success(service.store.action(foreignWorkspace, FOREIGN_OWNER, edit(createElement("NOTE", 30, 30, "Documento de outro dono"))));
        CompoundTag reference = new CompoundTag(); reference.putString("workspace", foreignWorkspace.toString());
        ItemStack foreign = new ItemStack(CanvasDesk.DESK_ITEM); foreign.set(DataComponents.CUSTOM_DATA, CustomData.of(reference));
        place(player, FOREIGN, foreign);
        require(!view(server, foreignWorkspace, player.getUUID()).get("editable").getAsBoolean(), "Uninvited player can edit a foreign document");
    }

    public static void preparePhysicalMerge(MinecraftServer server) {
        ServerPlayer player = player(server); DeskServer service = DeskServer.get(server);
        // The existing x=18 bridge is adjacent to the foreign desk at x=19. Replacing
        // it re-evaluates a real physical union and must ask the owners for consent.
        BlockPos bridge = PRIMARY.offset(3, 0, 0);
        ItemStack reference = breakAndCollect(player, bridge);
        place(player, bridge, reference);
        JsonArray merges = view(server, workspace, player.getUUID()).getAsJsonArray("merges");
        require(merges.size() == 1, "Physical cross-owner join did not create one approval request");
        pendingMerge = UUID.fromString(merges.get(0).getAsJsonObject().get("id").getAsString());
        require(!service.store.resolve(workspace).equals(service.store.resolve(foreignWorkspace)), "Different owners merged without unanimous consent");
        require(!player.level().getBlockState(bridge).getValue(DeskBlock.EAST), "Pending join must keep a visible seam");
    }

    public static void approveMergeOnServer(MinecraftServer server) {
        ServerPlayer player = player(server); DeskServer service = DeskServer.get(server);
        int before = count(server, workspace) + count(server, foreignWorkspace);
        JsonObject approval = action("approveMerge"); approval.addProperty("requestId", pendingMerge.toString());
        success(service.store.action(workspace, player.getUUID(), approval));
        require(!service.store.resolve(workspace).equals(service.store.resolve(foreignWorkspace)), "One owner approval completed a two-owner merge");
        success(service.store.action(foreignWorkspace, FOREIGN_OWNER, approval));
        require(service.store.resolve(workspace).equals(service.store.resolve(foreignWorkspace)), "Unanimous owner approvals failed to merge");
        require(count(server, workspace) == before, "Approved merge lost or duplicated elements");
        require(view(server, workspace, FOREIGN_OWNER).get("owner").getAsBoolean(), "Approved merge lost coproperty");
    }

    public static void open(MinecraftServer server, BlockPos pos) {
        ServerPlayer player = player(server);
        player.teleportTo(16.5, 80, 11.5);
        DeskServer.get(server).open(player, desk(player.level(), pos));
    }

    public static void saveExpected(MinecraftServer server) {
        expectedState = view(server, workspace, player(server).getUUID()).deepCopy();
        require(expectedState.getAsJsonArray("elements").size() >= 9, "Editor did not produce the complete tool set");
        server.saveEverything(false, true, true);
    }

    public static void assertReloaded(MinecraftServer server) {
        ServerPlayer player = player(server); DeskServer service = DeskServer.get(server);
        require(service.store.resolve(desk(player.level(), PRIMARY).workspace).equals(service.store.resolve(workspace)), "World reload lost the desk document reference");
        JsonObject actual = view(server, workspace, player.getUUID());
        require(actual.getAsJsonArray("elements").equals(expectedState.getAsJsonArray("elements")), "Actual world save/load changed document elements");
        require(actual.get("name").equals(expectedState.get("name")), "World reload lost the document name");
        require(count(server, recoveryWorkspace) == 1 && player.level().getBlockState(RECOVERY).is(CanvasDesk.DESK), "Recovered module did not survive world save/load");
        require(service.store.resolve(foreignWorkspace).equals(service.store.resolve(workspace)), "Approved merge alias was lost after restarting the world");
        if (loadWorkspace != null) require(count(server, loadWorkspace) == 10_000, "Load fixture lost elements after actual world reload");
    }

    public static void prepareLoad(MinecraftServer server) {
        ServerPlayer player = player(server); player.teleportTo(80.5, 80, 35.5);
        player.setGameMode(GameType.CREATIVE);
        floor(player.level(), 63, 39, 97, 73);
        measurements.addProperty("heapBeforeLoadBytes", heap());
    }

    public static void placeLoadRow(MinecraftServer server, int row) {
        ServerPlayer player = player(server); long start = System.nanoTime();
        for (int column = 0; column < 32; column++) place(player, new BlockPos(64 + column, 80, 40 + row), new ItemStack(CanvasDesk.DESK_ITEM));
        if (row == 0) loadWorkspace = desk(player.level(), new BlockPos(64, 80, 40)).workspace;
        double previous = measurements.has("modulePlacementMillis") ? measurements.get("modulePlacementMillis").getAsDouble() : 0;
        measurements.addProperty("modulePlacementMillis", previous + (System.nanoTime() - start) / 1_000_000.0);
    }

    public static void writeLoadBatch(MinecraftServer server, int batch) {
        ServerPlayer player = player(server); JsonObject request = action("edit"); request.addProperty("operationId", UUID.randomUUID().toString());
        JsonArray changes = new JsonArray(); long start = System.nanoTime();
        for (int offset = 0; offset < 1_000; offset++) {
            int i = batch * 1_000 + offset;
            JsonObject element = createElement(i % 3 == 0 ? "TEXT" : "RECTANGLE", (i % 100) * 81, (i / 100) * 81, "Ação " + i);
            element.addProperty("w", 48); element.addProperty("h", 32);
            JsonObject change = new JsonObject(); change.addProperty("id", element.get("id").getAsString()); change.addProperty("expectedRevision", -1); change.add("after", element); changes.add(change);
        }
        request.add("changes", changes); success(DeskServer.get(server).store.action(loadWorkspace, player.getUUID(), request));
        double previous = measurements.has("elementWriteMillis") ? measurements.get("elementWriteMillis").getAsDouble() : 0;
        measurements.addProperty("elementWriteMillis", previous + (System.nanoTime() - start) / 1_000_000.0);
    }

    public static void assertLoad(MinecraftServer server) {
        ServerPlayer player = player(server); DeskServer service = DeskServer.get(server); int modules = 0;
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
            DeskBlockEntity tile = desk(player.level(), new BlockPos(64 + x, 80, 40 + z));
            require(service.store.resolve(tile.workspace).equals(service.store.resolve(loadWorkspace)), "Load surface split into different workspaces");
            require(tile.tileX == x * 256 && tile.tileY == z * 256, "Load surface projection drifted"); modules++;
        }
        require(count(server, loadWorkspace) == 10_000, "Runtime load fixture does not contain ten thousand elements");
        long start = System.nanoTime(); JsonObject visible = service.store.view(loadWorkspace, player.getUUID(), 0, 0, 256, 256);
        measurements.addProperty("singleTileQueryMillis", (System.nanoTime() - start) / 1_000_000.0);
        int visibleCount = visible.getAsJsonArray("elements").size(); require(visibleCount > 0 && visibleCount < 100, "Region fetch retrieved the entire load document");
        measurements.addProperty("modules", modules); measurements.addProperty("elements", 10_000);
        measurements.addProperty("singleTileElements", visibleCount); measurements.addProperty("heapAfterLoadBytes", heap());
        measurements.addProperty("measurementScope", "Real integrated-server item placement, document storage, viewport query and JVM heap; excludes frame-time/FPS benchmarking.");
    }

    public static JsonObject view(MinecraftServer server, UUID id, UUID viewer) { return DeskServer.get(server).store.view(id, viewer, -1_000_000, -1_000_000, 1_000_000, 1_000_000); }
    public static int count(MinecraftServer server, UUID id) { JsonObject state = view(server, id, player(server).getUUID()); success(state); return state.getAsJsonArray("elements").size(); }
    private static long heap() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); }
    private static void floor(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            level.setBlock(new BlockPos(x, 79, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
            for (int y = 80; y < 85; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
    }
    private static void place(ServerPlayer player, BlockPos pos, ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos support = pos.below();
        var hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, .5, 0), Direction.UP, support, false);
        ((BlockItem) CanvasDesk.DESK_ITEM).place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit));
        require(player.level().getBlockState(pos).is(CanvasDesk.DESK), "Real BlockItem placement failed at " + pos);
        require(desk(player.level(), pos).workspace != null, "Block placement did not initialize workspace at " + pos);
    }
    private static ItemStack breakAndCollect(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();
        require(level.destroyBlock(pos, true, player, 512), "Desk could not be broken at " + pos);
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1), item -> item.getItem().is(CanvasDesk.DESK_ITEM));
        require(drops.size() == 1, "Expected one real dropped desk, found " + drops.size());
        ItemStack stack = drops.getFirst().getItem().copy(); drops.getFirst().discard(); return stack;
    }
    private static boolean linkedTo(ItemStack stack, UUID id, MinecraftServer server) {
        if (!stack.is(CanvasDesk.DESK_ITEM)) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA); if (data == null) return false;
        try { return DeskServer.get(server).store.resolve(UUID.fromString(data.copyTag().getStringOr("workspace", ""))).equals(DeskServer.get(server).store.resolve(id)); }
        catch (RuntimeException ignored) { return false; }
    }
    private static DeskBlockEntity desk(ServerLevel level, BlockPos pos) { require(level.getBlockEntity(pos) instanceof DeskBlockEntity, "Missing desk block entity at " + pos); return (DeskBlockEntity) level.getBlockEntity(pos); }
    private static void assertProjection(MinecraftServer server, BlockPos pos, double x, double y) {
        DeskBlockEntity desk = desk(player(server).level(), pos); DeskServer service = DeskServer.get(server);
        require(service.store.resolve(desk.workspace).equals(service.store.resolve(workspace)), "Connected module changed document at " + pos);
        double[] offset = service.store.projectionOffset(desk.workspace);
        require(desk.tileX + offset[0] == x && desk.tileY + offset[1] == y, "Projection mismatch at " + pos + ": " + desk.tileX + ", " + desk.tileY);
    }
    private static JsonObject action(String name) { JsonObject value = new JsonObject(); value.addProperty("action", name); return value; }
    private static JsonObject createElement(String type, double x, double y, String text) {
        JsonObject value = new JsonObject(); value.addProperty("id", UUID.randomUUID().toString()); value.addProperty("type", type);
        value.addProperty("x", x); value.addProperty("y", y); value.addProperty("w", 160); value.addProperty("h", 100);
        value.addProperty("text", text); value.addProperty("color", 0x30302c); value.addProperty("thickness", 3); value.add("points", new JsonArray()); value.add("checks", new JsonArray()); return value;
    }
    private static JsonObject edit(JsonObject element) {
        JsonObject value = action("edit"); value.addProperty("operationId", UUID.randomUUID().toString()); JsonArray changes = new JsonArray();
        JsonObject change = new JsonObject(); change.addProperty("id", element.get("id").getAsString()); change.addProperty("expectedRevision", -1); change.add("after", element); changes.add(change); value.add("changes", changes); return value;
    }
    public static void success(JsonObject value) { require(value.has("ok") && value.get("ok").getAsBoolean(), "Server rejected test operation: " + value); }
    public static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
