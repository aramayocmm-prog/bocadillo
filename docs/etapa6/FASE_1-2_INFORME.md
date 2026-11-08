# Etapa 6 · Prioridades 1 y 2: editor, barras y menús

Versión 0.6.0 (versionCode 9). Punto de control anterior: rama `checkpoint-0.5.1`.

## Cambios

| Pantalla | Antes | Ahora |
|---|---|---|
| Tema | Material You con colores dinámicos, claro u oscuro | Tema clásico oscuro fijo: menús negros, barras gris oscuro, texto blanco, acento azul `#2196F3`. Colores centralizados en `ui/theme/Theme.kt` (`Classic`) |
| Componentes | — | `ui/classic/`: barra superior (← título ✓), barra inferior, fila de menú con separador, cabecera de sección, slider azul con − / + y valor, botón oscuro con borde gris, iconos dibujados por la app |
| 1 · Inicio | Lista de proyectos | Logo propio (globo «Bocadillo»), 4 botones: Edit a Photo, Get a Photo (cámara), Recent Photos, More… |
| 2 · Editor | 9 menús abajo, barra Material | 4 botones (Adjust, Effect, Sticker, Export). Arriba: ←, título, deshacer, rehacer, ✓ |
| 3 · Adjust | Fila de miniaturas | Lista con iconos y las secciones Transform Picture y Misc del original |
| 5 / 17 · Effect | Chips por familia | Lista con miniatura a la izquierda y secciones: Effect (pincel, marcador, borrador), Distort, Color, Artistic, Photo effects, Filter & Frames, Misc |
| 7 · Sticker | Panel con pestañas | Lista: Word Balloon, Title, categorías propias, grupos Noto, Mis stickers |
| 8 · Globos | Fila horizontal | Cuadrícula de 2 columnas en celdas gris oscuro (las 6 formas reales) |
| 13 · Export | Hoja con 3 botones | Lista: Save picture to album, Save as file…, Share picture via…, **Set picture as…** (nuevo: fondo de pantalla, contacto…) |
| 18 · More… | No existía | New blank picture, **Open project…**, **Save project…** (nuevos: importar y exportar `.bcproj`), Recent projects, **Settings** (nueva), Licencias |
| Settings | No existía | Ocultar la barra de estado al editar, restablecer paneles, asunto y firma al compartir |
| Herramientas | Cabecera propia en cada panel | ← / ✓ en la barra superior. Sliders azules con − / + y valor con signo (+0,50) |

## Errores encontrados por las capturas y corregidos

- **La barra superior del editor quedaba oculta bajo la barra de estado** (modo edge-to-edge). Ahora se coloca debajo.
- Al duplicar un proyecto se perdían las fuentes y los stickers importados. Ahora se copian.

## Validación visual (medida, no estimada)

**Método**
- GitHub Actions ejecuta un emulador Android 14 (Pixel 6, 1080×2400) y el test `ReferenceScreensTest` abre las 18 pantallas y captura la pantalla real.
- Las capturas se publican en la rama `screens-output`.
- `tools/visual/compare.py` recorta cada panel de la lámina y la compara con la captura:
  - fila 1 (pantallas completas): la captura entera se escala al panel;
  - filas 2 y 3 (pantallas recortadas): se recorta la parte superior con la misma proporción.
- SSIM en gris (ventana gaussiana σ = 1,5).

| Pantalla | SSIM total | Barra superior | Resto |
|---|---|---|---|
| 01 Inicio | 0,09 | 0,24 | 0,07 |
| 02 Editor | 0,13 | 0,40 | 0,09 |
| 03 Adjust | 0,31 | 0,48 | 0,28 |
| 04 Exposure | 0,21 | 0,34 | 0,19 |
| 05 Effect | 0,27 | 0,47 | 0,24 |
| 06 Twirl | 0,26 | 0,41 | 0,24 |
| 07 Sticker | 0,27 | 0,24 | 0,28 |
| 08 Globos | 0,31 | 0,20 | 0,32 |
| 09 Edición de globo | 0,22 | 0,20 | 0,22 |
| 10 Título | 0,13 | 0,24 | 0,11 |
| 11 Estilos de texto | 0,20 | 0,27 | 0,19 |
| 12 Paint | 0,14 | 0,27 | 0,12 |
| 13 Export | 0,46 | 0,25 | 0,49 |
| 14 Crop | 0,12 | 0,20 | 0,11 |
| 15 Rotate | 0,16 | 0,20 | 0,15 |
| 16 Resize | 0,17 | 0,21 | 0,16 |
| 17 Filtros de color | 0,31 | 0,23 | 0,33 |
| 18 More… | 0,39 | 0,23 | 0,42 |

**Lectura honesta:** ninguna pantalla se acerca al 95 % y no se puede declarar ninguna como coincidente. Una parte de la diferencia no se puede corregir:
- la foto (árbol frente a nuestro paisaje propio);
- el logo y el nombre PicSay;
- las fuentes;
- la proporción de pantalla: la lámina usa unos 9:16 y el emulador 20:9, así que las filas ocupan una fracción distinta de la altura.

Otra parte sí es diseño pendiente y se ve en `fase1-2_side_by_side.png`.

## Diferencias pendientes (siguientes prioridades)

- **Escala:** en la lámina, filas, títulos e iconos son más grandes respecto a la pantalla. Hay que subir la fila a unos 60 dp, el texto a 20 sp y la barra a 56 dp.
- **Adjust:** la lámina separa Exposure y Contrast. Por tu decisión se mantienen juntos, como en el original.
- **Pantallas 4 y 6:** la lámina pone el valor encima del slider y no muestra la etiqueta en Exposure. En la app aún quedan «Reset» y el botón de comparar.
- **Pantallas 9 a 12, 14 a 16:** las disposiciones propias de la lámina siguen pendientes (prioridades 4 y 5):
  - barra de 4 botones del globo;
  - lista de fuentes con resaltado azul;
  - paleta arcoíris;
  - panel de Paint con cuadro de color, pincel y borrador;
  - asas circulares y barra de proporciones de Crop;
  - pantalla Rotate con botones ↺ ↻;
  - diálogo Resize oscuro.
- **Paneles minimizados:** en Paint y Crop, el panel arranca minimizado (lo pediste antes para tener más espacio de trabajo), mientras que la lámina lo muestra abierto.
- **Captura 17:** el test no llegó a desplazarse hasta la sección Color. Es un fallo del test, no de la app.
- **Stickers:** la lista usa nuestras categorías. Las nuevas (sombreros, peinados, ojos, máscaras, gafas, bigotes) siguen pendientes.

## Funciones probadas en el emulador

En el emulador se recorrieron, sin errores, la navegación por las 18 pantallas y estas aperturas:
- herramienta de ajuste, Twirl, globo nuevo, título nuevo, pincel, recorte, rotación libre y diálogo de tamaño;
- los menús Export y More….

**No se probó en el emulador:**
- guardar, compartir, «Set picture as»;
- abrir y guardar un proyecto;
- Settings.

Esas funciones compilan, pero hay que probarlas en el teléfono.
