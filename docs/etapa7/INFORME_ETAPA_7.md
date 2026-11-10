# Etapa 7 · Reconstrucción de menús y herramientas de PicSay Pro 1.8.0.5 y biblioteca externa de stickers

Versión **0.7.0** (versionCode 10). Copia de seguridad previa: rama `backup-0.6.0-before-stage7`.

## 1. Menús según las capturas reales de la versión 1.8.0.5

| Menú | Contenido y orden (como en las capturas) |
|---|---|
| Adjust | Exposure / Contrast, Saturation, Temperature / Tint, Boost, Sharpen, Smooth · **Transform Picture**: Crop and Straighten, Resize, Rotate and Flip · **Misc**: Curves, Adjust RGB, Brightness, Fix Red-Eye, Blur, Paint Mask / Selection, Add Background Color, History Paint, Picture Location (+ Free rotation y Restore original, que son de esta app) |
| Effect | Paint, Insert Picture, Pop Color · **Distort**: Warp Brush, Spherize, Twirl, Bulge & Pinch, Circle Splash, Stretch, Vertical Stretch, Horizontal Stretch, Mirror · **Color Adjustments**: Hue, Colorize, Grayscale, Black & White, Duotone, Rainbow, Blend with Color · **Artistic**: Comic Print, Posterize, Pop Art, Pencil Sketch, Neon · **Photo effects**: Lomo, Cross Process, Gritty, Fading Colors, Film Grain, Red Scale, Faux HDR, Sepia Toning, Invert, Tilt-shift, Soft Glow · **Filter & Frames**: Vignette, Focal Blur, Top Lens Filter, Edge Lens Filter, Spotlight, Instant, Film, Rounded Corners, Solid Border, Matte · **Misc**: Pixelize, X-ray, Dizziness, Heat Map |
| Sticker | Word Balloon, Title, las **32 categorías** en el orden del catálogo, My Stickers e «Import sticker library (ZIP)…» |
| Export | Save picture to album, Save as file…, Share picture via…, Set picture as… |

- Los menús se cierran con ✕ y las pantallas internas vuelven con ←.
- Rotate and Flip es una ventana emergente con 5 opciones, como en el original.
- Add Background Color muestra el aviso «Add background only works on pictures that have transparency» cuando la imagen no tiene transparencia.

## 2. Herramientas nuevas, todas funcionales (algoritmos propios)

| Herramienta | Funcionamiento | Prueba |
|---|---|---|
| Curves | Gráfico por canal (RGB, R, G, B). Tocar agrega un punto, arrastrar lo mueve y mantener pulsado lo quita. Usa una curva monótona | Unitaria (identidad, forma, monotonía, canal R invertido) |
| Fix Red-Eye | Un círculo se arrastra sobre el ojo, con radio ajustable; solo cambia los píxeles rojizos dentro del círculo | Unitaria |
| Add Background Color | Rellena la transparencia con un color | Unitaria |
| Pop Color | Conserva el color que está bajo el punto, con tolerancia ajustable. El resto pasa a gris, se invierte o se desenfoca (las opciones del original) | Unitaria |
| Warp Brush | Cada arrastre con un dedo empuja la imagen; con dos dedos se hace zoom | Unitaria |
| Insert Picture | Agrega una foto de la galería como objeto que se puede mover, escalar y rotar | Compila; **sin prueba en emulador** |
| Picture Location | Muestra la latitud y longitud del EXIF y permite abrirlas en una app de mapas | Compila; **sin prueba en emulador** |

## 3. Biblioteca externa de stickers

- Los **59 stickers de Bocadillo y los emojis Noto ya no aparecen en el selector**. Su código de dibujo se conserva para que los proyectos antiguos que los usan se sigan viendo.
- Las **32 categorías** están siempre visibles. Si una categoría está vacía, la fila muestra «Pending import» y, al abrirla, un mensaje con el botón para importar.
- **Importación de un ZIP** desde el almacenamiento. El catálogo se toma de la primera fuente que tenga datos, en este orden:
  1. `manifest.json`;
  2. `*.sqlite` / `*.db`, con una tabla `stickers` (acepta el esquema de `picsay_catalogo.sqlite`: `category_id`, `display_order`, `asset_path`);
  3. carpetas con una categoría cada una (`01_hats_helmets`, «Hats & Helmets», etc.).
- **Formatos aceptados:** PNG, WebP y SVG, con transparencia. Se comprueba el contenido real de cada archivo, no solo su extensión.
- **Reglas de importación:**
  - **duplicados:** se detectan por SHA-256 y se omiten;
  - **errores:** cada uno se informa (archivo inexistente, categoría desconocida, imagen inválida, más de 8 MB, ruta peligrosa);
  - **identificadores:** un id repetido actualiza el sticker existente;
  - **conservación:** nada de lo instalado se borra.
- **Almacenamiento:** `files/sticker_packs/index.json` y `files/sticker_packs/files/<sha256>.<ext>`.
- **Proyectos:** al insertar un sticker, su imagen se copia dentro del proyecto, así que el proyecto se puede compartir.
- Para borrar un sticker de la biblioteca, se mantiene pulsado en la cuadrícula.
- **Plantilla:** `tools/sticker_library/make_template.py` genera `Bocadillo_Plantilla_Biblioteca_Stickers.zip`. Contiene las 32 carpetas, un `manifest.json` vacío, un `manifest.example.json`, un `catalog.sqlite` con las 32 categorías y un `LEEME.md`.

## 4. Compilación y pruebas (resultado real)

- **Núcleo, en JVM:** 87 pruebas OK. Hay 6 nuevas de la biblioteca de stickers (categorías, formatos, manifiesto, carpetas, actualización sin pérdidas, rutas) y 5 de las herramientas nuevas.
- **APK:** compilada en GitHub Actions (`assembleDebug`, BUILD SUCCESSFUL).
- **Emulador Android 14 (Pixel 6)**, 2 pruebas instrumentadas que **pasan**:
  - `StickerLibraryFlowTest`:
    1. importa un ZIP de prueba (PNG + SVG + un duplicado + un archivo roto). Resultado: 2 agregados, 1 duplicado, 1 error;
    2. reimporta el mismo ZIP sin cambios;
    3. abre una categoría vacía (mensaje «pendiente») y la categoría Hats & Helmets;
    4. inserta un sticker, lo mueve, lo escala con pellizco, lo rota con dos dedos, lo duplica y elimina la copia;
    5. sale del editor y comprueba en el proyecto guardado que queda 1 sticker movido, escalado y rotado.
  - `ReferenceScreensTest`: recorre las pantallas principales y captura cada una.
- Las imágenes de prueba se dibujan dentro del propio test.

## 5. Pendiente (no se declara terminado)

- **Biblioteca gráfica:** las 32 categorías están vacías por diseño. Se llenarán importando imágenes con derechos de uso. **No se incorporaron stickers de PicSay Pro.**
- **Estilo de las pantallas de herramienta según las capturas:** zona de trabajo gris; barra superior con deshacer, nombre de la herramienta con menú ⋮, iconos de pincel/borrador/color y ✓ verde; slider inferior.
- **Diálogos de las capturas:** «Get Picture from» (Galería / Cámara / Import / New Blank Picture), «Import large picture» (Fast / Normal / Maximum), «New Picture» con casilla de color de fondo y «Set Size» con Scale / Expand Edges / Quick Fit.
- **Editores de las capturas:**
  - «New Word Balloon» y «New Title» con cuadrícula de estilos predefinidos;
  - Set Shape Style / Set Line Style;
  - Brush Settings: tamaño relativo a la pantalla y presión.
- **Exportar:** la lista de apps para compartir dentro del menú Export (hoy se usa el selector de Android).
- **Pruebas:** Insert Picture, Picture Location, Curves, Warp Brush y Pop Color no tienen prueba de interfaz en el emulador, solo pruebas unitarias del algoritmo.
- **Capturas del emulador:** en las capturas de esta ronda aparece un aviso del sistema «Pixel Launcher isn't responding». Viene del emulador, no de la app, y se desactivó para la siguiente ejecución.
