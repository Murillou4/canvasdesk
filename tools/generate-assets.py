#!/usr/bin/env python3
"""Deterministic, editable Canvas Desk pixel-art and multipart resource source.

Run with Python 3 + Pillow from any directory. No downloaded art is used.
Every PNG also has a pixel-preserving editable SVG in art/source/.
"""
from __future__ import annotations

import copy
import hashlib
import json
import math
from pathlib import Path
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/canvasdesk"
DATA = ROOT / "src/main/resources/data"
ART = ROOT / "art"
PALETTE = {
    "paper": "#fafaf7", "paper_light": "#ffffff", "paper_fiber": "#f3f3ef",
    "wood": "#4a342a", "wood_dark": "#35261f", "wood_light": "#634737",
    "copper": "#b97751", "copper_light": "#dda071", "copper_dark": "#885138",
    "ink": "#34342e", "muted": "#79766b", "sage": "#758770", "redstone": "#974f40",
}


def json_file(path: Path, obj: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def save_pixel_art(path: Path, image: Image.Image) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)
    # Exact pixels rather than an embedded bitmap: each swatch is editable in Inkscape.
    try:
        name = path.relative_to(ASSETS).with_suffix(".svg")
    except ValueError:
        name = Path(path.stem + ".svg")
    dest = ART / "source" / name
    dest.parent.mkdir(parents=True, exist_ok=True)
    groups: dict[str, list[str]] = {}
    rgba = image.convert("RGBA")
    for y in range(image.height):
        x = 0
        while x < image.width:
            color = rgba.getpixel((x, y))
            end = x + 1
            while end < image.width and rgba.getpixel((end, y)) == color:
                end += 1
            if color[3]:
                fill = "#%02x%02x%02x" % color[:3]
                opacity = "" if color[3] == 255 else f' opacity="{color[3]/255:.4f}"'
                groups.setdefault(fill + opacity, []).append(f'<rect x="{x}" y="{y}" width="{end-x}" height="1"/>')
            x = end
    lines = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {image.width} {image.height}" shape-rendering="crispEdges">']
    for fill, rects in groups.items():
        if ' opacity=' in fill:
            fill, opacity = fill.split(' opacity=', 1)
            lines.append(f'<g fill="{fill}" opacity={opacity}>')
        else:
            lines.append(f'<g fill="{fill}">')
        lines.extend(rects)
        lines.append("</g>")
    lines.append("</svg>")
    dest.write_text("\n".join(lines) + "\n", encoding="utf-8")


def textures() -> None:
    rng = random.Random(0xCA7A5DE5)
    paper = Image.new("RGB", (32, 32), PALETTE["paper"])
    dp = ImageDraw.Draw(paper)
    for _ in range(92):
        x, y = rng.randrange(32), rng.randrange(32)
        dp.point((x, y), fill=rng.choice([PALETTE["paper_light"], PALETTE["paper_fiber"]]))
    for x, y in [(3, 7), (18, 2), (23, 24), (8, 26), (28, 13)]:
        dp.line((x, y, x + 1, y), fill=PALETTE["paper_fiber"])
    save_pixel_art(ASSETS / "textures/block/desk_canvas.png", paper)

    wood = Image.new("RGB", (32, 32), PALETTE["wood"])
    dw = ImageDraw.Draw(wood)
    for y in range(32):
        for x in range(32):
            v = rng.randint(-4, 4)
            base = (74, 52, 42)
            wood.putpixel((x, y), tuple(c + v for c in base))
    for y in [3, 11, 19, 27]:
        dw.line((0, y, 31, y), fill=PALETTE["wood_dark"])
        for start, end in [(2, 11), (17, 28)]:
            dw.line((start, y + 1, end, y + 1), fill=PALETTE["wood_light"])
    for y in [7, 15, 23, 31]:
        dw.line((rng.randrange(3), y, 27 + rng.randrange(4), y), fill="#533a2d")
    dw.line((8, 4, 8, 10), fill="#3c2c24")
    dw.line((23, 20, 23, 26), fill="#3c2c24")
    save_pixel_art(ASSETS / "textures/block/desk_wood.png", wood)

    end = Image.new("RGB", (32, 32), PALETTE["wood"])
    de = ImageDraw.Draw(end)
    for inset in [1, 5, 10, 14]:
        de.rectangle((inset, inset, 31 - inset, 31 - inset), outline=PALETTE["wood_dark"])
        if inset < 14:
            de.line((inset + 1, inset + 1, 30 - inset, inset + 1), fill=PALETTE["wood_light"])
    save_pixel_art(ASSETS / "textures/block/desk_endgrain.png", end)

    copper = Image.new("RGB", (32, 32), PALETTE["copper"])
    dc = ImageDraw.Draw(copper)
    for y in range(32):
        for x in range(32):
            v = rng.randint(-5, 5)
            copper.putpixel((x, y), tuple(c + v for c in (185, 119, 81)))
    dc.rectangle((0, 0, 31, 31), outline=PALETTE["copper_dark"])
    dc.line((1, 1, 30, 1), fill=PALETTE["copper_light"])
    dc.line((1, 1, 1, 30), fill=PALETTE["copper_light"])
    for x, y in [(5, 5), (26, 5), (5, 26), (26, 26)]:
        dc.rectangle((x - 1, y - 1, x + 1, y + 1), fill=PALETTE["copper_dark"])
        dc.point((x - 1, y - 1), fill=PALETTE["copper_light"])
    save_pixel_art(ASSETS / "textures/block/desk_copper.png", copper)

    circuit = Image.new("RGB", (16, 16), PALETTE["wood_dark"])
    dr = ImageDraw.Draw(circuit)
    dr.line((0, 8, 4, 8, 4, 5, 11, 5, 11, 8, 15, 8), fill=PALETTE["copper"], width=2)
    dr.rectangle((6, 7, 9, 10), fill=PALETTE["redstone"])
    dr.line((7, 7, 9, 7), fill="#bd6950")
    save_pixel_art(ASSETS / "textures/block/desk_circuit.png", circuit)


def cuboid(a, b, texture="wood", faces=None):
    element = {"from": list(a), "to": list(b), "faces": {}}
    for face in ["down", "up", "north", "south", "west", "east"]:
        element["faces"][face] = {"texture": "#" + texture}
    if faces:
        for face, tex in faces.items():
            element["faces"][face] = {"texture": "#" + tex}
    return element


def rotate_element(element, degrees):
    result = copy.deepcopy(element)
    if degrees == 0:
        return result
    coords = []
    for x in [element["from"][0], element["to"][0]]:
        for z in [element["from"][2], element["to"][2]]:
            a = math.radians(degrees)
            coords.append((round(8 + (x - 8) * math.cos(a) - (z - 8) * math.sin(a), 4),
                           round(8 + (x - 8) * math.sin(a) + (z - 8) * math.cos(a), 4)))
    result["from"][0], result["from"][2] = min(p[0] for p in coords), min(p[1] for p in coords)
    result["to"][0], result["to"][2] = max(p[0] for p in coords), max(p[1] for p in coords)
    horizontal = ["north", "east", "south", "west"]
    result["faces"] = {}
    for face, definition in element["faces"].items():
        target = horizontal[(horizontal.index(face) + degrees // 90) % 4] if face in horizontal else face
        result["faces"][target] = copy.deepcopy(definition)
    return result


def models():
    from generate_variants import models as generate_models
    generate_models(ROOT, save_pixel_art)


def tools():
    ink, copper, muted = PALETTE["ink"], PALETTE["copper"], PALETTE["muted"]
    names = ["select", "pen", "text", "note", "checklist", "rectangle", "ellipse", "line", "arrow", "connector", "eraser", "pan", "resize", "undo", "redo", "delete", "fit", "invite", "check", "close", "save", "lock", "merge", "palette", "embed", "materials"]
    for name in names:
        im = Image.new("RGBA", (16, 16))
        d = ImageDraw.Draw(im)
        if name == "select":
            d.polygon([(3, 2), (3, 12), (6, 9), (9, 14), (11, 13), (8, 8), (12, 8)], fill=ink)
            d.line((4, 4, 4, 9), fill=copper)
        elif name == "pen":
            d.polygon([(3, 10), (10, 3), (13, 6), (6, 13), (2, 14)], fill=ink)
            d.line((5, 10, 10, 5), fill=copper, width=2)
            d.polygon([(2, 14), (3, 10), (6, 13)], fill="#e2cfa8")
            d.point((2, 14), fill=ink)
        elif name == "embed":
            d.rounded_rectangle((1, 3, 14, 12), radius=2, outline=ink, width=2)
            d.polygon([(6, 5), (6, 10), (10, 8)], fill=copper)
        elif name == "materials":
            for x in [2, 9]:
                d.polygon([(x, 5), (x+3, 3), (x+6, 5), (x+3, 7)], fill=copper)
                d.line((x, 5, x, 10, x+3, 12, x+6, 10, x+6, 5), fill=ink)
                d.line((x+3, 7, x+3, 12), fill=ink)
        elif name == "text":
            d.rectangle((3, 3, 13, 4), fill=ink)
            d.rectangle((7, 4, 8, 12), fill=ink)
            d.line((5, 13, 10, 13), fill=copper, width=2)
        elif name == "note":
            d.rectangle((3, 2, 12, 13), outline=ink, width=2)
            d.rectangle((5, 5, 10, 5), fill=copper)
            d.line((5, 8, 10, 8), fill=ink)
            d.polygon([(9, 13), (12, 10), (12, 13)], fill=copper)
        elif name == "checklist":
            for y in [3, 8]:
                d.rectangle((2, y, 5, y + 3), outline=ink)
                d.line((8, y + 1, 13, y + 1), fill=ink, width=2)
            d.line((2, 3, 4, 5, 7, 1), fill=copper, width=2)
        elif name in ("rectangle", "ellipse"):
            if name == "rectangle":
                d.rectangle((2, 3, 13, 12), outline=ink, width=2)
            else:
                d.ellipse((2, 3, 13, 12), outline=ink, width=2)
            d.rectangle((11, 10, 13, 12), fill=copper)
        elif name in ("line", "arrow", "connector"):
            if name == "connector":
                d.rectangle((1, 1, 5, 5), outline=ink, width=2)
                d.rectangle((10, 10, 14, 14), outline=ink, width=2)
                d.line((5, 3, 9, 3, 9, 12, 10, 12), fill=copper, width=2)
            else:
                d.line((3, 12, 12, 3), fill=ink, width=2)
                if name == "arrow":
                    d.line((6, 3, 12, 3, 12, 9), fill=copper, width=2)
                else:
                    d.rectangle((2, 11, 4, 13), fill=copper)
                    d.rectangle((11, 2, 13, 4), fill=copper)
        elif name == "eraser":
            d.polygon([(2, 9), (9, 2), (14, 7), (7, 14), (5, 14)], fill=ink)
            d.polygon([(4, 9), (8, 13), (12, 9), (8, 5)], fill=copper)
            d.line((9, 14, 14, 14), fill=muted)
        elif name == "pan":
            d.line((8, 2, 8, 13), fill=ink, width=2)
            d.line((2, 8, 13, 8), fill=ink, width=2)
            d.line((5, 4, 8, 1, 11, 4), fill=copper, width=2)
            d.line((5, 11, 8, 14, 11, 11), fill=copper, width=2)
            d.line((4, 5, 1, 8, 4, 11), fill=ink)
            d.line((11, 5, 14, 8, 11, 11), fill=ink)
        elif name == "resize":
            d.line((3, 12, 12, 3), fill=ink, width=2)
            d.line((7, 3, 12, 3, 12, 8), fill=copper, width=2)
            d.line((3, 7, 3, 12, 8, 12), fill=copper, width=2)
        elif name in ("undo", "redo"):
            d.line((5, 5, 10, 5, 13, 8, 13, 11, 10, 13, 6, 13), fill=ink, width=2)
            d.polygon([(2, 5), (7, 1), (7, 9)], fill=copper)
            if name == "redo":
                im = im.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
        elif name == "delete":
            d.rectangle((4, 5, 11, 13), outline=ink, width=2)
            d.line((2, 3, 13, 3), fill=copper, width=2)
            d.line((6, 1, 9, 1), fill=ink, width=2)
            d.line((7, 7, 7, 11), fill=muted)
        elif name == "fit":
            for pts in [(2, 6, 2, 2, 6, 2), (10, 2, 14, 2, 14, 6), (14, 10, 14, 14, 10, 14), (6, 14, 2, 14, 2, 10)]:
                d.line(pts, fill=ink, width=2)
            d.rectangle((6, 6, 10, 10), outline=copper)
        elif name == "invite":
            d.ellipse((3, 2, 8, 7), fill=ink)
            d.arc((1, 7, 11, 17), 180, 360, fill=ink, width=2)
            d.line((12, 7, 12, 13), fill=copper, width=2)
            d.line((9, 10, 15, 10), fill=copper, width=2)
        elif name == "check":
            d.line((2, 8, 6, 12, 14, 3), fill=ink, width=3)
            d.point((3, 8), fill=copper)
        elif name == "close":
            d.line((3, 3, 12, 12), fill=ink, width=2)
            d.line((3, 12, 12, 3), fill=ink, width=2)
        elif name == "save":
            d.rectangle((2, 2, 13, 13), fill=ink)
            d.rectangle((4, 2, 10, 6), fill=copper)
            d.rectangle((4, 9, 11, 13), fill="#e8dfc7")
            d.rectangle((8, 2, 9, 4), fill=ink)
        elif name == "lock":
            d.arc((4, 1, 11, 10), 180, 360, fill=ink, width=2)
            d.rectangle((3, 7, 12, 14), fill=ink)
            d.rectangle((7, 9, 8, 12), fill=copper)
        elif name == "merge":
            d.line((3, 3, 3, 6, 8, 10, 8, 14), fill=ink, width=2)
            d.line((12, 3, 12, 6, 8, 10), fill=copper, width=2)
            d.line((5, 12, 8, 15, 11, 12), fill=ink, width=2)
        elif name == "palette":
            d.ellipse((1, 2, 14, 14), fill=ink)
            d.rectangle((9, 9, 15, 15), fill=(0, 0, 0, 0))
            for x, y, c in [(4, 6, "#b97751"), (8, 4, "#758770"), (11, 6, "#c9aa67"), (4, 10, "#e8dfc7")]:
                d.rectangle((x, y, x + 1, y + 1), fill=c)
        save_pixel_art(ASSETS / f"textures/gui/sprites/tools/{name}.png", im)
    for alias, original in {"brush": "pen", "share": "invite"}.items():
        save_pixel_art(ASSETS / f"textures/gui/sprites/tools/{alias}.png", Image.open(ASSETS / f"textures/gui/sprites/tools/{original}.png"))


def languages():
    en = {
        "block.canvasdesk.desk": "Canvas Desk Board", "itemGroup.canvasdesk": "Canvas Desk",
        "tooltip.canvasdesk.desk": "A modular whiteboard. Connect panels sideways or upwards.",
        "tooltip.canvasdesk.desk.linked": "Linked workspace: %s", "tooltip.canvasdesk.desk.open": "Right-click to draw and write.",
        "tooltip.canvasdesk.desk.preserved": "The desk keeps its document when broken.",
        "screen.canvasdesk.title": "Canvas Desk", "screen.canvasdesk.read_only": "Read only",
        "screen.canvasdesk.saved": "Saved", "screen.canvasdesk.saving": "Saving…",
        "screen.canvasdesk.conflict": "Another player changed this element. Refresh and try again.",
        "screen.canvasdesk.color": "Color", "screen.canvasdesk.thickness": "Stroke width",
        "screen.canvasdesk.text": "Text", "screen.canvasdesk.invite": "Invite editor", "screen.canvasdesk.revoke": "Remove editor",
        "screen.canvasdesk.merge": "Join documents", "screen.canvasdesk.merge.pending": "Document join waiting for approval",
        "screen.canvasdesk.merge.approve": "Approve join", "screen.canvasdesk.merge.owners": "Owners", "screen.canvasdesk.merge.editors": "Editors",
        "screen.canvasdesk.help": "Drag: draw / move · Middle button: pan · Wheel: zoom · Ctrl+Z / Ctrl+Y: undo / redo",
        "screen.canvasdesk.projection_adjusted": "The displayed region was aligned with the oldest desk. Your drawings are safe.",
        "message.canvasdesk.recovered": "Recovered a linked desk for %s.", "message.canvasdesk.no_permission": "You do not have permission to edit this document.",
        "message.canvasdesk.out_of_range": "Move closer to the desk to keep editing.", "message.canvasdesk.unknown_workspace": "This workspace could not be found. No replacement document was created.",
        "message.canvasdesk.merge_pending": "This join needs every document owner's approval.", "message.canvasdesk.store_error": "The document could not be saved. Your change was not confirmed.",
        "lore.canvasdesk.desk": "Copper carries the pulse; paper keeps the thought.",
    }
    pt = {
        "block.canvasdesk.desk": "Quadro Canvas Desk", "itemGroup.canvasdesk": "Canvas Desk",
        "tooltip.canvasdesk.desk": "Um quadro branco modular. Amplie para os lados e para cima.",
        "tooltip.canvasdesk.desk.linked": "Documento vinculado: %s", "tooltip.canvasdesk.desk.open": "Clique direito para desenhar e escrever.",
        "tooltip.canvasdesk.desk.preserved": "A mesa preserva seu documento quando quebrada.",
        "screen.canvasdesk.title": "Canvas Desk", "screen.canvasdesk.read_only": "Somente leitura",
        "screen.canvasdesk.saved": "Salvo", "screen.canvasdesk.saving": "Salvando…",
        "screen.canvasdesk.conflict": "Outro jogador alterou este elemento. Atualize e tente novamente.",
        "screen.canvasdesk.color": "Cor", "screen.canvasdesk.thickness": "Espessura do traço",
        "screen.canvasdesk.text": "Texto", "screen.canvasdesk.invite": "Convidar editor", "screen.canvasdesk.revoke": "Remover editor",
        "screen.canvasdesk.merge": "Unir documentos", "screen.canvasdesk.merge.pending": "União de documentos aguardando aprovação",
        "screen.canvasdesk.merge.approve": "Aprovar união", "screen.canvasdesk.merge.owners": "Donos", "screen.canvasdesk.merge.editors": "Editores",
        "screen.canvasdesk.help": "Arraste: desenhar / mover · Botão do meio: navegar · Roda: zoom · Ctrl+Z / Ctrl+Y: desfazer / refazer",
        "screen.canvasdesk.projection_adjusted": "A região exibida foi alinhada à mesa mais antiga. Seus desenhos estão preservados.",
        "message.canvasdesk.recovered": "Mesa vinculada a %s recuperada.", "message.canvasdesk.no_permission": "Você não tem permissão para editar este documento.",
        "message.canvasdesk.out_of_range": "Aproxime-se da mesa para continuar editando.", "message.canvasdesk.unknown_workspace": "Este documento não foi encontrado. Nenhum documento substituto foi criado.",
        "message.canvasdesk.merge_pending": "Esta união precisa da aprovação de todos os donos dos documentos.", "message.canvasdesk.store_error": "Não foi possível salvar o documento. Sua alteração não foi confirmada.",
        "lore.canvasdesk.desk": "O cobre conduz o pulso; o papel guarda o pensamento.",
    }
    en["block.canvasdesk.drawing_desk"] = "Canvas Desk Drawing Board"
    pt["block.canvasdesk.drawing_desk"] = "Prancheta Canvas Desk"
    labels = {
        "select": ("Select", "Selecionar"), "pen": ("Freehand", "Desenho livre"), "brush": ("Freehand", "Desenho livre"),
        "text": ("Text", "Texto"), "note": ("Note", "Nota"), "checklist": ("Checklist", "Lista de tarefas"),
        "rectangle": ("Rectangle", "Retângulo"), "ellipse": ("Ellipse", "Elipse"), "line": ("Line", "Linha"),
        "arrow": ("Arrow", "Seta"), "connector": ("Connector", "Conector"), "eraser": ("Eraser", "Borracha"),
        "pan": ("Pan", "Navegar"), "resize": ("Resize", "Redimensionar"), "undo": ("Undo", "Desfazer"),
        "redo": ("Redo", "Refazer"), "delete": ("Delete", "Excluir"), "fit": ("Fit content", "Enquadrar conteúdo"),
        "share": ("Share", "Compartilhar"), "palette": ("Custom color", "Cor personalizada"),
    }
    for name, (english, portuguese) in labels.items():
        en["tool.canvasdesk." + name] = english
        pt["tool.canvasdesk." + name] = portuguese
    json_file(ASSETS / "lang/en_us.json", en)
    json_file(ASSETS / "lang/pt_br.json", pt)


def data_files():
    from generate_variants import data_files as generate_data
    generate_data(ROOT)


def icon():
    im = Image.new("RGBA", (64, 64))
    d = ImageDraw.Draw(im)
    d.rectangle((4, 4, 59, 59), fill=PALETTE["wood_dark"])
    d.rectangle((7, 7, 56, 56), fill=PALETTE["copper"])
    d.rectangle((10, 10, 53, 53), fill=PALETTE["paper"])
    d.rectangle((14, 14, 49, 49), outline=PALETTE["paper_fiber"])
    d.line((19, 25, 24, 19, 35, 19, 40, 25), fill=PALETTE["ink"], width=3)
    d.line((19, 25, 19, 39, 24, 44, 36, 44, 41, 38), fill=PALETTE["ink"], width=3)
    d.line((33, 33, 44, 22), fill=PALETTE["copper"], width=4)
    d.polygon([(29, 37), (31, 31), (35, 35)], fill=PALETTE["ink"])
    for x, y in [(8, 8), (55, 8), (8, 55), (55, 55)]:
        d.rectangle((x, y, x + 1, y + 1), fill=PALETTE["copper_light"])
    save_pixel_art(ASSETS / "icon.png", im)


def preview():
    """Software isometric reference, not a claim of Minecraft runtime validation."""
    width, height = 1440, 980
    image = Image.new("RGB", (width, height), "#eeeae0")
    d = ImageDraw.Draw(image)
    try:
        font = ImageFont.truetype("C:/Windows/Fonts/consola.ttf", 19)
        title = ImageFont.truetype("C:/Windows/Fonts/consolab.ttf", 28)
    except OSError:
        font = ImageFont.load_default()
        title = font
    d.text((40, 30), "CANVAS DESK / MODEL AND PIXEL ASSET REFERENCE", fill=PALETTE["ink"], font=title)
    d.text((40, 69), "Vertical whiteboard | wooden perimeter | continuous 256-unit tiles", fill=PALETTE["muted"], font=font)
    examples = [({(0, 0)}, "ONE MODULE", (240, 320), 9),
                ({(0, 0), (1, 0), (0, 1), (1, 1)}, "2 x 2 / SHARED BOARD", (670, 300), 7),
                ({(0, 0), (1, 0), (2, 0), (0, 1)}, "L BOARD", (1120, 290), 6)]
    state = json.loads((ASSETS / "blockstates/desk.json").read_text(encoding="utf-8"))
    geometry = {}
    for path in (ASSETS / "models/block").glob("*.json"):
        geometry[path.stem] = json.loads(path.read_text(encoding="utf-8"))["elements"]
    texture_images = {key: Image.open(ASSETS / f"textures/block/desk_{name}.png").convert("RGB") for key, name in {"canvas": "canvas", "wood": "wood", "end": "endgrain", "copper": "copper", "circuit": "circuit"}.items()}
    texture_images["ink"] = Image.new("RGB",(32,32),"#292929")
    texture_images["eraser"] = Image.new("RGB",(32,32),"#686868")
    def projection(x, y, z, origin, scale):
        return (origin[0] + (x - z) * scale * .70, origin[1] + (x + z) * scale * .35 - y * scale * .85)
    for tiles, label, origin, scale in examples:
        d.text((origin[0] - 120, 140), label, fill=PALETTE["ink"], font=font)
        polygons = []
        for tx, tz in tiles:
            attrs = {"facing": "south", "wall_mounted":"false", "color":"white", "base": str(tz == max(t[1] for t in tiles)).lower(), "up": str((tx, tz - 1) in tiles).lower(), "right": str((tx + 1, tz) in tiles).lower(), "down": str((tx, tz + 1) in tiles).lower(), "left": str((tx - 1, tz) in tiles).lower()}
            for part in state["multipart"]:
                if not all(attrs.get(k) == v for k, v in part.get("when", {}).items()):
                    continue
                spec = part["apply"]
                for e in geometry[spec["model"].split("/")[-1]]:
                    e = rotate_element(e, spec.get("y", 0))
                    x0, y0, z0 = e["from"]
                    x1, y1, z1 = e["to"]
                    x0 += tx * 16; x1 += tx * 16
                    y0 -= tz * 16; y1 -= tz * 16
                    for face, points, light in [
                        ("east", [(x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)], .74),
                        ("south", [(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)], .88),
                        ("up", [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)], 1.0),
                    ]:
                        if face not in e["faces"]:
                            continue
                        poly = [projection(*p, origin, scale) for p in points]
                        depths = [p[0] + p[2] + p[1] * (.70 / .85) for p in points]
                        polygons.append((depths, poly, texture_images[e["faces"][face]["texture"][1:]], light))
        rgb = np.array(image)
        zbuffer = np.full((height, width), -float("inf"))
        for depths, poly, texture, light in polygons:
            # Rasterize a real depth buffer so feet and below-top copper pieces
            # cannot bleed onto the top in this authoring reference.
            p0, p1, _, p3 = poly
            a, b = p1[0] - p0[0], p3[0] - p0[0]
            c, e = p1[1] - p0[1], p3[1] - p0[1]
            det = a * e - b * c
            if abs(det) < .001:
                continue
            xlo = max(0, math.floor(min(p[0] for p in poly)))
            xhi = min(width, math.ceil(max(p[0] for p in poly)))
            ylo = max(0, math.floor(min(p[1] for p in poly)))
            yhi = min(height, math.ceil(max(p[1] for p in poly)))
            yy, xx = np.mgrid[ylo:yhi, xlo:xhi]
            xx = xx + .5 - p0[0]
            yy = yy + .5 - p0[1]
            u = (e * xx - b * yy) / det
            v = (-c * xx + a * yy) / det
            depth = depths[0] + u * (depths[1] - depths[0]) + v * (depths[3] - depths[0])
            mask = (u >= 0) & (u <= 1) & (v >= 0) & (v <= 1) & (depth >= zbuffer[ylo:yhi, xlo:xhi])
            tex = np.array(texture)
            tu = np.minimum(texture.width - 1, np.maximum(0, (u * texture.width).astype(int)))
            tv = np.minimum(texture.height - 1, np.maximum(0, (v * texture.height).astype(int)))
            sampled = (tex[tv, tu] * light).astype(np.uint8)
            rgb[ylo:yhi, xlo:xhi][mask] = sampled[mask]
            zbuffer[ylo:yhi, xlo:xhi][mask] = depth[mask]
        image = Image.fromarray(rgb)
        d = ImageDraw.Draw(image)
    d.line((40, 490, 1400, 490), fill="#d7d1c4")
    d.text((40, 515), "MATERIALS / 32px, tiled paper fibers and dark wood, copper fasteners", fill=PALETTE["ink"], font=font)
    for i, (filename, caption) in enumerate([("canvas", "WHITEBOARD"), ("wood", "FRAME"), ("endgrain", "END GRAIN"), ("copper", "JOINTS"), ("circuit", "REDSTONE")]):
        path = ASSETS / f"textures/block/desk_{filename}.png"
        pic = Image.open(path).convert("RGB").resize((128, 128), Image.Resampling.NEAREST)
        x = 40 + i * 200
        image.paste(pic, (x, 558))
        d.text((x, 699), caption, fill=PALETTE["muted"], font=font)
    image.paste(Image.open(ASSETS / "icon.png").resize((128, 128), Image.Resampling.NEAREST), (1150, 558))
    d.text((1150, 699), "MOD ICON", fill=PALETTE["muted"], font=font)
    d.text((40, 755), "EDITOR / hand-drawn 16px sprites", fill=PALETTE["ink"], font=font)
    names = ["select", "pen", "text", "note", "checklist", "rectangle", "ellipse", "line", "arrow", "connector", "eraser", "pan", "undo", "redo", "fit", "share"]
    for i, name in enumerate(names):
        x = 40 + i * 85
        im = Image.open(ASSETS / f"textures/gui/sprites/tools/{name}.png").resize((48, 48), Image.Resampling.NEAREST)
        image.paste(im, (x, 805), im)
        d.text((x, 870), name, fill=PALETTE["muted"], font=font.font_variant(size=12) if hasattr(font, "font_variant") else font)
    d.text((40, 925), "Authoring preview generated from model JSON; runtime evidence belongs in VALIDATION_STATUS.md.", fill=PALETTE["muted"], font=font)
    ART.mkdir(parents=True, exist_ok=True)
    image.save(ART / "asset-reference.png")


def validate():
    """Static resource checks only; never starts Minecraft or a server."""
    from itertools import product
    checked=0
    for path in [*ASSETS.rglob("*.json"),*DATA.rglob("*.json")]:
        json.loads(path.read_text(encoding="utf-8"));checked+=1
    for path in ASSETS.rglob("*.png"):
        with Image.open(path) as image:image.verify()
    for path in (ASSETS/"textures/gui/sprites/tools").glob("*.png"):
        with Image.open(path) as image:assert image.size==(16,16) and image.mode=="RGBA",path
    for path in (ASSETS/"models").rglob("*.json"):
        model=json.loads(path.read_text(encoding="utf-8"))
        for texture in model.get("textures",{}).values():
            if texture.startswith("canvasdesk:"):assert (ASSETS/"textures"/(texture.split(":")[1]+".png")).is_file(),(path,texture)
        for box in model.get("elements",[]):
            assert all(a<b for a,b in zip(box["from"],box["to"])),path
            assert all(0<=v<=16 for v in box["from"]+box["to"]),path
    geometry=json.loads((ART/"geometry.json").read_text(encoding="utf-8"))
    assert geometry==json.loads((ASSETS/"geometry.json").read_text(encoding="utf-8"))
    states=0
    for block,mounts in [("desk",["false","true"]),("drawing_desk",["false"])]:
        multipart=json.loads((ASSETS/f"blockstates/{block}.json").read_text(encoding="utf-8"))["multipart"]
        for part in multipart:assert (ASSETS/"models"/(part["apply"]["model"].split(":")[1]+".json")).is_file()
        modes=["vertical","wall"] if block=="desk" else ["horizontal"]
        for mode in modes:
            for color in geometry["colors"]:
                base=json.loads((ASSETS/f"models/block/{block}_{mode}_base_{color}.json").read_text(encoding="utf-8"))
                assert set(base["elements"][0]["faces"])=={"up" if mode=="horizontal" else "north"},"Paper side faces must not overlap the frame"
        item=json.loads((ASSETS/f"items/{block}.json").read_text(encoding="utf-8"))["model"]
        assert item["type"]=="minecraft:select" and item["block_state_property"]=="color"
        assert {case["when"] for case in item["cases"]}==set(geometry["colors"])
        for facing,base,mount,color,mask in product(["north","east","south","west"],["false","true"],mounts,geometry["colors"],range(16)):
            attrs={name:str(bool(mask&(1<<bit))).lower() for bit,name in enumerate(["up","right","down","left"])}
            attrs.update(facing=facing,base=base,wall_mounted=mount,color=color)
            applied=[p for p in multipart if all(attrs[k]==v for k,v in p.get("when",{}).items())]
            assert sum("_base_" in p["apply"]["model"] for p in applied)==1,(block,attrs)
            assert sum("_edge_" in p["apply"]["model"] for p in applied)==4-mask.bit_count(),(block,attrs)
            states+=1
        loot=json.loads((DATA/f"canvasdesk/loot_table/blocks/{block}.json").read_text(encoding="utf-8"))
        modifiers=loot["pools"][0]["entries"][0]["modifier"]
        assert any(m["type"]=="minecraft:copy_components" and "minecraft:custom_data" in m["include"] for m in modifiers)
        assert any(m["type"]=="minecraft:copy_state" and m["properties"]==["color"] for m in modifiers)
        for color in geometry["colors"]:
            recipe=json.loads((DATA/f"canvasdesk/recipe/{block}_{color}.json").read_text(encoding="utf-8"))
            assert recipe["type"]=="minecraft:crafting_transmute" and recipe["result"]["components"]["minecraft:block_state"]["color"]==color
        target="drawing_desk" if block=="desk" else "desk"
        conversion=json.loads((DATA/f"canvasdesk/recipe/{block}_to_{target}.json").read_text(encoding="utf-8"))
        assert conversion["type"]=="minecraft:crafting_transmute" and conversion["result"]["id"]=="canvasdesk:"+target
    en=json.loads((ASSETS/"lang/en_us.json").read_text(encoding="utf-8"));pt=json.loads((ASSETS/"lang/pt_br.json").read_text(encoding="utf-8"))
    assert en.keys()==pt.keys()
    assert json.loads((DATA/"canvasdesk/recipe/desk.json").read_text(encoding="utf-8"))["pattern"]==["PPP","CRC","WWW"]
    assert json.loads((DATA/"canvasdesk/recipe/drawing_desk.json").read_text(encoding="utf-8"))["pattern"]==["PPP","WWW","CRC"]
    json_file(ART/"resource-validation.json",{"status":"PASS","version":"1.2.0","boundary":"Static resources only; excludes Minecraft runtime and actual crafting","jsonFiles":checked,"connectionStates":states,"colorsPerFormat":16,"checks":["JSON and PNG decoding","Model and texture references","Positive geometry within blocks","Shared editable geometry","Multipart format/mount/facing/color/connections","Item color selection","Data-preserving recipe definitions","Custom data and color loot","PT-BR/EN parity"]})
    print(f"PASS: {checked} JSON resources, {states} multipart configurations, two formats and 16 colors each.")


def main():
    json_file(ART / "palette.json", PALETTE)
    textures()
    models()
    tools()
    languages()
    data_files()
    icon()
    preview()
    manifest = {str(p.relative_to(ROOT)).replace("\\", "/"): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(ASSETS.rglob("*")) if p.is_file()}
    json_file(ART / "resource-sha256.json", manifest)
    import sys
    if "--validate" in sys.argv:
        validate()
    else:
        json_file(ART / "resource-validation.json", {"status": "NOT_RUN", "version": "1.2.0", "reason": "Generation only; automated tests explicitly waived."})


if __name__ == "__main__":
    main()
