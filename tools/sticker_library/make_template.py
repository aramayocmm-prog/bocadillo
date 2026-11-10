#!/usr/bin/env python3
"""Builds the empty sticker-library template ZIP (32 classic categories, manifest, SQLite schema).

The template contains no pictures: the user adds his own PNG/WebP/SVG files (with the rights to
use them) and imports the ZIP from the app's Sticker menu.
"""
import io, json, sqlite3, sys, tempfile, zipfile, os

CATS = [
 ("hats_helmets","Hats & Helmets"),("hairstyles","Hairstyles"),("eyes","Eyes"),("masks_glasses","Masks & Glasses"),
 ("beards_moustaches","Beards & Moustaches"),("ears_nose_mouth","Ears, Nose & Mouth"),("masks_pointers","Masks & Pointers"),
 ("emoticons","Emoticons"),("celebrations","Celebrations"),("hands","Hands"),("nature","Nature"),("animals","Animals"),
 ("prints_stamps","Prints & Stamps"),("romance","Romance"),("signs_symbols","Signs & Symbols"),("comic","Comic"),
 ("food_drink","Food & Drink"),("accessories","Accessories"),("sport","Sport"),("basic_shapes","Basic Shapes"),
 ("summer","Summer"),("halloween","Halloween"),("thanksgiving","Thanksgiving"),("christmas_holiday","Christmas Holiday"),
 ("happy_new_year","Happy New Year"),("easter_spring","Easter & Spring"),("st_patricks_day","St. Patrick's Day"),
 ("independence_day","Independence Day"),("travel_places","Travel & Places"),("objects","Objects"),("people","People"),("activity","Activity"),
]

README = """# Plantilla de biblioteca de stickers de Bocadillo

1. Copia tus imágenes (PNG, WebP o SVG, con transparencia) en la carpeta de su categoría.
   Usa solo imágenes que tengas derecho a usar.
2. Elige UNA forma de describirlas:
   a) Sin catálogo: basta con las carpetas. El orden es el del nombre de archivo (1, 2, 10…)
      y el identificador es «categoría/nombre-de-archivo».
   b) manifest.json: copia el formato de manifest.example.json (id, category, name, position, file).
      "category" admite el id (hats_helmets), el número del menú (1-32) o el nombre (Hats & Helmets).
   c) catalog.sqlite: tabla "stickers" (id, category_id, display_order, name, asset_path) y tabla
      "categories" (id, name, display_order), ya creadas con las 32 categorías.
   Si hay manifest.json se usa ese; si no, catalog.sqlite; si no, las carpetas.
3. Comprime TODO en un ZIP y en Bocadillo abre Sticker › «Importar biblioteca de stickers (ZIP)…».

Al volver a importar: los stickers con el mismo id se actualizan, los nuevos se agregan, los que ya
estaban no se borran, las imágenes repetidas se omiten y cada error se informa.
"""

def build(out_path):
    manifest = {"format": "bocadillo-sticker-library", "version": 1, "library": "Mi biblioteca", "stickers": []}
    example = {"format": "bocadillo-sticker-library", "version": 1, "library": "Ejemplo",
               "stickers": [
                   {"id": "hat_001", "category": "hats_helmets", "name": "Sombrero 1", "position": 1, "file": "01_hats_helmets/hat_001.png"},
                   {"id": "hat_002", "category": 1, "name": "Casco", "position": 2, "file": "01_hats_helmets/hat_002.webp"},
                   {"id": "shape_star", "category": "Basic Shapes", "name": "Estrella", "position": 1, "file": "20_basic_shapes/star.svg"}]}
    with tempfile.TemporaryDirectory() as tmp:
        db = os.path.join(tmp, "catalog.sqlite")
        c = sqlite3.connect(db)
        c.executescript("""
            CREATE TABLE categories (id INTEGER PRIMARY KEY, name TEXT NOT NULL, display_order INTEGER NOT NULL);
            CREATE TABLE stickers (id TEXT PRIMARY KEY, category_id INTEGER NOT NULL REFERENCES categories(id),
                                   display_order INTEGER, name TEXT, asset_path TEXT NOT NULL);""")
        c.executemany("INSERT INTO categories VALUES (?,?,?)", [(i + 1, n, i + 1) for i, (_, n) in enumerate(CATS)])
        c.commit(); c.close()
        dbbytes = open(db, "rb").read()
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("LEEME.md", README)
        z.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        z.writestr("manifest.example.json", json.dumps(example, ensure_ascii=False, indent=2))
        z.writestr("catalog.sqlite", dbbytes)
        z.writestr("categorias.csv", "posicion,id,nombre\n" + "".join(f'{i+1},{cid},"{n}"\n' for i, (cid, n) in enumerate(CATS)))
        for i, (cid, n) in enumerate(CATS):
            z.writestr(f"{i+1:02d}_{cid}/_PENDIENTE.txt", f"{n}: copia aquí los stickers de esta categoría (PNG, WebP o SVG).\n")

if __name__ == "__main__":
    build(sys.argv[1] if len(sys.argv) > 1 else "Bocadillo_Plantilla_Biblioteca_Stickers.zip")
