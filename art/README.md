# Canvas Desk artwork 1.2.0

The editable model/shape source is `geometry.json`. `tools/generate-assets.py` and `tools/generate_variants.py` produce game resources, original pixel textures, editable SVG equivalents and the authoring preview. Run with Python 3, Pillow and NumPy. No downloaded art or image generation is required.

`python tools/generate-assets.py` generates only. Add `--validate` to check JSON/PNG decoding, geometry, references, both formats, 16 colors, 6,144 relevant multipart combinations, recipes, loot and translation parity. Generation always updates the resource hash manifest. Reports distinguish static resources from Minecraft runtime.

Canonical vertical paper faces north at z=7/16. Exposed frame width is 1/16 block (16 document units). Connected edges expose the full tile; no per-tile texture rescaling is permitted. The same geometry resource feeds selection and collision. Wall variants move depth 6.5/16 and remove rear supports; horizontal variants flatten the surface onto a supported plate. Tray stationery is decorative and never required to edit.

Colors belong to modules. Generated paper textures, item selectors and DyeColor blockstates retain the existing wood/copper identity. White is the legacy/default background. Transmute recipes preserve custom data and state components; loot copies document references and color.

`asset-reference.png` is a software preview of generated model resources, not a Minecraft screenshot. The current runtime/manual checklist is in `VALIDATION_STATUS.md`.
