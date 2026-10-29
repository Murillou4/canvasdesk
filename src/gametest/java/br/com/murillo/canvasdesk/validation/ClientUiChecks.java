package br.com.murillo.canvasdesk.validation;

import br.com.murillo.canvasdesk.client.editor.CanvasScreen;
import br.com.murillo.canvasdesk.client.editor.CanvasViewport;
import br.com.murillo.canvasdesk.server.DeskServer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Test-source-only input driver. It exercises the live Screen and verifies the real server document. */
public final class ClientUiChecks {
    private ClientUiChecks() {}

    public static void run(ClientGameTestContext context, TestServerContext server, UUID workspace,
                           List<String> ui, Consumer<String> screenshot) throws Exception {
        context.waitForScreen(CanvasScreen.class);
        waitSaved(context);
        require(view(server, workspace).getAsJsonArray("elements").isEmpty(), "UI scenario must start with an empty document");

        press(context, InputConstants.KEY_B, 0);
        drag(context, 30, 30, 65, 40, 110, 55, InputConstants.MOUSE_BUTTON_LEFT, 0);
        waitSaved(context);
        require(element(server, workspace, "STROKE").getAsJsonArray("points").size() >= 3, "Freehand input was not persisted");
        ui.add("PASS freehand mouse path persisted");

        text(context, InputConstants.KEY_T, 30, 75, "Ação e criação\nConexão salva");
        require(element(server, workspace, "TEXT").get("text").getAsString().contains("Conexão"), "Accented multiline text was lost");
        text(context, InputConstants.KEY_N, 230, 20, "Ideias da bancada\nPapel, cobre e redstone");
        text(context, InputConstants.KEY_C, 230, 150, "Desenhar uma ideia\nCompartilhar a mesa");
        ui.add("PASS native multiline text, accented text, notes and checklist creation");

        draw(context, InputConstants.KEY_R, 440, 20, 580, 75);
        draw(context, InputConstants.KEY_O, 440, 95, 580, 145);
        draw(context, InputConstants.KEY_L, 450, 170, 540, 180);
        draw(context, InputConstants.KEY_A, 450, 195, 580, 220);
        press(context, InputConstants.KEY_K, 0);
        click(context, 510, 47, 0); click(context, 510, 120, 0); waitSaved(context);
        require(view(server, workspace).getAsJsonArray("elements").size() == 9, "Every tool must create one persisted primitive");
        JsonObject connector = element(server, workspace, "CONNECTOR");
        require(connector.has("from") && connector.has("to"), "Connector must link real elements");
        ui.add("PASS rectangle, ellipse, line, arrow and attached connector mouse tools");

        press(context, InputConstants.KEY_V, 0);
        click(context, 254, 175, 0); waitSaved(context);
        require(element(server, workspace, "CHECKLIST").get("fontSize").getAsDouble()==18,"New checklist default font size");
        require(element(server, workspace, "CHECKLIST").getAsJsonArray("checks").get(0).getAsBoolean(), "Checklist hit target did not toggle");
        ui.add("PASS checklist checkbox toggles and persists");

        JsonObject rectangle = element(server, workspace, "RECTANGLE");
        double rectangleX = rectangle.get("x").getAsDouble(), rectangleY = rectangle.get("y").getAsDouble();
        drag(context, rectangleX + 50, rectangleY + 25, rectangleX + 56, rectangleY + 29,
            rectangleX + 62, rectangleY + 33, InputConstants.MOUSE_BUTTON_LEFT, 0);
        waitSaved(context);
        rectangle = element(server, workspace, "RECTANGLE");
        near(rectangle.get("x").getAsDouble(), rectangleX + 12, "Selection move x");
        near(rectangle.get("y").getAsDouble(), rectangleY + 8, "Selection move y");
        JsonObject movedConnector = element(server, workspace, "CONNECTOR");
        require(!movedConnector.getAsJsonArray("points").equals(connector.getAsJsonArray("points")), "Connector did not follow its moved element");
        double x2 = rectangle.get("x").getAsDouble() + rectangle.get("w").getAsDouble();
        double y2 = rectangle.get("y").getAsDouble() + rectangle.get("h").getAsDouble();
        double oldWidth = rectangle.get("w").getAsDouble(), oldHeight = rectangle.get("h").getAsDouble();
        drag(context, x2, y2, x2 + 10, y2 + 8, x2 + 20, y2 + 16, InputConstants.MOUSE_BUTTON_LEFT, 0); waitSaved(context);
        rectangle = element(server, workspace, "RECTANGLE");
        near(rectangle.get("w").getAsDouble(), oldWidth + 20, "Resize width");
        near(rectangle.get("h").getAsDouble(), oldHeight + 16, "Resize height");
        ui.add("PASS real selection move and resize; attached connector follows");

        JsonObject ellipse = element(server, workspace, "ELLIPSE");
        click(context, ellipse.get("x").getAsDouble() + 30, ellipse.get("y").getAsDouble() + 25, InputConstants.MOD_SHIFT);
        double beforeRect = rectangle.get("x").getAsDouble(), beforeEllipse = ellipse.get("x").getAsDouble();
        press(context, InputConstants.KEY_RIGHT, 0); waitSaved(context);
        near(element(server, workspace, "RECTANGLE").get("x").getAsDouble(), beforeRect + 1, "Multiselect rectangle");
        near(element(server, workspace, "ELLIPSE").get("x").getAsDouble(), beforeEllipse + 1, "Multiselect ellipse");
        press(context, InputConstants.KEY_Z, InputConstants.MOD_CONTROL); waitSaved(context);
        near(element(server, workspace, "RECTANGLE").get("x").getAsDouble(), beforeRect, "Undo multiselect");
        press(context, InputConstants.KEY_Y, InputConstants.MOD_CONTROL); waitSaved(context);
        near(element(server, workspace, "ELLIPSE").get("x").getAsDouble(), beforeEllipse + 1, "Redo multiselect");
        ui.add("PASS Shift multiselect and atomic keyboard move, undo, redo");

        clickWidget(context, "#"); replaceText(context, "#336699"); saveText(context); waitSaved(context);
        require(element(server, workspace, "RECTANGLE").get("color").getAsInt() == 0x336699, "Custom color was not saved");
        clickWidget(context, "6"); waitSaved(context);
        near(element(server, workspace, "ELLIPSE").get("thickness").getAsDouble(), 6, "Thickness control");
        ui.add("PASS custom RGB color and thickness applied to selected elements");

        press(context, InputConstants.KEY_E, 0); click(context, 495, 175, 0); waitSaved(context);
        require(view(server, workspace).getAsJsonArray("elements").size() == 8, "Eraser did not remove the complete element");
        press(context, InputConstants.KEY_Z, InputConstants.MOD_CONTROL); waitSaved(context);
        require(view(server, workspace).getAsJsonArray("elements").size() == 9, "Undo did not restore erased element");
        ui.add("PASS whole-element eraser and server undo restoration");

        press(context, InputConstants.KEY_V, 0); click(context, 45, 85, 0); press(context, InputConstants.KEY_RETURN, 0);
        replaceText(context, "Ação revisada\nConexão continua salva"); saveText(context); waitSaved(context);
        require(element(server, workspace, "TEXT").get("text").getAsString().startsWith("Ação revisada"), "Existing text edit was lost");
        ui.add("PASS selection and native text editing preserve accents");

        JsonObject checklist=element(server,workspace,"CHECKLIST");
        click(context,checklist.get("x").getAsDouble()+100,checklist.get("y").getAsDouble()+60,0);
        clickWidgetContaining(context,"Fonte","Font");replaceText(context,"24");saveText(context);waitSaved(context);
        checklist=element(server,workspace,"CHECKLIST");
        near(checklist.get("fontSize").getAsDouble(),24,"Font size control persists");
        press(context,InputConstants.KEY_F,0);context.waitTicks(3);
        double cw=checklist.get("w").getAsDouble(),ch=checklist.get("h").getAsDouble();
        double cx=checklist.get("x").getAsDouble()+cw,cy=checklist.get("y").getAsDouble()+ch;
        drag(context,cx,cy,cx+8,cy+4,cx+16,cy+8,InputConstants.MOUSE_BUTTON_LEFT,0);waitSaved(context);
        checklist=element(server,workspace,"CHECKLIST");
        near(checklist.get("w").getAsDouble()/cw,checklist.get("h").getAsDouble()/ch,"Proportional checklist dimensions");
        near(checklist.get("fontSize").getAsDouble()/24,checklist.get("w").getAsDouble()/cw,"Proportional checklist text");
        press(context,InputConstants.KEY_Z,InputConstants.MOD_CONTROL);waitSaved(context);
        near(element(server,workspace,"CHECKLIST").get("fontSize").getAsDouble(),24,"Undo proportional resize");
        ui.add("PASS shared checklist hit layout, adjustable font, proportional resize and undo");

        double[] before = camera(context);
        context.runOnClient(client -> screen(client).mouseScrolled(320, 190, 0, 1));
        double[] after = camera(context); require(after[2] > before[2], "Mouse-wheel zoom did not change camera");
        context.runOnClient(client -> {
            CanvasScreen screen = screen(client);
            screen.mouseClicked(mouse(310, 190, InputConstants.MOUSE_BUTTON_MIDDLE, 0), false);
            screen.mouseDragged(mouse(340, 210, InputConstants.MOUSE_BUTTON_MIDDLE, 0), 30, 20);
            screen.mouseReleased(mouse(340, 210, InputConstants.MOUSE_BUTTON_MIDDLE, 0));
        });
        double[] panned = camera(context); require(Math.abs(panned[0] - after[0]) > 1, "Middle-button pan did not change camera");
        press(context, InputConstants.KEY_F, 0); context.waitTicks(3);
        ui.add("PASS zoom at cursor, middle-button pan and global content fit");
        screenshot.accept("ui-canvas-all-tools");

        context.runOnClient(client -> {
            CanvasScreen screen = screen(client); screen.mouseClicked(mouse(15, 12, InputConstants.MOUSE_BUTTON_LEFT, 0), false);
            screen.mouseReleased(mouse(15, 12, InputConstants.MOUSE_BUTTON_LEFT, 0));
        });
        replaceText(context, "Ateliê de ideias"); saveText(context);
        server.waitFor(game -> DeskServer.get(game).store.view(workspace, game.getPlayerList().getPlayers().getFirst().getUUID(), -1e6, -1e6, 1e6, 1e6)
            .get("name").getAsString().equals("Ateliê de ideias"));
        ui.add("PASS owner rename saved on real server");

        press(context, InputConstants.KEY_F1, 0); screenshot.accept("ui-controls-help"); press(context, InputConstants.KEY_ESCAPE, 0);
        context.getInput().resizeWindow(854, 480); context.waitTicks(3);
        context.runOnClient(client -> {
            CanvasScreen screen = screen(client);
            require(screen.width >= 320 && screen.height >= 200, "Small-window UI dimensions");
            for (var child : screen.children()) if (child instanceof AbstractWidget widget)
                require(widget.getX() >= 0 && widget.getRight() <= screen.width, "A toolbar control overflows the small GUI");
        });
        press(context, InputConstants.KEY_F, 0); screenshot.accept("ui-responsive-small-window");
        context.getInput().resizeWindow(1280, 720); context.waitTicks(3); press(context, InputConstants.KEY_F, 0);
        ui.add("PASS help screen and responsive 854x480 window");
    }

    public static void inspectAccess(ClientGameTestContext context, Consumer<String> screenshot) throws Exception {
        clickWidgetContaining(context, "Acesso e uniões", "Access and merges");
        screenshot.accept("ui-access-permissions");
        clickWidgetContaining(context, "Aprovar", "Approve");
        screenshot.accept("ui-merge-all-owners-confirmation");
        context.runOnClient(client -> require(screen(client).children().stream().anyMatch(child -> child instanceof AbstractWidget widget
            && (widget.getMessage().getString().equals("Aprovar") || widget.getMessage().getString().equals("Approve"))), "Merge confirmation lacks an explicit approval control"));
        press(context, InputConstants.KEY_ESCAPE, 0);
    }

    public static void inspectReadOnly(ClientGameTestContext context, TestServerContext server, UUID workspace,
                                       List<String> ui, Consumer<String> screenshot) throws Exception {
        context.waitForScreen(CanvasScreen.class);
        long revision = view(server, workspace).get("revision").getAsLong();
        press(context, InputConstants.KEY_B, 0);
        drag(context, 30, 30, 50, 40, 80, 60, InputConstants.MOUSE_BUTTON_LEFT, 0);
        press(context, InputConstants.KEY_DELETE, 0);
        press(context, InputConstants.KEY_Z, InputConstants.MOD_CONTROL);
        context.waitTicks(4);
        require(view(server, workspace).get("revision").getAsLong() == revision, "Read-only editor changed the document");
        screenshot.accept("ui-read-only-permissions");
        ui.add("PASS read-only live UI refuses draw, deletion and undo without changing server revision");
    }

    private static void text(ClientGameTestContext context, int tool, double x, double y, String content) throws Exception {
        press(context, tool, 0); click(context, x, y, 0); replaceText(context, content); saveText(context); waitSaved(context);
    }
    private static void draw(ClientGameTestContext context, int tool, double x1, double y1, double x2, double y2) throws Exception {
        press(context, tool, 0); drag(context, x1, y1, (x1 + x2) / 2, (y1 + y2) / 2, x2, y2, InputConstants.MOUSE_BUTTON_LEFT, 0); waitSaved(context);
    }
    private static void saveText(ClientGameTestContext context) throws Exception { press(context, InputConstants.KEY_RETURN, InputConstants.MOD_CONTROL); }
    private static void replaceText(ClientGameTestContext context, String value) throws Exception {
        press(context, InputConstants.KEY_A, InputConstants.MOD_CONTROL);
        context.runOnClient(client -> {
            for (int codepoint : value.codePoints().toArray()) {
                if (codepoint == '\n') screen(client).keyPressed(key(InputConstants.KEY_RETURN, 0));
                else screen(client).charTyped(new CharacterEvent(codepoint));
            }
        });
    }
    private static void press(ClientGameTestContext context, int code, int modifiers) throws Exception {
        context.runOnClient(client -> { screen(client).keyPressed(key(code, modifiers)); screen(client).keyReleased(key(code, modifiers)); });
    }
    private static KeyEvent key(int key, int modifiers) {
        int keycode = key >= InputConstants.KEY_A && key <= InputConstants.KEY_Z ? 'a' + key - InputConstants.KEY_A
            : key == InputConstants.KEY_RETURN ? 13 : key == InputConstants.KEY_ESCAPE ? 27 : 0;
        return new KeyEvent(key, keycode, modifiers);
    }
    private static MouseButtonEvent mouse(double x, double y, int button, int modifiers) { return new MouseButtonEvent(x, y, new MouseButtonInfo(button, modifiers)); }
    private static void click(ClientGameTestContext context, double x, double y, int modifiers) throws Exception {
        context.runOnClient(client -> {
            CanvasScreen screen = screen(client); CanvasViewport v = viewport(screen);
            double sx = v.screenX(x), sy = v.screenY(y);
            require(v.contains(sx, sy), "UI scenario attempted an off-canvas click at " + x + "," + y);
            screen.mouseClicked(mouse(sx, sy, InputConstants.MOUSE_BUTTON_LEFT, modifiers), false);
            screen.mouseReleased(mouse(sx, sy, InputConstants.MOUSE_BUTTON_LEFT, modifiers));
        });
    }
    private static void drag(ClientGameTestContext context, double x1, double y1, double xm, double ym, double x2, double y2, int button, int modifiers) throws Exception {
        context.runOnClient(client -> {
            CanvasScreen screen = screen(client); CanvasViewport v = viewport(screen);
            double sx1 = v.screenX(x1), sy1 = v.screenY(y1), sxm = v.screenX(xm), sym = v.screenY(ym), sx2 = v.screenX(x2), sy2 = v.screenY(y2);
            require(v.contains(sx1, sy1) && v.contains(sx2, sy2), "UI scenario attempted an off-canvas drag");
            screen.mouseClicked(mouse(sx1, sy1, button, modifiers), false);
            screen.mouseDragged(mouse(sxm, sym, button, modifiers), sxm - sx1, sym - sy1);
            screen.mouseDragged(mouse(sx2, sy2, button, modifiers), sx2 - sxm, sy2 - sym);
            screen.mouseReleased(mouse(sx2, sy2, button, modifiers));
        });
    }
    private static void clickWidget(ClientGameTestContext context, String label) throws Exception { clickWidgetContaining(context, label, label); }
    private static void clickWidgetContaining(ClientGameTestContext context, String first, String second) throws Exception {
        context.runOnClient(client -> {
            CanvasScreen screen = screen(client);
            AbstractWidget target = screen.children().stream().filter(child -> child instanceof AbstractWidget).map(child -> (AbstractWidget) child)
                .filter(widget -> widget.getMessage().getString().equals(first) || widget.getMessage().getString().equals(second)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing native control " + first + " / " + second));
            double x = target.getX() + target.getWidth() / 2.0, y = target.getY() + target.getHeight() / 2.0;
            require(target.active, "Control is unexpectedly inactive: " + first);
            screen.mouseClicked(mouse(x, y, InputConstants.MOUSE_BUTTON_LEFT, 0), false);
            screen.mouseReleased(mouse(x, y, InputConstants.MOUSE_BUTTON_LEFT, 0));
        });
    }
    private static JsonObject view(TestServerContext context, UUID workspace) throws Exception {
        return context.computeOnServer(server -> DeskServer.get(server).store.view(workspace, server.getPlayerList().getPlayers().getFirst().getUUID(), -1e6, -1e6, 1e6, 1e6));
    }
    private static JsonObject element(TestServerContext context, UUID workspace, String type) throws Exception {
        JsonArray values = view(context, workspace).getAsJsonArray("elements");
        for (JsonElement value : values) if (value.getAsJsonObject().get("type").getAsString().equals(type)) return value.getAsJsonObject();
        throw new AssertionError("Missing persisted " + type + " element");
    }
    private static CanvasScreen screen(Minecraft client) {
        if (!(client.gui.screen() instanceof CanvasScreen screen)) throw new AssertionError("CanvasScreen is not open");
        return screen;
    }
    private static CanvasViewport viewport(CanvasScreen screen) { return (CanvasViewport) read(screen, "viewport"); }
    private static double[] camera(ClientGameTestContext context) throws Exception {
        return context.computeOnClient(client -> { CanvasViewport v = viewport(screen(client)); return new double[]{v.panX, v.panY, v.zoom}; });
    }
    private static void waitSaved(ClientGameTestContext context) {
        context.waitFor(client -> client.gui.screen() instanceof CanvasScreen screen && !(boolean) read(screen, "saving"));
        context.waitTick();
    }
    private static Object read(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException exception) { throw new AssertionError("Cannot inspect live UI state", exception); }
    }
    private static void near(double actual, double expected, String description) { require(Math.abs(actual - expected) < 0.01, description + ": " + actual + " != " + expected); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
