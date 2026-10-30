package br.com.murillo.canvasdesk.client.editor;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.*;

public final class MaterialIcons {
    private MaterialIcons() {}
    public static ItemStack stack(String id){var key=Identifier.tryParse(id);if(key!=null&&BuiltInRegistries.ITEM.containsKey(key))return new ItemStack(BuiltInRegistries.ITEM.getValue(key));var substitute=new ItemStack(Items.BARRIER);substitute.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal(id+" · indisponível"));return substitute;}
    public static boolean missing(String id){var key=Identifier.tryParse(id);return key==null||!BuiltInRegistries.ITEM.containsKey(key);}
}
