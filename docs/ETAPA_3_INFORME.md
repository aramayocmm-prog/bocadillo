# Informe de etapa 3 — Textos, globos de diálogo y stickers

**Fecha:** 2026-11-06 · **Versión:** 0.3.0-stage3

## 1. Estado por función

Leyenda:
- ✅ probado (pruebas automáticas en JVM o revisión visual)
- 🟡 implementado y compilado, pendiente de probar en dispositivo
- ⏳ pendiente

### Núcleo (Kotlin puro)
| Función | Estado | Pruebas |
|---|---|---|
| Objetos editables: texto, globo, sticker (siguen editables dentro del proyecto) | ✅ | `ObjectsTest` |
| Transformación: mover, escalar, rotar, estirar, voltear, restablecer, opacidad | ✅ | `ObjectGeometryTest` |
| Duplicar (encima del original y desplazado), eliminar, orden Z en 4 operaciones | ✅ | `duplicate…`, `arrange…` |
| Deshacer y rehacer de las ediciones de objetos | ✅ | `historyOfObjectEditsUndoesCleanly` |
| Fuentes importadas y PNG guardados dentro del proyecto (portables entre dispositivos) | ✅ | `archiveKeepsOnlyReferencedAssets`, `missingAssetIsReported` |
| Toque sobre objetos girados, escalados o volteados; asa de escala y giro | ✅ | `transformRoundTripAndHitTest`, `handleDragScalesAndRotatesAroundCentre` |
| Guías magnéticas (centro y tercios) e imán de giro a 90° | ✅ | `rotationAndGuideSnapping` |
| 6 globos (diálogo, pensamiento, grito, susurro, rectangular, redondeado) con cola independiente unida sin costura | ✅ | `balloonTailsAttachOutsideAndVanishInside` + hoja visual |
| Lector de trazados SVG (M L H V C S Q T Z, absolutos y relativos) | ✅ | `pathParserHandlesRelativeAndShorthand` |
| Catálogo original de **59 stickers vectoriales** en 6 categorías, con 3 colores editables | ✅ | `stickerCatalogParsesAndResolvesSlots` + hoja visual |

**Total: 54 pruebas en el núcleo, todas superadas.**

### App Android
| Función | Estado |
|---|---|
| Menús Texto, Globo y Sticker en la barra inferior | 🟡 |
| Toque para seleccionar, tocar de nuevo para editar, toque en vacío para deseleccionar | 🟡 |
| Arrastrar para mover, con guías; dos dedos para mover, escalar y rotar; asa de esquina para escalar y rotar (o estirar en modo Estirar) | 🟡 |
| Punto verde para mover la cola del globo de forma independiente | 🟡 |
| Menú del objeto: editar, sustituir (sticker), duplicar, eliminar, 4 órdenes, voltear H/V, estirar, restablecer, opacidad | 🟡 |
| **Texto:** varias líneas; 10 estilos predefinidos originales; fuente (11 del sistema, 3 incluidas con licencia OFL, importar .ttf/.otf); negrita y cursiva; color; recuadro de fondo; contorno (color y grosor); sombra (color, X, Y, desenfoque); tamaño; alineación; altura de línea; espaciado; curvatura de −360° a 360°; opacidad | 🟡 |
| Frases frecuentes (crear, editar, eliminar e insertar) y fecha/hora (EXIF de la foto, hoy o fecha elegida; 8 formatos según el idioma) | 🟡 |
| **Globo:** texto, 6 formas, relleno, contorno, grosor, puntero sí/no y su ancho, fuente, color, tamaño máximo (el texto se reduce solo para caber), alineación, altura de línea, opacidad | 🟡 |
| **Sticker:** catálogo por categorías, importar PNG transparente, sustituir, 3 colores, tamaño del contorno, opacidad | 🟡 |
| Exportación y miniaturas con los objetos dibujados por el mismo motor que la vista previa | 🟡 |

## 2. Hoja visual
`docs/stage3_balloons_stickers.png` muestra los 6 globos con la cola y la zona de texto (rectángulo azul), y los 59 stickers con sus colores por defecto.

## 3. Problemas conocidos y limitaciones
1. **La interacción en pantalla no está probada en un dispositivo.** Las matemáticas están probadas, pero la sensación de los gestos (tamaño de las asas, conflictos con el zoom de la imagen) hay que validarla en el teléfono.
2. El **texto curvado** se dibuja en una sola línea (los saltos se convierten en espacios), igual que el "Circular Wrap" del original. Su caja de selección es aproximada.
3. La fuente **"Casual"** del sistema hace el papel de fuente de cómic. Su aspecto depende del fabricante del teléfono.
4. Los **"Object Effects"** de Pro (sombra/brillo de objeto, fundir con el fondo, borrar partes, efectos de color sobre objetos) se harán en la etapa 6, junto con los efectos.
5. El catálogo tiene 59 stickers propios, frente a los más de 1000 que Pro descargaba de sus servidores. El formato permite añadir más regenerando `tools/stickers/gen_stickers.py`.
6. Los datos EXIF de la exportación (fecha, cámara) siguen pendientes.

## 4. Archivos creados o modificados
```
core/model/…/Objects.kt (nuevo), ObjectJson.kt (nuevo), Document.kt, DocumentJson.kt, ProjectArchive.kt
core/model/src/test/…/ObjectsTest.kt (nuevo)
core/imaging/…/objects/{ObjectGeometry,BalloonGeometry,VectorSticker}.kt (nuevos)
core/imaging/src/test/…/ObjectGeometryTest.kt (nuevo)
tools/stickers/gen_stickers.py (nuevo) → app/src/main/assets/stickers/catalog.json
app/src/main/assets/fonts/{Poppins-Regular,Poppins-Bold,Lora-Variable}.ttf + OFL.txt + CREDITS.txt
app/…/render/ObjectRenderer.kt (nuevo)
app/…/data/{FontRepository,PhraseRepository}.kt (nuevos), ImageIo.kt
app/…/editor/{ObjectLayer,ObjectPanels,TextEditorPanel,ColorPicker}.kt (nuevos)
app/…/editor/{EditorViewModel,EditorScreen,EditorCanvas}.kt
app/…/BocadilloApp.kt, res/values{,-es}/strings.xml
```

## 5. Siguiente etapa (4): dibujo y máscaras
- Pincel, marcador y borrador con tamaño, dureza, opacidad, color y trazo suavizado, y presión del lápiz.
- Máscara roja / selección verde, invertir y difuminar, para aplicar efectos solo en una zona.
- History Paint y cuentagotas.
