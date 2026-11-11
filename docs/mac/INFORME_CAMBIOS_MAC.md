# MAC Editor para MEMES 0.9.0 — Informe técnico de cambios

Base: Bocadillo 0.8.0, commit `edfab51`. Antes de modificar nada se creó la rama de respaldo
`backup-0.8.0-before-MAC-redesign`. El código fuente del paquete coincidía con el repositorio,
salvo `app/debug.keystore`, que el ZIP no incluye.

Datos de la versión:
- versión 0.9.0, versionCode 12;
- paquete `app.bocadillo` (la depuración usa `app.bocadillo.debug`);
- Android 10–15.

## 1. Cómo se verificó

| Prueba | Dónde | Resultado |
|---|---|---|
| Núcleo (modelo, efectos y geometría) | JVM, `tools/build-core-offline.sh` | **92 pruebas OK**, 5 nuevas: sesgado, perspectiva, degradados, segundo contorno, estilo vectorial y lectura de proyectos 0.8 |
| Compilación de la APK | GitHub Actions, `assembleDebug` | **BUILD SUCCESSFUL** (commit indicado en el apartado 6) |
| Pruebas instrumentadas | Emulador Android 14 (perfil Pixel 6, 411 dp de ancho), `connectedDebugAndroidTest` | Ver el apartado 6 |
| Comparación visual | `tools/visual/compare_pairs.py`: original y captura del emulador lado a lado, más SSIM orientativo | Hoja `Comparacion_PicSay_vs_MAC_Editor_0.9.0.jpg` |

El SSIM se usa solo como apoyo. Las capturas originales tienen otra foto, otro teléfono y otra
barra de estado. La fidelidad se revisó a ojo en cada pareja: ubicación, proporción, jerarquía y
controles.

**No probado:**
- en un Pixel 10 Pro real;
- con la escala de fuente del sistema aumentada;
- en horizontal;
- la actualización encima de la 0.8.0 instalada (ver el apartado 4).

## 2. Matriz de requisitos

| ID | Archivos principales | Comportamiento | Captura (después) | Prueba | Estado |
|---|---|---|---|---|---|
| 9 Identidad | `res/values*/strings.xml` (`app_name`), `mipmap-*/ic_launcher*`, `mipmap-anydpi-v26/*`, `drawable-nodpi/logo_mac_*`, `values-v31/themes.xml`, `ExportRepository.kt`, `HomeScreen.kt` (AppLogo) | Nombre visible «MAC Editor para MEMES» en el lanzador, el inicio, el informe de fallos y la carpeta de la galería. El icono adaptativo y el redondo se generaron en 5 densidades: el logotipo se reduce de tamaño, sin deformar ni recortar, sobre fondo negro. La pantalla de arranque de Android 12+ usa el logotipo sobre negro. En el inicio, la variante circular tiene transparente el negro de fuera del aro, de modo que se lee sobre fondo oscuro o claro | 30_home_mac | `homeTitleAndPaint`: comprueba el nombre y el logotipo | **Hecho**. El `applicationId` no cambia (apartado 4) |
| 10 Inicio | `HomeScreen.kt` (`GlassOpenButton`) | Botón **ABRIR IMAGEN**: cristal oscuro con degradado azul, violeta y magenta, borde luminoso, icono de imágenes y chevrón. Texto en una línea (se reduce si la fuente del sistema es muy grande). Altura mínima de 56 dp, sombra suave y animación al pulsar que se desactiva si el sistema tiene las animaciones apagadas. Abre el mismo diálogo «Get Picture from». Se mantienen ≡ y la miniatura | 30_home_mac, 01b_get_picture | La prueba verifica una sola línea (altura menor de 60 dp) y que abre el selector | **Hecho** |
| 11 Pincel | `PaintChrome.kt` (nuevo), `EditorScreen.kt`, `EditorViewModel.kt` (`relativeToScreen`, `addStroke(zoom)`), `AppSettings.kt` (paleta) | Barra clásica: ↶ ↷, cápsula «Paint ■ pincel borrador» y ✕ (✓ verde cuando hay trazos). Los paneles bajan desde arriba sobre la foto y su contenido se desplaza:<br>• **Brush Settings**: tamaño relativo a la pantalla, presión → intensidad, presión → tamaño, modos y opacidad.<br>• **Brush**: puntas suaves y duras, Tamaño y Dureza con valor, − y +, y vista previa circular.<br>• **Color**: **pipeta solo icono** junto al color activo, Tono, Saturación, Brillo y Opacidad, y paleta en carrusel horizontal con «+» que se guarda.<br>Al tocar la foto con la pipeta se toma el color y vuelve el panel. Atrás pliega el panel y, si hay trazos, pide confirmación antes de descartar | 12_paint, 12b, 12c, 12d, 33_eyedropper, 34_color_after_pick, 35_painted | La prueba verifica: la pipeta sin texto, el regreso tras tomar el color y que el trazo aplicado queda guardado (operación `paint`) | **Hecho** |
| 12 Insertar imagen | `Objects.kt` (`skewX`, `skewY`, `perspective`), `ObjectJson.kt`, `ObjectGeometry.kt` (`corners`, `dragSkew`, `dragCorner`, `hitQuad`), `ObjectRenderer.kt` (`setPolyToPoly`), `ObjectLayer.kt`, `TransformChrome.kt` (nuevo), `EditorViewModel.kt` | Tocar el punto azul (sin arrastrar) abre el menú **Transformar** con seis modos (ver la lista debajo de la tabla). Con un modo activo aparece una barra con el nombre y la ayuda, ✕ para volver al objeto como estaba y ✓ para terminar. Cada arrastre es un paso de Deshacer. «Restablecer» quita escala, giro, estiramiento, sesgado y perspectiva. La transparencia se conserva. La exportación usa el mismo dibujo. Los proyectos 0.8 se leen sin cambios: los campos nuevos solo se escriben si se usan | 36_transform_menu, 37_skew, 38_perspective, 39_reopened, 40_exported | `insertedPictureSkewPerspectiveSaveReopenExport`: sesga, aplica perspectiva, guarda, comprueba en disco, reabre y exporta a la galería. Prueba del núcleo de la lectura de JSON 0.8 | **Hecho**. Correspondencia con el original: ver la nota 1 |
| 13 Title | `NewStickerScreens.kt` (`NewTitleScreen`, `TITLE_STYLES`, `TitlePreview`), `TitleStyleScreens.kt` (nuevo), `EditorScreen.kt`, `ObjectRenderer.kt` (degradado y segundo contorno) | Composición clásica: vista previa clara «Tap to style Title», campo blanco con borde azul y «+» de frases, y **cuadrícula ABC con estilos reales** de **5 filas que se desplaza hacia los lados** (lo que muestran WA0013/14/21/25: entre capturas las columnas aparecen desplazadas). Hay 61 estilos de combinación propia. Tocar la vista previa abre «Title Style»: Fuente › (pantalla propia, cada fuente en su tipo, la elegida resaltada y «More Fonts ›» para importar), color, degradado, contorno, segundo contorno, sombra, fondo, tamaño, alineación, interlineado, espaciado, curva, opacidad y fecha y hora. Reeditar un título abre la misma composición («Edit Title») | 10_new_title, 31_new_title_typed, 11_title_style, 10b_font_list, 32_edit_title | La prueba escribe el texto, elige el estilo 7, inserta, reedita y cambia el texto, y comprueba el texto y el estilo guardados | **Hecho** |

Modos del menú **Transformar** (cambio 12):
- Escalar y rotar;
- Escalar (solo proporcional);
- Rotar;
- Estirar (ancho y alto por separado);
- **Sesgar**: afín, los lados paralelos siguen paralelos;
- **Perspectiva**: cuatro esquinas independientes, con un asa en cada una.

## 3. Los ocho pendientes de la etapa 8

| # | Archivos | Qué se hizo | Captura | Prueba | Estado |
|---|---|---|---|---|---|
| 1 Tamaño relativo | `ClassicComponents.kt` (`ClassicDims`) | Filas de 62 dp con texto de 22 sp, barra superior de 56 dp, cabeceras de 24 dp, iconos de 35 dp y miniaturas de 28×44 dp: alrededor de un 8 % más que la 0.8. Las filas, cabeceras y la barra superior pasan a altura mínima: crecen con la fuente del sistema en vez de recortar | 03_adjust, 05_effect | Solo emulador (411 dp) | **Parcial**: sin probar en el Pixel 10 Pro ni con la fuente del sistema grande |
| 2 Crop | `ClassicToolChrome.kt` (`CropTopBar`, `CropSliderBar`), `EditorCanvas.kt`, `EditorViewModel.kt` (`resetCrop`) | Barra «↶ \| Recortar ancho × alto \| ⛶ \| ✕/✓», como en WA0117. El tamaño en píxeles se actualiza en directo. ⛶ abre las proporciones (Libre, Original, Áurea, Cuadrado, Fondo de pantalla, Personalizada). Las asas son escuadras gris claro en las esquinas. Abajo, − deslizador + para enderezar. El antiguo panel de chips ya no se muestra | 14_crop | Recorrido de pantallas | **Hecho**. El deslizador del original no se puede identificar en la captura; aquí endereza (nota 2) |
| 3 Resize | `ClassicDialogs.kt` (`SetSizeDialog`) | «Set Size ancho × alto», «Keep Proportions», texto de ayuda y la lista clara Scale… / Expand Edges… / Quick Fit, como en WA0112:<br>• Scale… redimensiona al tamaño escrito;<br>• Expand Edges… pide el color del borde y amplía el lienzo;<br>• Quick Fit encaja la foto dentro del tamaño sin deformarla;<br>• «Todas las opciones…» abre el diálogo anterior (modos rellenar y recortar) | 16_resize | Recorrido de pantallas | **Hecho**. Las operaciones de redimensionado ya estaban probadas en el núcleo |
| 4 Paint | ver el cambio 11 | — | 12_* | — | **Hecho** |
| 5 Import large picture | `HomeViewModel.kt` (`LargeImport`, `chooseImportSize`), `ImageIo.kt` (`fitPixels`), `HomeScreen.kt` (`LargeImportDialog`), `MainActivity.kt` | Si la foto supera 3,1 MP aparece «Import large picture…» con Fast (≈0,8 MP), Normal (≈3,1 MP) y Maximum (la foto completa, solo limitada por la memoria del aparato). Cada opción muestra el tamaño exacto resultante. El archivo original se guarda completo en el proyecto en todos los casos; la opción fija la resolución de trabajo y de exportación. Si la memoria limita Maximum, el diálogo lo dice. Nada se reduce sin que el usuario lo elija | 41_import_large | `largePictureAsksForSize`: con una foto de 4000×3000 aparecen 1024×768 y 2048×1536, se elige Fast y el proyecto queda en 1024×768 | **Hecho** |
| 6 Set Shape Style / Set Line Style | `Objects.kt` (`VectorStyle`, `isRestylable`), `ObjectJson.kt`, `ObjectRenderer.kt` (CSS sobre SVG y relleno y trazo en vectores integrados), `VectorStyleScreens.kt` (nuevo), `ObjectPanels.kt`, `EditorScreen.kt` | Solo aparecen para stickers **vectoriales**: SVG importados de la biblioteca y stickers vectoriales de proyectos antiguos. Nunca aparecen para PNG o WebP. Las dos pantallas siguen las capturas WA0096/99/106 y WA0100:<br>• **Set Shape Style**: cuadrícula de 4 filas desplazable con relleno y contorno;<br>• **Set Line Style**: 2 columnas, extremos redondeados o cuadrados, 8 grosores y fila de colores.<br>El archivo SVG no se modifica: el estilo se aplica al dibujar | 42_set_shape_style, 43_set_line_style | `svgShapeAndLineStyle` | Ver el apartado 6. Limitación: un SVG con estilos `style="…"` en línea puede conservar sus colores |
| 7 Degradados en New Word Balloon | `Objects.kt` (`fillColor2`, `shadowColor`), `ObjectRenderer.kt`, `NewStickerScreens.kt`, `TitleStyleScreens.kt` (`BalloonStyleScreen`) | 42 estilos propios: relleno liso o con degradado vertical, contorno de color o grueso, globos oscuros y sombra. La cuadrícula tiene **4 filas y se desplaza hacia los lados**, como WA0011/12/15. «Balloon Style» (al tocar la vista previa) incluye el interruptor de degradado y su color, la sombra y Fuente ›. Reeditar un globo abre la misma composición | 09_new_word_balloon, 09b_balloon_style | Recorrido de pantallas y prueba del núcleo de lectura y escritura | **Hecho** |
| 8 Menú ≡ / More | `HomeScreen.kt` | Se revisó: los elementos llevan a funciones reales (imagen en blanco, abrir proyecto, guardar proyecto, recientes, ajustes y licencias). Atrás vuelve siempre al inicio. **No hay captura del original** de este menú, así que no se afirma ninguna equivalencia | 18_more | Recorrido de pantallas | **Revisado**, sin referencia original |

## 4. Proyectos, actualización y firma

- El `applicationId` no cambia: `app.bocadillo`, y `app.bocadillo.debug` en la APK de prueba.
  Por eso la 0.9.0 se instala **encima** de la 0.8.0 y conserva:
  - los proyectos (`files/projects`);
  - la biblioteca de stickers (`files/sticker_packs`);
  - los ajustes.
- La APK de GitHub se firma con la misma clave de depuración del repositorio
  (`app/debug.keystore`) que la 0.8.0, así que Android acepta la actualización.
  **No se probó** instalar una versión encima de la otra.
- El ZIP de código no incluye esa clave (archivo de firma, por petición tuya). Si compilas desde el
  ZIP, Android Studio usará tu clave de depuración local. Esa APK no podrá instalarse encima de la
  de GitHub sin desinstalar antes, y desinstalar borra los proyectos: expórtalos primero desde
  ≡ › Guardar proyecto.
- Formato de proyecto: los campos nuevos (sesgado, perspectiva, degradados, segundo contorno y
  estilo vectorial) son opcionales. Una prueba del núcleo confirma que un proyecto 0.8 se abre
  igual y que, si no se usan, el JSON no cambia.
- Lo exportado se guarda ahora en `Imágenes/MAC Editor para MEMES`. Las exportaciones anteriores
  siguen en `Imágenes/Bocadillo`.

## 5. Notas de fidelidad (sin inventar comportamiento)

1. **Modos de transformación.** Las 144 capturas no muestran el menú del punto azul.
   - «Escalar y rotar» y «Estirar» ya existían en Bocadillo, tomados del menú Transform del
     original.
   - **Escalar, Rotar, Sesgar y Perspectiva son mejoras nuevas**: no se ha verificado que existan
     en PicSay Pro con esos nombres ni en esa cantidad.
2. **Crop.** En WA0117 se ve un deslizador centrado bajo la foto, pero la captura no dice qué
   controla. En MAC Editor endereza la imagen (−45° a 45°).
3. **Estilos.** Los estilos de Title, Word Balloon, Set Shape Style y Set Line Style son
   combinaciones propias de fuente, color, degradado y contorno, hechas con fuentes del sistema o
   incluidas con licencia libre. Siguen la organización de las capturas (filas, desplazamiento y
   proporción), no sus diseños concretos.
4. No se usó ningún gráfico, icono, fuente, sticker ni recorte de las capturas o del APK. Las
   únicas imágenes nuevas son el logotipo de MAC PRODUCCIONES que tú suministraste.

## 6. Pruebas instrumentadas ejecutadas

RESULTADOS_INSTRUMENTADAS

## 7. Pendiente o no verificado

- Prueba en el Pixel 10 Pro real, con la fuente del sistema grande y en horizontal. Solo se probó
  en el emulador de 411 dp.
- Instalar encima de la 0.8.0 y comprobar que siguen los proyectos. La conservación está
  garantizada por diseño (mismo paquete y misma firma), pero no se ejecutó.
- Las fuentes del original (Daniel, Vinque, Anton, etc.) no están incluidas por derechos. La
  lista de fuentes es la de MAC Editor y «More Fonts ›» permite importar TTF/OTF propias.
- Las 32 categorías de stickers siguen vacías hasta importar una biblioteca autorizada.
