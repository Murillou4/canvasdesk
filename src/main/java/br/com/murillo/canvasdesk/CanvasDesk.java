package br.com.murillo.canvasdesk;

import br.com.murillo.canvasdesk.block.DeskBlock;
import br.com.murillo.canvasdesk.block.DeskBlockEntity;
import br.com.murillo.canvasdesk.network.CanvasPayload;
import br.com.murillo.canvasdesk.server.DeskServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CanvasDesk implements ModInitializer {
    public static final String MOD_ID = "canvasdesk";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final Identifier DESK_ID = id("desk");
    public static final DeskBlock DESK = Registry.register(BuiltInRegistries.BLOCK, DESK_ID,
            new DeskBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, DESK_ID))
                    .mapColor(MapColor.WOOD).strength(2.0F).sound(SoundType.WOOD).noOcclusion()
                    .pushReaction(PushReaction.IMMOVEABLE)));
    public static final Item DESK_ITEM = Registry.register(BuiltInRegistries.ITEM, DESK_ID,
            new BlockItem(DESK, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, DESK_ID))
                    .useBlockDescriptionPrefix()));
    public static final BlockEntityType<DeskBlockEntity> DESK_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, DESK_ID,
            new BlockEntityType<>(DeskBlockEntity::new, Set.of(DESK)));

    public static Identifier id(String path) { return Identifier.fromNamespaceAndPath(MOD_ID, path); }

    @Override public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(CanvasPayload.TYPE, CanvasPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CanvasPayload.TYPE, CanvasPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CanvasPayload.TYPE, (payload, context) -> context.server().execute(() -> DeskServer.get(context.server()).receive(context.player(), payload)));
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> entries.accept(DESK_ITEM));
        ServerLifecycleEvents.SERVER_STARTED.register(DeskServer::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(DeskServer::stop);
        ServerTickEvents.END_SERVER_TICK.register(server -> DeskServer.get(server).tick());
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> DeskServer.get(level.getServer()).loadChunk(level, chunk));
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> DeskServer.get(level.getServer()).unloadChunk(level, chunk));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> DeskServer.registerCommands(dispatcher));
        LOGGER.info("Canvas Desk 1.0.0 initialized");
    }
}
