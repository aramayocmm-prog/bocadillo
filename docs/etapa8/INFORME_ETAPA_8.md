# Etapa 8 · Composición clásica (comparada con las 144 capturas)

Versión **0.8.0** (versionCode 11). Copia de seguridad previa: rama `backup-0.7.0-before-stage8`.

## Método

1. Medí las capturas de 747×1600: colores con un cuentagotas por píxel y alturas por cambios de color.
2. Lo traduje a dp para el emulador (Pixel 6, 411 dp de ancho).
3. Cada cambio se compiló en GitHub, se abrió en un emulador Android 14 y se capturó.
4. `tools/visual/compare_pairs.py` pone la captura original y la de Bocadillo lado a lado y calcula el SSIM.

Hubo dos rondas de corrección y medición en esta etapa.

**Valores medidos:**

| Elemento | Valor |
|---|---|
| Barra superior | 54 dp, #1E1E1E, título centrado de 21 sp, ✕ en menús, ‹ y ✓ verde en las herramientas de sticker |
| Listas | fondo #111111, filas de 58 dp, texto de 21 sp, separador #1A1A1A |
| Cabeceras de grupo | 22 dp, #232323, texto gris de 12 sp en negrita |
| Inicio | fondo #333333, botón #CFCFCF |
| Herramientas | zona de trabajo #444444, barra superior #252525, barra del deslizador #262626 |
| Title / Word Balloon / Font | vista previa #CFCFCF, cuadrícula #DFDFDF, campo con borde #7B9EE0, «More Fonts» #A9D3EB |
| Cuadrícula de stickers | #EEEEEE |

## Pantallas corregidas

| Pantalla | Antes (0.7.0) | Ahora (0.8.0) |
|---|---|---|
| Inicio | 4 botones | ≡ arriba a la izquierda, última foto arriba a la derecha, logo propio y «Get a Picture» |
| Get Picture from | — | Diálogo blanco con título azul: Gallery, Camera, Import…, New blank picture |
| Adjust / Effect | Filas de 55 dp con texto pequeño | Medidas anteriores. Las miniaturas son recortes verticales de la **foto que se edita** |
| Export | Lista fija | Nombre del archivo arriba, Save picture to album, Save as file…, Set picture as… y **las apps instaladas que aceptan imágenes**, con su icono y por orden alfabético; se envía directo a la app elegida |
| Word Balloon (formas) | 6 formas en celdas oscuras | **8 formas** (se añadieron Caption… y Pointer), en 2 columnas, globo blanco con el nombre dentro, sobre degradado gris |
| New Word Balloon | Panel sobre la foto | Pantalla clara con vista previa (tocarla abre todas las opciones), ▶ cambia de forma, campo con + (frases) y cuadrícula de 25 estilos de color propios |
| New Title | Panel sobre la foto | Pantalla clara con «Tap to style Title», campo con + y cuadrícula de 25 estilos propios |
| Font | Lista oscura | Lista blanca con cabeceras grises, nombres dibujados en su fuente, fila elegida resaltada y «More Fonts ›» (importar) |
| Herramientas de ajuste y efecto | Panel con todos los controles | Fondo gris #444444. Arriba: ↶, «⋮ Nombre valor» (el menú elige el parámetro y da acceso a todas las opciones), cuadro de comparar (mantener pulsado) y ✕ si no hay cambio o ✓ verde si lo hay. Abajo: − deslizador + |
| Categorías de stickers | Celdas oscuras | Hoja gris clara, 4 columnas, sin marcos |

## SSIM medido (escala de grises, pantalla completa sin barra de navegación)

| Pantalla | 0.7.0 → 0.8.0 (1.ª ronda) | 0.8.0 final |
|---|---|---|
| Inicio | — | 0,76 |
| Get Picture from | — | 0,67 |
| Adjust | — | 0,67 |
| Effect | — | 0,66 |
| Effect · Color | — | 0,65 |
| Export | — | 0,58 |
| Formas de globo | — | 0,60 |
| New Word Balloon | — | 0,55 |
| New Title | — | 0,49 |
| Font | — | 0,42 |
| Exposure / Contrast | 0,24 | 0,44 |
| Twirl | 0,34 | 0,58 |
| Crop | 0,22 | 0,44 |
| Resize | 0,18 | 0,33 |
| Paint | 0,29 | 0,36 |

El SSIM no puede llegar cerca de 1, y **no se declara coincidencia**, por estas diferencias de contenido:
- otra foto: la del usuario frente a un paisaje de prueba;
- logo propio, sin el de PicSay;
- otras fuentes;
- barra de estado de otro teléfono.

## Diferencias que todavía existen

1. **Tamaño relativo:** el texto y los iconos siguen algo más pequeños que en las capturas. El emulador tiene 411 dp de ancho; conviene revisarlo en el Pixel 10 Pro.
2. **Crop:** la barra superior original es «↶ Crop 1536 × 2048 [□] ✕», con asas en las esquinas y un deslizador abajo. Bocadillo mantiene su panel de proporciones.
3. **Resize:** el original muestra «Set Size w × h», «Keep Proportions» y las opciones Scale…, Expand Edges… y Quick Fit en un diálogo propio. Bocadillo usa un diálogo distinto.
4. **Paint:** el original tiene una barra superior con cuadro de color, pincel y borrador, y un menú «Brush Settings». Bocadillo mantiene su panel.
5. **Import large picture:** falta el diálogo «Fast / Normal / Maximum».
6. **Set Shape Style / Set Line Style:** no se hicieron. Solo tienen sentido con stickers vectoriales, y la biblioteca ahora es externa (PNG/WebP/SVG).
7. **Detalle de New Word Balloon:** el original rellena el globo con degradados; los estilos actuales usan color sólido.
8. **Menú ≡ del inicio:** abre la lista More…. No hay captura del original para compararlo.

## Sin cambios (conservado)

Se mantienen todas las herramientas, deshacer/rehacer, máscaras, importación externa de stickers (32 categorías vacías hasta importar), Title y Word Balloon con todas sus opciones, guardar y exportar.

No se usó ningún gráfico, icono, logo, fuente ni sticker del APK o de las capturas: solo sirvieron como referencia de medidas y disposición.

## Compilación y pruebas

- **Núcleo:** 88 pruebas OK. Hay 1 nueva (formas Caption y Pointer).
- **APK de depuración:** compilada en GitHub Actions (BUILD SUCCESSFUL) a partir del commit 1e13a98.
- **Emulador Android 14:** 2 pruebas instrumentadas OK.
  - **Recorrido de pantallas:** capturas de inicio, Get Picture, More, editor, Adjust, Exposure, Effect, Twirl, Sticker, formas de globo, New Word Balloon con su estilo, New Title, lista de fuentes, estilos de texto, Paint, Export, Crop, rotación libre, Resize y Color.
  - **Biblioteca de stickers:** importar, insertar, mover, escalar, rotar, duplicar, eliminar y comprobar el proyecto guardado.
