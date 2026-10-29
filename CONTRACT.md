# Canvas Desk 1.2.1 implementation contract

Minecraft 26.3, Java 25, Fabric Loader 0.19.5, API 0.164.0+26.3, Loom 1.17.21, Gradle 9.6.0. Native Mojang names; split main/client source sets. Never load client types on a dedicated server.

## Boundaries and compatibility

`core` owns vectors, revisions, ACLs, per-player undo and durable journal. It has no block dependency. `server` owns physical modules, connected components and session validation. `block` shares geometry, placement and surface coordinates. Rendering, native text input and GUI belong to `src/client`.

Retain `canvasdesk:desk` as the vertical block/item; `canvasdesk:drawing_desk` is horizontal. COLOR is a DyeColor blockstate, default WHITE; WALL defaults false for legacy centered boards. Items carry only color in BLOCK_STATE, and workspace/tileX/tileY/projectionOrder in CUSTOM_DATA. Loot and crafting_transmute copy these components. Physical module identity is fresh on placement. Format comes from the block ID, never camera pitch.

Documents outlive blocks, items, chunks and disconnected components. Preserve legacy projection fields and aliases; only merge owners can consent. Unknown references fail safely without creating replacement documents. Legacy snapshots and history arrays remain readable.

## Physical coordinates and shared geometry

One module is always 256 document units. BoardPlane supplies viewer-right, document-down, normal and model/world transforms for both formats. Modules connect only when format, orientation, mounting plane and canonical document match.

Editable `art/geometry.json` generates models and the resource loaded by DeskGeometry for selection/collision. Paper is at canonical z=7/16; overlay is displaced .002 blocks toward its normal. Wall models move canonical depth by 6.5/16 and omit rear feet/tray. Horizontal models map canonical (x,y,z) to (x,9.5-z,y) before yaw rotation.

An exposed rim covers 16 document units. Both geometry and UVs crop the corresponding subrectangle; a connected seam uses no inset. SurfaceLayout holds module rectangles and the printable mask, with a spatial index. Shapes retain complete vector geometry across missing modules; only printable ink can initiate interactions. Undo/redo may restore hidden content.

NormalizeJob processes bounded work per server tick. Topology changes invalidate old component snapshots; completed jobs publish full revisioned snapshots and notify sessions. Persisted module index includes unloaded chunks. Reanchoring sessions uses surviving members of their previous component within the existing 10-block range; its fixed physical reference prevents spurious camera shifts.

## Transport

CanvasPayload remains a typed, bounded, fragmented UTF-8 JSON payload. CanvasScreen constructor `(JsonObject state, Consumer<JsonObject> send)` and `acceptState(JsonObject)` remain the editor boundary.

Elements retain IDs and types STROKE/TEXT/RECTANGLE/ELLIPSE/LINE/ARROW/CONNECTOR/NOTE/CHECKLIST; x/y/w/h, color, thickness, fontSize, text, points, endpoint IDs, checks, revision and group remain supported. TEXT width is its wrapping capacity; measured content bounds drive selection and proportional font resize.

Requests: edit(operationId,changes,surfaceRevision,optional typingGroup), view(minX,minY,maxX,maxY), undo/redo, invite/revoke(player), rename(name), approveMerge(requestId), close. Changes are atomic `{id,expectedRevision,after}`, with null after deleting. New IDs expect revision -1. Existing ACL and dedup rules remain authoritative.

State retains workspace/name/revision/editable/owner/owners/editors/elements/merges, originX/Y, players, ok/message and acknowledgements. New fields:

- surfaceRevision and surfaceUpdating describe physical readiness independently of document revision.
- surface, sent on opening and revision changes: revision, format, bounds and modules `{id,x,y,color,left,right,up,down}`. Booleans indicate connected edges; missing edges receive the exposed rim inset.
- viewShiftX/Y changes camera only when the projection basis changes.
- closed/message explicitly ends an unavailable session.

Surface metadata is cached and omitted from unchanged document-only updates. The last edited element is pinned in session responses so an inline draft keeps its authoritative baseline even after panning.

## Text, history and durability

Native CanvasTextModel/MultilineTextField provides cursor, selection, clipboard and navigation; CanvasTextLayout drives the GUI, caret and world raster. Default Portuguese bitmap glyphs are supported; glyph/tile/layout caches invalidate on resource reload. TEXT is inline; NOTE/CHECKLIST retain their dialogs.

750ms idle commits end a typing burst; 5s checkpoints during continuous typing reuse a UUID typingGroup. One request is in flight, while local input continues; queued finalized bursts preserve order. Optional persisted typing metadata coalesces only contiguous single-TEXT operations by the same actor/group/element, including derived connectors. It keeps earliest before/latest after and terminates on intervening document edits, undo/redo or merge. Replay applies the same deterministic rule. Existing history array format is preserved.

A receipt is successful only after journal force. Never persist on every keystroke. Conflict/permission loss stops sending, keeps the draft available to copy and never overwrites remote state. Closing waits for outstanding saves. Unexpected close attempts asynchronous local draft recovery; this is not a durable server receipt.

Additional editor sounds are an optional local preference, default off, rate limited and routed through master/blocks volume. Never mute or stop game audio.

## Packaging and verification

Generate from sources, never edit a JAR. `release -x test -x check` packages main/client only, source JAR/project, docs and SHA-256 into dist/1.2.1. Personal installations stay unchanged.

Unit tests are time bounded; GUI/world/multiplayer acceptance remains manual in this round. Record current evidence separately from 1.1.0. Asset generation and static validation are separate modes. No Minecraft client is launched by either mode.

## 1.2.1 interaction contract

The ordinary DeskBlock.useItemOn fallback MUST return TRY_WITH_EMPTY_HAND, matching vanilla 26.3. PASS skips useWithoutItem even when the held stack is empty. Tests must exercise gameMode.useItemOn instead of calling DeskServer.open directly.

Opening requests surface calculation without changing the topology epoch. Initial state may omit surface and set surfaceUpdating=true; the editor opens its loading panel and accepts the later completed snapshot. Advance opening/sentSurfaceRevision/projection bookkeeping only after successful send.

Assistance resolves the server/client eye ray, not only the vanilla block hit. Accept the entire compatible front face and four complete orthogonal neighboring cells; reject diagonal corners. Prioritize direct faces before surrounding regions; tie UP/RIGHT/DOWN/LEFT. Query only loaded chunks near the interaction ray; rendering consumes a cached immutable result. Sneaking bypasses assistance.

UseBlockCallback and UseItemCallback intercept client input only when a candidate exists, including invalid candidates. Return FAIL to prevent vanilla fallback/double placement; air use can iterate both hands, so deduplicate per player/tick. Synchronize the vanilla carried slot before sending the payload. Choose MAIN module first, otherwise OFF only if MAIN is empty.

New request assistPlace: operationId UUID, hand name, anchor packed position, moduleId UUID, target packed position, direction name, yaw/pitch. It needs no editor session. Server uses its own eye/inventory/world, recomputes from the finite requested orientation, compares the expected anchor/module/target/direction, checks cooldown/mode/range/world border/permissions, and dispatches through ServerPlayerGameMode.useItemOn. Scope the expected placement across callbacks and revalidate before item.place; a changed anchor must fail without selecting another target. Keep at most 64 placement receipts per online player and accept at most one new attempt per server tick. Reply kind=assist is routed separately from editor state.

The focused interaction smoke runs in an isolated test profile with a 180-second external deadline and explicit bounded waits. Existing user processes are never cleanup targets. Full editor/multiplayer acceptance is independent of this smoke.
