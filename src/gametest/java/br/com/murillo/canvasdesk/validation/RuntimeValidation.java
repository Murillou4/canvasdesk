package br.com.murillo.canvasdesk.validation;

import br.com.murillo.canvasdesk.client.editor.CanvasScreen;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

/** Real Minecraft client test. The test mod and its fixtures never ship in the release JAR. */
public final class RuntimeValidation implements FabricClientGameTest {
    private final Path output = Path.of(System.getProperty("canvasdesk.reportDir", "artifacts/runtime")).toAbsolutePath();
    private final List<String> gameplay = new ArrayList<>(), ui = new ArrayList<>(), screenshots = new ArrayList<>();

    @Override public void runTest(ClientGameTestContext context) {
        if (!Boolean.getBoolean("canvasdesk.selftest")) throw new IllegalStateException("Explicit canvasdesk.selftest flag is required");
        try {
            Files.createDirectories(output); write("RUNNING", null);
            Consumer<String> shot = name -> { try { screenshot(context, name); } catch (Exception error) { throw new RuntimeException(error); } };
            TestWorldSave save;
            try (var world = context.worldBuilder().create()) {
                save = world.getWorldSave();
                context.waitFor(client -> client.player != null && client.level != null, 600);
                world.getConnection().waitForChunksDownload();
                context.runOnClient(client -> { client.options.guiScale().set(2); client.options.renderDistance().set(6); client.resizeGui(); });
                world.getServer().runOnServer(GameplayChecks::setupRectangle);
                context.waitTicks(20);
                world.getServer().runOnServer(GameplayChecks::assertRectangle);
                gameplay.add("Actual recipe manager with mixed plank tags; one-item survival placement; 2x2 connections across four chunks PASS"); write("RUNNING", null);
                world.getServer().runOnServer(GameplayChecks::expandRing); context.waitTicks(20);
                world.getServer().runOnServer(GameplayChecks::assertRing);
                gameplay.add("Incremental 3x3 ring expansion, genuine center hole and projection continuity PASS");
                world.getServer().runOnServer(server -> GameplayChecks.open(server, GameplayChecks.PRIMARY));
                context.waitForScreen(CanvasScreen.class);
                ClientUiChecks.run(context, world.getServer(), GameplayChecks.workspace(), ui, shot);
                gameplay.add("Client editor operations acknowledged and persisted by integrated server PASS"); write("RUNNING", null);
                context.runOnClient(client -> client.setScreenAndShow(null)); context.waitTicks(5);
                world.getServer().runOnServer(GameplayChecks::detachAndReposition); context.waitTicks(15);
                world.getServer().runOnServer(GameplayChecks::reconnect); context.waitTicks(20);
                world.getServer().runOnServer(GameplayChecks::assertReconnected);
                gameplay.add("Real block loot preserves custom_data; detached module shares document; isolated and connected replacements preserve/adopt projection PASS");
                world.getServer().runOnServer(GameplayChecks::permissionsAndLostRecovery); context.waitTicks(10);
                gameplay.add("Server ACL invite/revoke/denied write; lost-item document listing and actual player recovery command PASS");
                world.getServer().runOnServer(server -> GameplayChecks.open(server, GameplayChecks.FOREIGN));
                context.waitForScreen(CanvasScreen.class);
                ClientUiChecks.inspectReadOnly(context, world.getServer(), GameplayChecks.foreignWorkspace(), ui, shot);
                context.runOnClient(client -> client.setScreenAndShow(null)); context.waitTicks(5);
                world.getServer().runOnServer(GameplayChecks::preparePhysicalMerge); context.waitTicks(15);
                world.getServer().runOnServer(server -> GameplayChecks.open(server, GameplayChecks.PRIMARY)); context.waitForScreen(CanvasScreen.class);
                ClientUiChecks.inspectAccess(context, shot);
                ui.add("Real pending physical cross-owner join: access and unanimous-consent confirmation screens PASS");
                context.runOnClient(client -> client.setScreenAndShow(null)); context.waitTicks(5);
                world.getServer().runOnServer(GameplayChecks::approveMergeOnServer);
                gameplay.add("Two-owner consent and coproperty through server actor API PASS; this does not represent two real clients");

                // Load fixture is a real 32x32 block surface; work is divided across
                // ticks to avoid disguising a frozen server as a passing test.
                world.getServer().runOnServer(GameplayChecks::prepareLoad);
                for (int row = 0; row < 32; row++) { final int current = row; world.getServer().runOnServer(server -> GameplayChecks.placeLoadRow(server, current)); context.waitTick(); }
                context.waitTicks(40);
                for (int batch = 0; batch < 10; batch++) { final int current = batch; world.getServer().runOnServer(server -> GameplayChecks.writeLoadBatch(server, current)); context.waitTick(); }
                world.getServer().runOnServer(GameplayChecks::assertLoad);
                gameplay.add("Real 32x32 modules and 10,000 persisted elements, bounded region query and heap/timing measurements PASS"); write("RUNNING", null);
                context.runOnClient(client -> client.setScreenAndShow(null));
                world.getServer().runCommand("tp @a 80.5 86 34.5 0 42");
                context.waitTicks(40); world.getConnection().waitForChunksRender(); shot.accept("12-load-32x32-world");
                world.getServer().runCommand("tp @a 17.5 84 10.5 0 46"); context.waitTicks(25);
                world.getConnection().waitForChunksRender(); shot.accept("13-world-surface-content");
                world.getServer().runOnServer(server -> GameplayChecks.open(server, GameplayChecks.PRIMARY)); context.waitForScreen(CanvasScreen.class);
                shot.accept("14-editor-before-save"); context.runOnClient(client -> client.setScreenAndShow(null)); context.waitTicks(5);
                world.getServer().runOnServer(GameplayChecks::saveExpected);
            }
            context.waitFor(client -> client.player == null && client.level == null, 600);
            try (var reloaded = save.open()) {
                context.waitFor(client -> client.player != null && client.level != null, 600);
                reloaded.getConnection().waitForChunksDownload();
                reloaded.getServer().runOnServer(GameplayChecks::assertReloaded);
                gameplay.add("Actual Minecraft world stop, chunk save, document journal close and world reopen preserve elements, name, recovery and merged aliases PASS");
                reloaded.getServer().runOnServer(server -> GameplayChecks.open(server, GameplayChecks.PRIMARY)); context.waitForScreen(CanvasScreen.class);
                shot.accept("15-editor-after-world-reload"); context.runOnClient(client -> client.setScreenAndShow(null));
            }
            context.waitFor(client -> client.player == null && client.level == null, 600);
            gameplay.add("PASS"); ui.add("PASS"); write("PASS", null);
        } catch (Throwable error) {
            gameplay.add("FAIL " + error); ui.add("FAIL " + error);
            try { screenshot(context, "failure"); write("FAIL", error.toString()); } catch (Exception ignored) {}
            throw error instanceof RuntimeException runtime ? runtime : new RuntimeException(error);
        }
    }

    private void screenshot(ClientGameTestContext context, String name) throws Exception {
        context.waitTicks(3);
        context.runOnClient(client -> client.gui.toastManager().clear());
        Path source = context.takeScreenshot(name), destination = output.resolve(name + ".png");
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING); screenshots.add(destination.toString());
    }

    private void write(String status, String error) throws Exception {
        Files.write(output.resolve("gameplay-selftest.log"), gameplay, StandardCharsets.UTF_8);
        Files.write(output.resolve("ui-selftest.log"), ui, StandardCharsets.UTF_8);
        var report = new LinkedHashMap<String, Object>(); report.put("timestamp", Instant.now().toString()); report.put("status", status);
        report.put("target", "Minecraft 26.3 / Fabric Loader 0.19.5 / API 0.164.0+26.3 / Java 25");
        report.put("modVersion", FabricLoader.getInstance().getModContainer("canvasdesk").orElseThrow().getMetadata().getVersion().getFriendlyString());
        report.put("artifactBoundary", "Development classes via Fabric client gametest; release JAR production bootstrap is separate.");
        report.put("gameplay", gameplay); report.put("ui", ui); report.put("screenshots", screenshots); report.put("load", GameplayChecks.measurements());
        report.put("twoRealClients", "NOT_RUN: tests use one real client and integrated server. Additional owner UUIDs exercise server authorization, not another connection.");
        report.put("hardPowerLoss", "NOT_RUN: forced operating-system shutdown is not performed; corrupt-tail journal recovery is covered by core tests.");
        report.put("chunkUnload", "Actual world closure/reopen unloads and reloads all chunks; distance-triggered chunk unload is not independently measured.");
        report.put("clientShutdown", "Test closes both disposable world connections; Fabric client gametest process exits after suite completion, verified by runner.");
        if (error != null) report.put("failure", error);
        Files.writeString(output.resolve("summary.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
    }
}
