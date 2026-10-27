package br.com.murillo.canvasdesk.client.editor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.BooleanSupplier;

/** Flat native widget: keyboard focus, narration and tooltips remain Minecraft's. */
final class CanvasButton extends AbstractWidget {
    private final String sprite;
    private final Runnable action;
    private final BooleanSupplier selected;
    private final BooleanSupplier enabled;
    private final Integer swatch;

    CanvasButton(int x, int y, int width, int height, String label, String sprite, Runnable action,
                 BooleanSupplier selected, BooleanSupplier enabled, Integer swatch) {
        super(x, y, width, height, Component.literal(label));
        this.sprite = sprite; this.action = action; this.selected = selected; this.enabled = enabled; this.swatch = swatch;
        setTooltip(Tooltip.create(Component.literal(label)));
    }

    @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        active = enabled.getAsBoolean();
        int bg = selected.getAsBoolean() ? 0xffd8c1a2 : isHoveredOrFocused() ? 0xffe3ddd0 : 0xfff0ece1;
        g.fill(getX(), getY(), getRight(), getBottom(), bg);
        if (isFocused() || selected.getAsBoolean()) g.outline(getX(), getY(), width, height, 0xff99704a);
        if (swatch != null) {
            g.fill(getX() + 4, getY() + 4, getRight() - 4, getBottom() - 4, 0xff000000 | swatch);
        } else if (sprite != null) {
            g.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath("canvasdesk", "tools/" + sprite),
                getX() + (width - 16) / 2, getY() + (height - 16) / 2, 16, 16);
        } else {
            String label = getMessage().getString();
            g.text(Minecraft.getInstance().font, label, getX() + (width - Minecraft.getInstance().font.width(label)) / 2,
                getY() + (height - 8) / 2, active ? 0xff34342e : 0xffa59f93, false);
        }
        if (!active) g.fill(getX(), getY(), getRight(), getBottom(), 0x88f0ece1);
    }

    @Override public void onClick(MouseButtonEvent event, boolean doubleClick) { if (enabled.getAsBoolean()) action.run(); }
    @Override public boolean keyPressed(KeyEvent event) {
        if (active && isFocused() && event.isConfirmation()) { action.run(); return true; }
        return false;
    }
    @Override protected void updateWidgetNarration(NarrationElementOutput narration) { defaultButtonNarrationText(narration); }
}
