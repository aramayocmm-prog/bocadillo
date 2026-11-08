# Etapa 6: tabla de diferencias visuales (antes de modificar)

Punto de control: tag `checkpoint-0.5.1` (commit actual de `main`).

## Fuentes comparadas

| Fuente | Qué es | Fiabilidad |
|---|---|---|
| **R** | La lámina de 18 pantallas adjunta | Según el propio encargo, es una recreación. Hay detalles que no existen en el APK (8 formas de globo, fuentes Impact/Arial/Helvetica, el menú "More…" con Open/Save project, "Set as wallpaper") |
| **C** | Capturas reales: "Add Effect" (Lite, estilo antiguo con ✖ rojo y ✔ verde), "Adjust" con secciones, "New Sticker", "New Word Balloon", "New Title", inicio con "Get a Picture" y barra Adjust · Effect · Sticker · Export | Reales, pero de versiones distintas |
| **A** | El APK PicSay Pro 1.8.0.5 analizado en la FASE 1 | Verificado en el código (nombres, menús y parámetros) |
| **H** | La app actual (Bocadillo 0.5.1) | Material 3, tema claro u oscuro según el sistema |

## Tabla por pantalla

| # | Pantalla | R pide | C / A dicen | H (hoy) | Conflicto y propuesta |
|---|---|---|---|---|---|
| 1 | Inicio | Logo "PicSay PRO" y 4 botones: Edit a Photo, Get a Photo, Recent Photos, More… | C: logo, "Say it within a picture" y un solo botón "Get a Picture", con la miniatura de la última foto. A: Get a Picture (galería, cámara, lienzo en blanco), Edited Pictures y Settings | Barra superior, pestañas Recientes/Favoritos, lista de proyectos y botones Importar, Cámara y En blanco | **No se puede usar el logo ni el nombre PicSay** (marca registrada; es una restricción del encargo original). Propuesta: misma disposición de R (fondo oscuro y 4 botones rectangulares con borde gris e icono blanco) con logo propio |
| 2 | Editor principal | Barra superior con ← y ✓; foto centrada; barra inferior con 4 botones: Adjust, Effect, Sticker, Export | C: igual (4 botones). A: Undo, Redo, Adjust, Effect, Sticker, Title, Word Balloon y Export | Barra Material con ←, deshacer, rehacer y exportar; barra inferior con 9 menús | Propuesta: 4 botones como R y C. Título y globo entran por Sticker, como en C. Deshacer y rehacer van a la barra superior. Color, Transformar, Dibujo y Más se reparten en los menús Adjust y Effect, según A |
| 3 | Menú Adjust | 8 filas: Exposure, Contrast, Saturation, Temperature, Tint, Boost, Sharpen, Smooth | C y A: Exposure / Contrast (juntos), Saturation, Temperature / Tint (juntos), Boost, Sharpen, Smooth; sección **Transform Picture** (Crop and Straighten, Resize, Rotate and Flip); sección **Misc** (Curves, Adjust RGB, Brightness, Fix Red-Eye, Blur, Paint Mask, Add Background Color, History Paint, Picture Location) | Fila horizontal de miniaturas (cambio de la 0.5.1) | R omite secciones con herramientas que ya funcionan, y el encargo prohíbe quitarlas. Propuesta: lista vertical negra con icono a la izquierda, separadores y cabeceras de sección, con el contenido de C y A. ¿Exposure y Contrast separados (R) o juntos (C y A)? |
| 4 | Ajuste (Exposure) | Foto arriba; slider azul con valor numérico y botones − y + | A: panel con slider y −/+, Cancel/Undo/Redo/Done [V] | Panel inferior con slider Material y valor; sin −/+ | Coinciden. Propuesta: slider azul con −/+ y valor a la derecha. Ya se actualiza en tiempo real |
| 5 | Menú Effect | Lista con miniatura fotográfica: Distort, Spotlight, Pixelize, Twirl, Bulge, Spherize, Stretch, Dizziness | C (Lite): iconos dibujados. A (Pro): secciones Effect, Distort, Color, Artistic, Photo effects, Filter & Frames y Misc (≈ 50 entradas) | Chips de familia y miniaturas horizontales | Propuesta: lista vertical con miniatura a la izquierda (la vista previa ya existe) y cabeceras de sección de A. R solo muestra los primeros 8 |
| 6 | Twirl | Foto, slider azul, "Strength", −/+ y 50 % | A: Twirl −90…90 | Panel con sliders y punto arrastrable | Igual que la 4, más el punto central arrastrable que ya existe |
| 7 | Menú Sticker | Word Balloon, Title, Hats & Helmets, Hairstyles, Eyes, Masks, Glasses, Mustaches & Beards | C: Word Balloon, Title, Hats & Helmets, Eyes & Eyewear, Hair, Halloween, Romance, Celebrations, Basic Shapes, Signs & Symbols. A: el catálogo completo se descargaba de un servidor | 59 stickers propios en 6 categorías + Noto Emoji + Mis stickers | **No se pueden copiar los stickers originales.** Las categorías de R y C (sombreros, peinados, máscaras, bigotes…) no existen en nuestro set. Opciones: (a) lista con nuestras categorías; (b) dibujar stickers propios nuevos para esas categorías (trabajo grande de contenido) |
| 8 | Formas de globos | Cuadrícula de 2 columnas con 8 formas en celdas gris oscuro | A: **6 formas** verificadas. C: "New Word Balloon" con vista previa, campo de texto y cuadrícula de **estilos de color** (ABC) | Fila de 6 miniaturas | R inventa 2 formas. Propuesta: cuadrícula gris oscuro con las 6 formas reales y, aparte, estilos de color como en C |
| 9 | Edición de globo | Foto con globo; barra inferior: texto (A), relleno, contorno, alineación | A: asas de escalar/rotar y cola independiente [V] | Panel con pestañas; asas y cola ya funcionan | Propuesta: barra inferior de 4 botones cuadrados oscuros como R; los controles ya existen |
| 10 | Editor de títulos | Vista previa; lista de fuentes con resaltado azul; botones B, I, contorno y color | C: "New Title", "Tap here to set Font", campo de texto y cuadrícula de estilos ABC | Panel con pestañas Estilos, Fuente, Color… | **Impact, Arial, Helvetica, Comic Sans, Cooper Black y Times son fuentes propietarias**: se mostrarán las del sistema y las OFL incluidas. Propuesta: diseño de R con nuestras fuentes |
| 11 | Estilos de texto | Vista previa, paleta arcoíris, sliders Outline (px) y Shadow (%) | A: contorno y sombra configurables | Pestaña Color con selector HSV y sliders | Propuesta: paleta arcoíris horizontal y 2 sliders azules con valor, como R |
| 12 | Paint | Foto; cuadro de color, pincel, borrador; sliders Size (px) y Opacity (%) | A: pincel, marcador y borrador; tamaño, dureza, color y opacidad | Panel minimizable con controles | Propuesta: disposición de R. Dureza y marcador se mantienen (fila extra) |
| 13 | Export | Save picture to album, Share picture, Send to app, Set as wallpaper | A: Save picture to album, Save as file…, Share picture via, Set picture as | Hoja con formato, calidad, tamaño y 3 botones | "Send to app" y "Set as wallpaper" no están en A. "Set picture as" incluye el fondo de pantalla. Propuesta: lista oscura con los 4 de A; formato y calidad en un diálogo al guardar |
| 14 | Recortar | Asas circulares, cuadrícula, proporciones Free, 1:1, 4:3, 16:9 | A: Freeform, Original, Golden, Square, Wallpaper, Custom | Asas en L, cuadrícula y proporciones de A | Propuesta: asas circulares y barra inferior de proporciones con las de A más 4:3 y 16:9 |
| 15 | Rotar / enderezar | Foto inclinada, cuadrícula, grados, slider, botones ↺ ↻ | A: enderezar está dentro de "Crop and Straighten" (±10°); "Rotate and Flip" va aparte | Enderezar dentro de Recortar; Orientar aparte | Propuesta: pantalla de R (slider ±10° y botones de giro de 90°) sin quitar voltear |
| 16 | Redimensionar | Width, Height, px, Maintain aspect ratio, OK/Cancel | A: Keep Proportions y modos Quick Fit, Scale, Crop, Expand Edges, Stretch | Diálogo con ancho, alto, proporciones y modos | Propuesta: diálogo oscuro de R y debajo los modos de A |
| 17 | Filtros de color | Lista con miniaturas: Sepia, Invert, Rainbow, Grayscale, Colorize | A: sección Color del menú Effect | Menú Color con miniaturas | Queda incluida en la 5 (lista con secciones) |
| 18 | More… | New blank picture, Open project…, Save project…, Recent projects, Settings | A: no existe "More": lienzo en blanco (en Get a Picture), Edited Pictures y Settings | Lienzo en blanco y proyectos en Inicio; autoguardado; **sin pantalla de Ajustes** | Propuesta: pantalla More… de R con las acciones reales (el guardado ya es automático; "Save project" exportaría el `.bcproj`). Settings es nueva: implementar las preferencias de A |

## Validación visual (sección 7 del encargo)

- No hay emulador ni Android SDK en este entorno: las capturas reales tendrían que hacerse en GitHub Actions (emulador o renderizado de pantallas Compose en JVM).
- **R no permite medir un SSIM ≥ 95 % de forma válida**:
  - cada pantalla mide unos 250×350 px y es una recreación;
  - el contenido es distinto (foto del árbol, barra de estado, fuentes propietarias);
  - el logo y los iconos no se pueden reproducir.
- Se propone medir SSIM solo en regiones comparables (barras, filas de menú, sliders) usando una foto equivalente, y declarar el resultado medido sin redondear.
