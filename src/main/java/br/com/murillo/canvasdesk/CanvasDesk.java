package br.com.murillo.canvasdesk;

import br.com.murillo.canvasdesk.block.DeskBlock;
import br.com.murillo.canvasdesk.block.DeskBlockEntity;
import br.com.murillo.canvasdesk.block.DeskBlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.core.component.DataComponents;
import java.util.Map;
import br.com.murillo.canvasdesk.network.CanvasPayload;
import br.com.murillo.canvasdesk.server.DeskServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
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
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
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
            new DeskBlockItem(DESK, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, DESK_ID))
                    .useBlockDescriptionPrefix()));
    public static final Identifier DRAWING_DESK_ID=id("drawing_desk");
    public static final DeskBlock DRAWING_DESK=Registry.register(BuiltInRegistries.BLOCK,DRAWING_DESK_ID,
        new DeskBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK,DRAWING_DESK_ID))
            .mapColor(MapColor.WOOD).strength(2).sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.IMMOVEABLE),true));
    public static final Item DRAWING_DESK_ITEM=Registry.register(BuiltInRegistries.ITEM,DRAWING_DESK_ID,
        new DeskBlockItem(DRAWING_DESK,new Item.Properties().setId(ResourceKey.create(Registries.ITEM,DRAWING_DESK_ID)).useBlockDescriptionPrefix()));
    public static final CreativeModeTab TAB=Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,id("main"),
            FabricCreativeModeTab.builder().title(Component.translatable("itemGroup.canvasdesk"))
                .icon(()->new ItemStack(DESK_ITEM)).displayItems((parameters,output)->{
                    for(Item item:new Item[]{DESK_ITEM,DRAWING_DESK_ITEM})for(DyeColor color:DyeColor.values()){
                        ItemStack stack=new ItemStack(item);stack.set(DataComponents.BLOCK_STATE,new BlockItemStateProperties(Map.of("color",color.getSerializedName())));output.accept(stack);
                    }
                }).build());
    public static final BlockEntityType<DeskBlockEntity> DESK_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, DESK_ID,
            new BlockEntityType<>(DeskBlockEntity::new, Set.of(DESK,DRAWING_DESK)));

    public static Identifier id(String path) { return Identifier.fromNamespaceAndPath(MOD_ID, path); }

    @Override public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(CanvasPayload.TYPE, CanvasPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CanvasPayload.TYPE, CanvasPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CanvasPayload.TYPE, (payload, context) -> context.server().execute(() -> DeskServer.get(context.server()).receive(context.player(), payload)));
        ServerLifecycleEvents.SERVER_STARTED.register(DeskServer::start);
        // Chunk unload callbacks still run during shutdown. Release the document lock only after them.
        ServerLifecycleEvents.SERVER_STOPPED.register(DeskServer::stop);
        ServerTickEvents.END_SERVER_TICK.register(server -> DeskServer.get(server).tick());
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> DeskServer.get(level.getServer()).loadChunk(level, chunk));
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> DeskServer.get(level.getServer()).unloadChunk(level, chunk));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> DeskServer.registerCommands(dispatcher));
        LOGGER.info("Canvas Desk 1.5.0 initialized");
    }
}
