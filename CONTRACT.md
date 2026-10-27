# Canvas Desk implementation contract

Java package: `br.com.murillo.canvasdesk`. Minecraft 26.3, Java 25, Fabric Loader 0.19.5, API 0.164.0+26.3, Loom 1.17.21, Gradle 9.6.0. Native Mojang names, no mappings/remapping. Client and main source sets are separate.

## Ownership of work

- Root: Gradle/bootstrap, Minecraft blocks/items, topology, network adapter, in-world renderer, integration and runtime validation.
- Core agent: `src/main/java/br/com/murillo/canvasdesk/core/**` and core unit tests only.
- Editor agent: `src/client/java/br/com/murillo/canvasdesk/client/editor/**` only.
- Assets agent: `src/main/resources/assets/**`, `src/main/resources/data/**`, `art/**` and asset generation helper only.

## Transport and element schema

One typed Fabric custom payload carries a UTF-8 JSON object. Root owns transport. Editor gets `Consumer<JsonObject> send`, receives `acceptState(JsonObject)`, and obtains Minecraft from Screen.

Element JSON: `id` UUID string, `type` one of STROKE,TEXT,RECTANGLE,ELLIPSE,LINE,ARROW,CONNECTOR,NOTE,CHECKLIST; `x`,`y`,`w`,`h` double; `color` opaque RGB integer; `thickness` float; `text` string; `points` array of `{x,y}` in document coordinates; `from`,`to` optional element UUID; `checks` array of booleans; `revision` long; `group` optional UUID. Defaults: x/y=0,w=100,h=60,color=0x30302c,thickness=3,text empty,points/checks empty. Typed internal records/classes must be used in core; JSON is the codec/transport boundary. A stroke has absolute points and bounds x/y/w/h. Use world coordinates mapped to 256 document units per module.

Client editor class `CanvasScreen extends Screen`: constructor `(JsonObject state, Consumer<JsonObject> send)` and public `acceptState(JsonObject state)`. Editor requests use `action`:

- `edit`: `operationId` UUID, `changes` array of `{id,expectedRevision,after}`; after is complete element or null for deletion. Creation expects revision -1. All changes atomic. Undo/redo through server actions `undo`,`redo`.
- `view`: `minX,minY,maxX,maxY`; root updates viewport and returns view.
- `invite`/`revoke`: `player` UUID string. Owner only.
- `approveMerge`: `requestId` UUID string. Owner only.
- `rename`: `name` string. Owner only.
- `close`: close session.

Server state: `workspace` UUID, `name`, `revision`, `editable` boolean, `owner` boolean, `owners` and `editors` UUID string arrays, `elements` array, `merges` array of `{id,owners,editors,approved}`; `message` optional human-readable feedback, `ok` boolean. `originX`,`originY` are editor's initially opened tile coordinates, added by root. `players` array `{id,name}` is added by root for inviting online players. State updates are authoritative; editor should preserve pan/zoom/selection while replacing elements. root handles region sync and acknowledgement.

## Core facade required by root

`public final class CanvasStore implements AutoCloseable` in core:

- `CanvasStore(Path directory)` (throws IOException).
- `UUID create(UUID owner)` (durable creation).
- `UUID resolve(UUID workspace)` aliases to canonical ID, unknown throws.
- `JsonObject view(UUID workspace, UUID viewer, double minX, double minY, double maxX, double maxY)`.
- `JsonObject action(UUID workspace, UUID actor, JsonObject request)` returns `{ok,message,workspace}` and handles edit/undo/redo/invite/revoke/rename/approveMerge. Every accepted mutation is durable before success.
- `JsonObject requestMerge(UUID target, UUID source, double dx, double dy, UUID actor)` auto merges only when both solely belong to actor; otherwise creates consent request with all owners+editors shown. Source objects translated by dx/dy. Merges preserve element IDs, aliases, owners/editors and groups; linked connector endpoints follow moved objects. All owners must approve; ACL changes invalidate pending approval. root requests merging only for physically adjacent modules and invalidates pending merges on topology change using next API.
- `void invalidateMerges(UUID workspace)`.
- `JsonArray list(UUID actor, boolean operator)` for recovery; records `{id,name}`.
- `void close()`.

Unknown IDs must fail safely, never auto-create a replacement document. Broken/lost modules do not delete documents. Core uses snapshots + checksummed journal and safe recovery; include meaningful tests including fault tails, merge/restart, concurrent revision conflicts, dedup, permissions and undo.

## Assets

Block ID `canvasdesk:desk`. Four boolean blockstate properties `north`,`south`,`east`,`west`. Multipart desk top/base plus conditional outside edge/legs. Top is y=14..16, dynamic content drawn at y=16+epsilon. All modules cover full 16x16 top, no raised rim cutting across content. Item definition modern `assets/canvasdesk/items/desk.json` referencing item or block model. Recipe PPP / CRC / WWW (P paper,C copper ingot,R redstone,W #minecraft:planks) produces one desk. PT-BR and EN translations. UI can use native glyphs plus custom sprites in `textures/gui/sprites/tools/`.
