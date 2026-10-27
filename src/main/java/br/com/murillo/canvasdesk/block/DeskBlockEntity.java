package br.com.murillo.canvasdesk.block;

import br.com.murillo.canvasdesk.CanvasDesk;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import java.util.UUID;

public final class DeskBlockEntity extends BlockEntity {
    public UUID moduleId = UUID.randomUUID();
    public UUID workspace;
    public double tileX, tileY;
    public long projectionOrder;
    public DeskBlockEntity(BlockPos pos, BlockState state) { super(CanvasDesk.DESK_ENTITY, pos, state); }
    @Override protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.putString("moduleId", moduleId.toString());
        if (workspace != null) out.putString("workspace", workspace.toString());
        out.putDouble("tileX", tileX); out.putDouble("tileY", tileY); out.putLong("projectionOrder", projectionOrder);
    }
    @Override protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        moduleId = uuid(in.getStringOr("moduleId", ""), UUID.randomUUID());
        workspace = uuid(in.getStringOr("workspace", ""), null);
        tileX = in.getDoubleOr("tileX",0); tileY = in.getDoubleOr("tileY",0);
        projectionOrder = in.getLongOr("projectionOrder",0);
    }
    @Override protected void applyImplicitComponents(DataComponentGetter getter) {
        super.applyImplicitComponents(getter);
        CustomData data = getter.get(DataComponents.CUSTOM_DATA);
        if (data != null) readReference(data.copyTag());
    }
    public void readReference(CompoundTag tag) {
        workspace = uuid(tag.getStringOr("workspace", ""), null);
        tileX = tag.getDoubleOr("tileX",0); tileY = tag.getDoubleOr("tileY",0);
        projectionOrder = tag.getLongOr("projectionOrder",0);
    }
    public CompoundTag reference() {
        CompoundTag tag = new CompoundTag();
        if (workspace != null) tag.putString("workspace", workspace.toString());
        tag.putDouble("tileX", tileX); tag.putDouble("tileY", tileY); tag.putLong("projectionOrder", projectionOrder);
        return tag;
    }
    @Override protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        builder.set(DataComponents.CUSTOM_DATA, CustomData.of(reference()));
    }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider provider) { return saveWithoutMetadata(provider); }
    public void changed() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    private static UUID uuid(String value, UUID fallback) {
        try { return UUID.fromString(value); } catch (IllegalArgumentException e) { return fallback; }
    }
}
