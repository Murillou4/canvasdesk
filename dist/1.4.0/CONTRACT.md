# Canvas Desk 1.4.0 implementation contract

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

Generate from sources, never edit a JAR. `release -x test -x check` packages main/client only, source JAR/project, docs and SHA-256 into dist/1.4.0. Personal installations stay unchanged.

Unit tests and focused GUI/world smoke are time bounded; real two-client collaboration remains a separate acceptance boundary. Record current evidence separately from 1.1.0. Asset generation and static validation are separate modes. No Minecraft client is launched by either mode.

## 1.2.1 interaction contract

The ordinary DeskBlock.useItemOn fallback MUST return TRY_WITH_EMPTY_HAND, matching vanilla 26.3. PASS skips useWithoutItem even when the held stack is empty. Tests must exercise gameMode.useItemOn instead of calling DeskServer.open directly.

Opening requests surface calculation without changing the topology epoch. Initial state may omit surface and set surfaceUpdating=true; the editor opens its loading panel and accepts the later completed snapshot. Advance opening/sentSurfaceRevision/projection bookkeeping only after successful send.

Assistance resolves the server/client eye ray, not only the vanilla block hit. Accept the entire compatible front face and four complete orthogonal neighboring cells; reject diagonal corners. Prioritize direct faces before surrounding regions; tie UP/RIGHT/DOWN/LEFT. Query only loaded chunks near the interaction ray; rendering consumes a cached immutable result. Sneaking bypasses assistance.

UseBlockCallback and UseItemCallback intercept client input only when a candidate exists, including invalid candidates. Return FAIL to prevent vanilla fallback/double placement; air use can iterate both hands, so deduplicate per player/tick. Synchronize the vanilla carried slot before sending the payload. Choose MAIN module first, otherwise OFF only if MAIN is empty.

New request assistPlace: operationId UUID, hand name, anchor packed position, moduleId UUID, target packed position, direction name, yaw/pitch. It needs no editor session. Server uses its own eye/inventory/world, recomputes from the finite requested orientation, compares the expected anchor/module/target/direction, checks cooldown/mode/range/world border/permissions, and dispatches through ServerPlayerGameMode.useItemOn. Scope the expected placement across callbacks and revalidate before item.place; a changed anchor must fail without selecting another target. Keep at most 64 placement receipts per online player and accept at most one new attempt per server tick. Reply kind=assist is routed separately from editor state.

The focused interaction smoke runs in an isolated test profile with a 180-second external deadline and explicit bounded waits. Existing user processes are never cleanup targets. Full editor/multiplayer acceptance is independent of this smoke.

## 1.3.0 editor and world checklist contract

CanvasEditorLayout owns the GUI rectangles: 94% x 92% panel, no fixed 800x500 cap, header 20, footer 14 and reserved side rails 24. Widgets remain at least 20 units. The left rail starts with selection/undo/redo/fit; tools overflow by available height. The right rail holds access/help/close/properties. Painting, camera, tooltips and input share these rectangles.

Fit uses the connected physical bounds with 8 GUI units of padding per side and uniform scale. Fit and wheel share maximum zoom 5; the effective minimum can fall below .005 for large surfaces. Preserve manual camera across resize/topology changes; F restores automatic framing.

Tiles additionally carry moduleId. SurfaceCache retains immutable interaction metadata and font-derived layout alongside pixels, updating metadata even on an unchanged image. The front-paper eye ray uses BoardPlane and printable DeskGeometry bounds for both formats; no unloaded chunk is requested. Hover calculation runs on ticks, rendering only consumes the cached target. Topmost content blocks covered boxes.

worldChecklist requires no editor session: operationId, pos, moduleId, canonical workspace and tileX/Y, elementId, expectedRevision, logical row, desired checked value, finite yaw/pitch. Empty main hand, no sneak and no selected module are required. Server checks current identity/projection, reach, occlusion, border, interaction permission, editor ACL and checklist/row/revision, then dispatches through vanilla useItemOn and protection callbacks. The scoped interaction revalidates after callbacks and consumes only the pinned task; it must never open a replaced block or editor. Replies kind=checklist route independently of Screen state.

Client font metrics identify the precise visual row. Dedicated servers have no Font dependency: they validate paper position, box column and bounded checklist region. This narrow request grants no ability beyond an editor's existing document ACL. Core setChecklistItem reconstructs only checks from the authoritative element, does not revise connectors, and uses normal durable history/receipts. A no-op records a receipt event without changing element/document revision or history. Existing snapshots need no migration; receipt journal events are additive in 1.3.0.

One physical press sends one operation; held-button repeats and the offhand fallback remain consumed until release. Conflicts refresh tiles without optimistic inversion. Changed tiles are pushed through a bounded queue of at most 32 per server tick, including readers and open editors. Online failed-request receipts remain bounded to 64/player and rate limited to one new request per tick.

Use tools/validate-editor.ps1 after compileGametestJava for the isolated 1.3.0 smoke (180 seconds total, no unbounded screen waits). Source-set test classes never ship. Distinguish this one-client evidence from real two-client collaboration and other unexecuted acceptance checks.


## 1.4.0 input, silhouettes and new document types

InlineTextEditor is a focused GuiEventListener/NarratableEntry. Focus ownership calls Minecraft.onTextInputFocusChange, updating the native TextInputManager and IME area. Rebuild/resize restores focus; finish, removal and permission loss release it. Dialog rebuilds must not leave focus on removed widgets. Text input owns its shortcuts before tools.

worldSurface publishes the existing revisioned SurfaceLayout outside editor sessions. SurfaceContour joins exposed physical module edges, preserving concavities and holes. Cache world segments per module/revision; BEFORE_BLOCK_OUTLINE suppresses the per-block outline. No module discovery occurs while drawing. Checkbox hover retains the action text, with no individual shape outline.

CanvasElement adds EMBED(url, text as title) and MATERIALS(text as title, items [{id,quantidade}]). Optional fields default empty on older element types. Immutable transformations, grouping, joins, object storage and history retain both fields. MaterialItem validates exact positive integers, merges duplicates with checked addition and preserves first order, up to 512 input entries before duplicate merging. Registry lookup stays client-only; unavailable IDs remain document data. MaterialsLayout is shared by editor and world. Native GuiItemAtlas uses a private FeatureRenderDispatcher/buffers, bounded preparation and cache invalidation on resource reload. Live browser uploads and item preparation occur at GameRenderer.render HEAD, outside any open RenderPearl pass.

## 1.4.0 browser and media transport

A separate browser source set builds a bundled worker JAR resource; its JCEF/Chromium classes and native libraries are never loaded by the mod classloader. First use extracts the worker by content hash, then starts a helper JVM using argument lists. NativeRuntime pins publisher SHA-256 digests, serializes installation across processes, verifies the installed file manifest and bounds download/installation. Helpers communicate through newline JSON commands/events and a seqlock-protected 1280x720 RGBA shared frame, capped at 30 FPS. Main-thread work only handles cached state and GPU uploads. At most two helpers are active per client; cancellation and cleanup target only owned processes.

mediaSubscribe/mediaControl require no editor session: canonical workspace, elementId, packed anchor pos, moduleId. Controls add source, mediaSession, mediaRevision, command and its bounded position/url/scroll data. Server checks loaded module identity, canonical projection, visible surface, distance, ACL, interaction permission and Fabric block protection callbacks. Commands are ordered by transient revision; stale sessions/revisions return current state without editing a document. mediaState replies are routed separately from durable edit ACKs and never replace a CanvasScreen. Owners/guests control; readers follow. A session UUID prevents commands from an old source/join timeline from affecting a new one.

MediaTimeline uses monotonic server time. Snapshots include source, navigationUrl, mediaSession, mediaRevision, playing, position, scrollX/Y and serverTime; clients project time between snapshots and correct drift above .5 seconds. Source changes, removal and joins discard affected sessions; server restart starts paused at zero. Only source/title/geometry live in the durable document. URLs/scroll and video clocks are shared; browser cookies, form contents and audio preference never enter the payload. JCEF navigation rejects non-HTTP top-level destinations and leaves form submissions local. YouTube uses its official iframe API through a loopback origin/referrer; direct files use HTMLMediaElement.

Validation evidence must name real Windows/SDL input separately from direct native text callbacks. Fabric GameTest normally cancels real SDL events; the isolated test mixin re-enables them only for the features role, in its own mixin package. It never ships in the mod JAR. Every smoke attempt has an independent directory and an external 180-second total watchdog, recording PID/creation time before cleanup. Two real clients are a separate acceptance run.


### Final 1.4.0 lifecycle details

`mediaClosed` carries workspace/element/source/session identity and explicitly stops the matching old helper. Late events cannot close a replacement session. `navigationRevision` distinguishes explicit same-address reloads from periodic snapshots; a shared explicit navigation leaves private form context. Bounded nonce receipts remain separate from periodic state and durable document acknowledgements.

Native installation records verified file size/mtime alongside hashes; unchanged manifests reuse the verified cache and changed files are rehashed. Helper profiles are unique per session. Child process handles are recorded before shutdown so cleanup can include descendants after an unexpected parent exit. Native MIME detection for extensionless videos uses a bounded HEAD request off the game thread. EmbedGeometry letterboxes the same 16:9 browser texture in editor and world without distorting its coordinates.

Validation profiles are muted and the synthetic fixture has no audio stream. The helper's test-only browserSilent property adds Chromium --mute-audio; this property is not enabled by normal runClient or by the released mod. Hidden SDL validation windows never take desktop focus, and ordinary GameTest input guards remain active in hidden mode. Actual OS/SDL evidence and callback evidence must remain separately labeled. Final minor GUI edits were build-checked without another client launch; see VALIDATION_STATUS.md.
