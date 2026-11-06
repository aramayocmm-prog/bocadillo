# FASE 1 — Informe técnico del APK original

**Archivo analizado:** `c0195312-PICSAYPRO.apk` (1 916 417 bytes)
**SHA-256:** `749a01ca50b0e97ff014b56aaa01bf83f228527ab86d8c1a4d18683fec2f2380`
**Fecha del análisis:** 2026-11-06
**Método:** solo análisis estático. JADX, Apktool, aapt y el Android SDK no estaban disponibles en el entorno, y la red estaba bloqueada. Se escribieron decodificadores propios en Python (AXML, `resources.arsc`, DEX y un desensamblador Dalvik), que están en `tools/apk-analysis/`. No se ejecutó la app.

Leyenda de evidencia:
- **[V]** Verificado: leído directamente en el bytecode, los recursos o el manifiesto.
- **[I]** Inferido: deducido con alta probabilidad a partir de nombres, tipos y flujo, sin confirmación completa.
- **[P]** Pendiente de validación en ejecución: no se puede determinar por análisis estático.

---

> **Actualización (rev. 2):** se recibió y analizó también **PicSay Pro 1.8.0.5** (`com.shinycore.picsaypro`, SHA-256 `0d7270e7f0aeb869008efbc94bfa167ef0801f56ccd18ebb6589b3a0dc6ae650`). La **referencia principal de la reconstrucción pasa a ser Pro**; ver la **Parte B** al final. Las secciones 0 a 8 describen la edición Lite y siguen siendo válidas como base común (la arquitectura, el formato de documento, los objetos y los filtros compartidos son los mismos).

## 0. Hallazgo crítico (rev. 1): el primer APK era PicSay *Lite*, no PicSay Pro

| Dato | Valor | Evidencia |
|---|---|---|
| Paquete | `com.shinycore.picsayfree` | Manifiesto [V] |
| Versión | 1.6.0.1 (versionCode 17170433) | Manifiesto [V] |
| Edición | "Lite edition" | `string/about_edition` [V] |
| Publicidad | Google Mobile Ads (`AdActivity`) y compras en la app (`InAppPurchaseActivity`) | Manifiesto [V] |
| Ventas de Pro | "Upgrade to PicSay Pro… over 1000 stickers", "multi-line text, more styles and fonts", "use your own fonts" | `string/pro_*` [V] |

**Restricciones de la edición Lite que confirman las cadenas [V]:**
- Solo guarda los **8** documentos más recientes; los más antiguos se borran (`pro_favorites_description`).
- No hay favoritos (`pro_addfavorite_description`).
- Descarta la transparencia de las imágenes importadas (`pro_alpha`).
- La resolución está limitada a "Normal"; la opción "PicSay Pro" está bloqueada (`pro_resolution_description`, `array/resolution_presets`).
- No hay títulos de varias líneas, ni estilos y fuentes adicionales, ni fuentes propias (`pro_titles_description`, `pro_fonts_description`).
- Trae pocos stickers locales (123 formas) frente a "más de 1000" en Pro.
- No hay más efectos: la entrada "Much More…" es un anuncio de Pro (`PicSayUI.Filters.aq` → `icon_pro`, `pro_effects`).

**Consecuencia:** varias funciones de las fases 5 a 8 (texto multilínea, fuentes importadas, transparencia PNG, favoritos, más efectos) **no se pueden verificar con este binario**, porque solo existen en PicSay Pro. Se implementarán como funciones *documentadas por la propia app* (las cadenas de venta las describen), pero sin un comportamiento de referencia. Si dispones del APK real de PicSay Pro (`com.shinycore.picsaypro`), conviene analizarlo antes de cerrar esas fases.

---

## 1. Estructura del paquete

| Elemento | Contenido |
|---|---|
| `classes.dex` | 2,4 MB, 2556 clases. 605 son propias (`com.shinycore.*`, `QuartzCore`, `a.*`, `b.*`) y ~1300 son de Google Play Services, Ads y Analytics |
| `resources.arsc` | 597 entradas; solo idioma inglés |
| `res/layout*` | 40 + 3 (v11) + 1 (v17) layouts |
| `res/raw/*.bin` | 59 recursos: iconos vectoriales en formato propio (`mA…`) y tablas de estilos (`pcss`) |
| `assets/100…260` | 15 paquetes de stickers, 123 formas vectoriales en formato propio `shp ` |
| `assets/fonts` | `FOO.ttf`, `LOCKERGN.ttf` (Ray Larabie, freeware) y `SilverAgeUCBB.otf` (Nate Piekos, comercial) |
| `lib/` | `libImageOp.so` solo para **armeabi-v7a y x86 (32 bits)**. **No hay arm64-v8a** |
| Firma | `META-INF/CERT.DSA` (firma v1/JAR, DSA) |

**SDK:** minSdk 14, targetSdk 24 (Android 7.0). `hardwareAccelerated="false"` y `largeHeap="true"`.

### 1.1 Componentes del manifiesto [V]

| Componente | Función |
|---|---|
| `com.shinycore.picsayfree.main` (Activity, LAUNCHER) | Actividad única que contiene la galería y el editor. Acepta `SEND` y `EDIT` con `image/*` |
| `com.shinycore.PicSayUI.PicSayPreferences` | Ajustes (`PreferenceActivity`, API obsoleta) |
| `TextProvider` (ContentProvider) | Base SQLite `phrases.db`, tabla `phrases(_id, phrase, catagory)`, para las frases frecuentes |
| `PictureProvider` (exportado) | Expone las imágenes exportadas (`content://com.shinycore.picsayfree/{internal,external}/pictures`) |
| `ProcessingService` | Servicio para el procesado y guardado en segundo plano |
| Permisos | INTERNET, ACCESS_NETWORK_STATE, READ/WRITE_EXTERNAL_STORAGE y WAKE_LOCK |

### 1.2 Arquitectura interna [V]

El código es un **port de una app iOS** (Objective‑C traducido a Java). Lo indican los paquetes `QuartzCore` (con `CGRect`), una clase raíz tipo `NSObject` (`a.q`), selectores como cadenas (`didLoadSourceImage`, `dismissPopoverAnimated`) y controladores (`subController`, `galleryController`).

| Capa | Paquete | Función |
|---|---|---|
| Modelo de documento | `com.shinycore.PicSay` | Documento, capas y objetos (título, globo, sticker), fuentes y catálogo de stickers |
| Acciones (historial) | `com.shinycore.PicSay.Action` (41 clases) y `Shared.SC*Action` | Patrón Command: cada edición es una acción serializable que se puede deshacer |
| Filtros (modelo) | `com.shinycore.PicSay.Filters` | Parámetros de cada efecto e ID FourCC para el motor nativo |
| Filtros (UI) | `com.shinycore.PicSayUI.Filters` | Paneles, sliders, vista previa y pincel de máscara |
| Tareas | `PicSay.Tasks` y `Shared.Tasks` | Importación, conversión, filtrado y render a archivo en hilos |
| Motor nativo | `libImageOp.so` | Imágenes en mosaico ("Tim"), remuestreo Lanczos, filtros, mezcla de capas y codificación JPEG/PNG |
| UI propia | `com.shinycore.a`, `b`, `PicSayUI.Legacy` | Toolkit tipo UIKit (vistas, popovers, sliders) |

---

## 2. Navegación [V salvo indicación]

```
Pantalla principal (main)
├── Get a Picture → Import… (galería / proveedor), Cámara (imageCaptureOutput.jpg) [I], New Blank Picture
│     └── Import large picture… → elegir resolución (Fast / Normal / [Pro bloqueado]) + "Use by default"
├── Edited Pictures (galería de documentos)
│     ├── Pestañas: Recent | Favorites (Favorites bloqueado en Lite)
│     └── Menú por documento: Add to favorites, Delete, Export, Duplicate, Edit
├── Settings (PicSayPreferences)
│     ├── Sharing: Default Subject, Signature (texto que se añade al e-mail)
│     ├── Editor: Hide Status Bar, Multi-touch panning
│     └── General: Show Most Recent, Reset messages
└── Editor
      ├── Barra: Undo | Redo | Effect | Sticker | Title | Word Balloon | Export/Share
      ├── Menú contextual del objeto seleccionado:
      │     Edit · Replace · Object Effect · Transform (Scale & Rotate Tool, Stretch Tool,
      │     Rotate and Flip: Rotate 90° Right / 180° / Flip H / Flip V, Reset) · Arrange
      │     (Bring to Front / Forward, Send Backward / to Back) · Duplicate · Delete
      ├── Asas sobre el objeto: escalar (handle_scale40), rotar (handle_rotate40), cola del globo
      ├── Zoom: botones +/− (zoom_controls_vertical) y pellizco
      └── Panel de filtro: Cancel | Undo | Redo | Done, con slider de valor y botones −/+
```

### 2.1 Menú de efectos (orden exacto de `PicSayUI.v`) [V]

| Sección | Entradas |
|---|---|
| **Effect** (sin título) | Object Effects*, Distort (Twirl / Spherize / Bulge / Stretch), Spotlight, Marker, Pixelize |
| **Adjustments** | Exposure, Contrast, Temperature, Tint, Saturation, Brightness |
| **Effects** | Colorize, Hue, Rainbow, Sepia Toning, Invert, Dizziness |
| **Transform Picture** | Crop and Straighten, Resize, Rotate and Flip |
| **Much More…** | Anuncio de PicSay Pro (no es una función) |

\* "Object Effects" solo aparece cuando hay un objeto seleccionado.

---

## 3. Inventario de herramientas y parámetros

### 3.1 Motor de filtros: identificadores FourCC [V]

Cada filtro del modelo devuelve un código de 4 caracteres que se pasa a `SCImageFilter.nativeFilterImageProxy(obj, id, amount, scale, …)`.

| Menú | Clase modelo | FourCC | Campos serializados | Slider UI (mín / máx / paso / defecto) | Rutina nativa |
|---|---|---|---|---|---|
| Brightness | `Filters.c` (ColorMatrix) | `brig` | amount | −100 / 100 / 1 / 0 [V] | `setupEffectColorMatrix` |
| Exposure | `Filters.m` | `expo` | exposure, contrast | −2 / 2 [V] | ColorMatrix |
| Contrast | `Filters.m` (modo 1) | `expo` | exposure, contrast | −0,5 / 0,5 [V] | ColorMatrix |
| Temperature | `Filters.aa` | `temp` | temperature, tint | −1 / ? [V mín, P máx] | `setupEffectTemperature` |
| Tint | `Filters.aa` (modo 1) | `temp` | temperature, tint | −1 / ? [V mín, P máx] | idem |
| Saturation | `Filters.w` | `satu` | amount | −1 / ? [V mín] | ColorMatrix |
| Colorize | `Filters.i` (hereda de gray) | `colr` | angle (tono), amount | [P] | `setupEffectColorize` |
| Hue | `Filters.q` | `hue ` | amount (°) | −180 / 180 / 1, defecto 90 [V] | ColorMatrix |
| Rainbow | `Filters.v` | `rnbw` | amount | defecto 1 [V] | `FilterRainbow` |
| Sepia Toning | `Filters.x` | `spia` | amount | defecto 1 [V] | ColorMatrix |
| Invert | `Filters.r` | `invt` | — | sin slider [V] | `FilterInvert` |
| Dizziness | `Filters.s` | `ofoc` | amount, scale | −30 / 30 / 1, scale 1 [V] | `FilterDistortOutOfFocusOffset` |
| Pixelize | `Filters.u` | `pixl` | amount (tamaño de bloque) | constantes 16, 45 y 427 [P semántica] | `FilterPixelize_initWithScale` |
| Spotlight | `Filters.z` | `spot` | center, radius, falloffDistance, color | radio 1; constantes 0,4, 0,75 y 2 [P] | máscara + mezcla |
| Twirl | `Filters.l` | `twrl` | type, amount, center, radius, angle | [P rangos] | `setupEffectDistortTwirl` |
| Spherize | `Filters.l` | `sphr` | idem | factor 0,99 [V] | `setupEffectDistortSpherize` |
| Bulge | `Filters.l` | `bulg` | idem | factor 0,99 [V] | `setupEffectDistortBulge` |
| Stretch | `Filters.l` | `strc` | idem | [P] | `setupEffectDistortStretch` |
| Marker | `Filters.t` | `pntb` | amount, blendMode (25) | tamaño de pincel 30 [I] | trazos `PEStroke` |
| Gray (interno) | `Filters.p` | `gray` | angle | base de Colorize [V] | ColorMatrix |
| Crop and Straighten | `Filters.k` | `crop` | rect, angle, scale, constrain, constrainAspectRatio | enderezar −10° / 10° / 0,1 [V] | `FilterCrop`, `FilterTransform` |

**Distorsiones (Twirl/Spherize/Bulge/Stretch):** la vista previa en Java usa una **malla de vértices** (`PicSayUI.Filters.p.c()` y `.d()`). Para cada vértice dentro del radio, el desplazamiento radial se calcula con `r' = r · (k·r + (1+k)·…)/…`, con `k = 0,99·amount` y un umbral `ε = 1e‑6` [V parcial]. El render definitivo usa una LUT nativa (`FilterDistort_createDistortLUT`) [V]. La forma exacta de cada curva queda **[P]**; se reproducirá con fórmulas estándar equivalentes y se ajustará contra capturas del original.

**Máscara de efecto:** existen `InvertMaskAction`, `StrokeAction`, `SetBrushAction`, el archivo `.msk`, un pincel y un borrador con parámetros **Diameter** y **Hardness** y el campo `apply` en `Filters.b` [V]. Los efectos se pueden **pintar por zonas** con pincel/borrador e invertir la máscara [I]. La UX exacta (valor por defecto: aplicar a toda la imagen) queda [P].

**Escala de previsualización:** los filtros se calculan sobre un proxy reducido de la imagen y se vuelven a calcular a resolución completa al aceptar (`renderFullImageIfNeeded`) [V].

### 3.2 Transformación del documento [V]

| Herramienta | Detalle |
|---|---|
| Crop | Proporciones: Freeform, Original, Golden, Square, Wallpaper y Custom (diálogo con ancho:alto) |
| Straighten | Ángulo de −10° a 10° en pasos de 0,1°, dentro de Crop and Straighten |
| Rotate and Flip | Rotar 90° a la derecha, 180°, voltear horizontal y voltear vertical (`OrientDocumentAction`). Existe la cadena "Rotate 90° Left" pero no se encontró referencia en el código [P] |
| Resize | Diálogo de ancho × alto con "Keep Proportions". Modos: Quick Fit, Scale…, Crop…, Expand Edges… y Stretch (`ScaleDocumentAction`, `TransformDocumentAction`, `FillCanvasAction` con color de fondo) |
| Rotación libre | Solo para objetos: slider 0–360° en pasos de 1 (`PicSayUI.Filters.af`) [V]. Para la imagen completa no se encontró [P] |

### 3.3 Objetos editables [V]

Jerarquía: `PicSay.i` (objeto) → `r` (capa) → `q` (transformable, con `Matrix`) → `o` **Título** / `n` **Sticker**; `s` **Globo** (hereda de `r`).

**Títulos (`PicSay.o`):** varias `Paint` (relleno, contorno y sombra), tamaño, líneas (`o$a[]`), alineación y fuente. Diálogo `dialog_title`: texto, cuadrícula de estilos (`title_styles.bin`, 12 estilos con las fuentes Foo, Lockergnome y Comic) y vista previa en vivo. Controles: **Text Size** y **Alignment**. Multilínea: solo en Pro [V cadena]. Colores por estilo: Fill, Second Fill, Outline, Second Outline, Text, Shadow, Midtone, Highlight y Reset (`array/style_colors`). Los títulos **siguen siendo objetos editables** en el documento [V].

**Insertar texto [V]:**
- Common Phrase: lista SQLite editable con 11 frases por defecto ("I Love You", "Happy Birthday"…), añadir y editar.
- Date and Time: formatos `MMMM dd`, `EEEE`, `© yyyy`, `EEEE, MMMM dd`, `EEEE, MMMM dd, yyyy`, `h:mmaa`, `MM/dd/yy`, con fecha de captura EXIF o personalizada (`Set Custom Date`, "Today").
- Location Based: usa el GPS EXIF de la foto para obtener una dirección. Requiere red o Geocoder [I]; se trata como función moderna opcional y **offline queda desactivada**.

**Fuentes [V]:** 3 fuentes incrustadas, más Sans Serif, Sans Serif Bold, Serif y Serif Bold del sistema, paquetes "Extra Font Packs" de terceros (`com.monotype.android.font.*`, `.tpfcf`) y "Used Fonts". Las fuentes propias del usuario solo están en Pro.

**Globos (`PicSay.s`, acciones `WordBalloonSizeAction` y `WordBalloonTailPositionAction`) [V]:**
- Tipos: Speech, Thought, Scream ("SCREAM!"), Whisper, Rectangle y Rounded.
- Diálogo: texto, botón de marcos, estilos (`balloon_styles.bin`, 7 estilos con fuente Comic) y controles **Text Size** y **Line Height**.
- **Puntero (cola) independiente** con su propia posición [V]. Tamaño del cuerpo independiente [V].

**Stickers (`PicSay.n`) [V]:**
- Son **formas vectoriales** (`shp `: cabecera, caja de 50×50 y comandos de trazado) con **colores reemplazables** (`ObjectColorsAction`, `colorSwatches`).
- Estilos de forma y línea (`shape_styles.bin`) y **Outline Size**.
- Categorías (`propscat.bin`) e índice (`propsids.bin`). Nombres incluidos: hats, hair, eyes, glasses, mustache, heart, valentine-*, symbols…
- Acciones: New, Replace, Object Effect, Transform, Arrange, Duplicate y Delete.
- Los paquetes extra se descargaban de `picsaypro.com` y `shinycore.com` (servidores fuera de servicio [I]).
- **No se reutilizarán**: son recursos protegidos.

**Manipulación [V]:** selección por toque, asas de escala y rotación, Stretch Tool (escala no uniforme), Reset Transform, orden Z en 4 operaciones, duplicar, eliminar y el aviso "tap the blue handle to switch tools".

### 3.4 Dibujo [V]

`PEStroke` es nativo y guarda los puntos (x, y, presión) con `brushIndex`, Diameter y Hardness. Herramientas Brush, Eraser y Marker. El color se elige en un selector HSB con opacidad (`color_hue`, `color_saturation`, `color_brightness` y `opacity`). Soporta los eventos del lápiz HTC (`com.htc.pen.PenEvent`). Historial propio del pincel: `PEPaintHistorySnapshot`.

### 3.5 Historial y proyecto [V]

- Patrón Command con `historyIndex`, `redoResetVersion` e instantáneas (`snapshot_`, `SCHistorySnapshot`).
- Por documento hay una carpeta `pictures/<id>/` con estos archivos:

| Archivo | Contenido |
|---|---|
| `#.pxy` | Imagen fuente en mosaico (formato nativo Tim) |
| `.his` | Historial serializado |
| `.imd` | Metadatos |
| `.msk` | Máscara |
| `.dat` | Datos del documento |
| `sourceAlpha` | Canal alfa |

- **Edición no destructiva:** el documento es "imagen original + lista de acciones", y se puede volver al original con `transform_reset` y `ResetFilterAction`.
- Galería de recientes con miniaturas; papelera (`didLoadTrashItems`, `delete_this.chk`).
- **No existe recuperación explícita tras un cierre inesperado** [P]. La persistencia del historial lo cubre en parte [I].

### 3.6 Importación y exportación [V]

- **Importar:** `image/*` desde Intents (SEND, EDIT, PICK), cámara y lienzo en blanco. Las imágenes grandes se reducen según la resolución elegida. Se respeta la orientación EXIF (274).
- **Exportar:**
  - Save picture to album (carpeta "PicSay").
  - Save as file… (`pref_exportPath`).
  - Share picture via (con asunto y firma para e-mail).
  - Set picture as (`ATTACH_DATA`).
- **Formatos:** JPEG y PNG (`pref_saveFormat`). La calidad JPEG se pasa al nativo (`FilterOutputJPEG_initWithOutput_quality`); su valor queda [P]. El color de fondo para aplanar la transparencia es `pref_exportBackgroundColor`.
- Se escriben las etiquetas EXIF Orientation, Software, DateTime y PixelX/YDimension.
- **No hay marca de agua** en el código [I]. La firma "Created with PicSay" solo se añade al texto del e-mail.

---

## 4. Componentes obsoletos que requieren una implementación moderna

| Original | Problema en Android 10–16 | Sustituto |
|---|---|---|
| `libImageOp.so` solo de 32 bits | Desde 2019 Google Play exige 64 bits, y muchos dispositivos actuales son solo ARM64 | Motor propio en Kotlin + AGSL (RuntimeShader, API 33+) o RenderScript-free CPU con corrutinas; sin código nativo heredado |
| WRITE/READ_EXTERNAL_STORAGE y rutas `/sdcard` | Almacenamiento con ámbito (scoped storage) | Photo Picker, SAF (`ACTION_CREATE_DOCUMENT`) y MediaStore (`RELATIVE_PATH`) |
| `PreferenceActivity` | Obsoleta | DataStore + pantalla Compose |
| `hardwareAccelerated=false` y Canvas por software | Lento en pantallas de alta densidad | Compose Canvas con aceleración por hardware |
| `ZoomButton`, `GridView`, `ListView` | Obsoletos | Gestos de transformación de Compose y LazyGrid |
| Ads, In-App Purchase, Analytics (GTM/GAv4) y descarga de stickers | Contrarios a los requisitos (sin anuncios, sin red) | Se eliminan |
| `TextProvider` y `PictureProvider` exportados | Superficie de ataque innecesaria | Room interno y `FileProvider` solo para compartir |
| Firma v1 DSA | No vale para targetSdk ≥ 30 | Firma v2/v3 con tu clave |
| Fuentes de terceros (`com.monotype…`, `.tpfcf`) | Ecosistema desaparecido | Importar TTF/OTF por SAF |
| Lápiz HTC | Ya no existe | `MotionEvent` con presión y stylus estándar |

---

## 5. Matriz de equivalencia funcional

Estado: **O** = existe en el original (Lite) y es verificable; **Pro** = solo descrito como función de Pro, sin referencia; **N** = función nueva solicitada que no existe en el original.

| # | Función | Origen | Evidencia | Nueva implementación (módulo · técnica) |
|---|---|---|---|---|
| 1 | Abrir foto (galería, cámara, compartir) | O | V | `:feature:import` · Photo Picker, `ACTION_SEND`/`EDIT`, TakePicture |
| 2 | Lienzo en blanco | O | V | `:feature:import` |
| 3 | Elegir resolución de importación | O | V | Diálogo con presets; sin límite artificial |
| 4 | Recortar libre y con proporciones (Original, Golden, Square, Wallpaper, Custom) | O | V | `:tools:transform` · Compose overlay + `CropOp` |
| 5 | Enderezar ±10° | O | V | `StraightenOp` (rotación + auto-crop) |
| 6 | Rotar 90°/180° y voltear H/V | O | V | `OrientOp` (Matrix) |
| 7 | Rotar 90° a la izquierda | O (cadena) | P | `OrientOp` |
| 8 | Rotación libre de la imagen | N | — | `RotateOp` (separada como mejora) |
| 9 | Cambiar tamaño con proporciones | O | V | `ResizeOp` (remuestreo de alta calidad) |
| 10 | Expandir bordes con color | O | V | `ExpandCanvasOp` |
| 11 | Escalar / Quick Fit / Stretch | O | V | `ScaleOp` |
| 12 | Brillo | O | V | `ColorMatrixOp(brig)` |
| 13 | Exposición | O | V | `ExposureContrastOp` |
| 14 | Contraste | O | V | idem |
| 15 | Saturación | O | V | `ColorMatrixOp(satu)` |
| 16 | Temperatura | O | V | `TemperatureTintOp` |
| 17 | Tinte | O | V | idem |
| 18 | Colorear | O | V | `ColorizeOp` |
| 19 | Tono (Hue) | O | V | `HueOp` |
| 20 | Invertir | O | V | `InvertOp` |
| 21 | Sepia | O | V | `SepiaOp` |
| 22 | Pixelizar | O | V | `PixelizeOp` |
| 23 | Distorsiones Twirl, Spherize, Bulge y Stretch | O | V/P | `DistortOp` · AGSL o CPU con LUT; centro, radio, cantidad y ángulo |
| 24 | Spotlight | O | V/P | `SpotlightOp` (centro, radio, difuminado, color) |
| 25 | Arcoíris | O | V/P | `RainbowOp` |
| 26 | Mareo (Dizziness) | O | V/P | `OutOfFocusOp` (desplazamiento múltiple) |
| 27 | Pintar el efecto por zonas (máscara, pincel/borrador, invertir) | O | V/I | `MaskLayer` + `BrushEngine` |
| 28 | Pincel, Marcador y Borrador; tamaño, dureza, color y opacidad | O | V | `:tools:draw` · trazos suavizados (Catmull-Rom) y presión |
| 29 | Selector de color HSB + opacidad | O | V | Componente Compose |
| 30 | Títulos con estilos (relleno, contorno, sombra, colores) | O | V | `:objects:text` · StaticLayout + Paint |
| 31 | Texto multilínea | Pro | — | Se implementa |
| 32 | Fuentes del sistema e incrustadas | O | V | Fuentes libres (OFL) en lugar de las originales |
| 33 | Importar fuentes TTF/OTF | Pro | — | SAF + copia interna |
| 34 | Altura de línea, alineación y tamaño | O | V | Se implementa |
| 35 | Grosor del contorno y sombra configurable | O / Pro | I | Se implementa |
| 36 | Frases frecuentes | O | V | Room |
| 37 | Insertar fecha y hora (EXIF o personalizada) | O | V | `ExifInterface` |
| 38 | Texto por ubicación | O | V | Opcional; desactivado offline (requiere Geocoder) |
| 39 | Globos: habla, pensamiento, grito, susurro, rectangular y redondeado | O | V | `:objects:balloon` · Path paramétrico |
| 40 | Cola del globo independiente, tamaño, estilos, tamaño de texto y altura de línea | O | V | Se implementa |
| 41 | Stickers vectoriales con colores editables y categorías | O | V | `:objects:sticker` · **set nuevo original** en VectorDrawable/SVG |
| 42 | Importar PNG transparente como sticker | N / Pro | — | Se implementa |
| 43 | Mover, escalar, rotar, estirar y restablecer objetos | O | V | Gestos + asas |
| 44 | Duplicar, eliminar, sustituir y orden Z (4 operaciones) | O | V | Se implementa |
| 45 | Opacidad del objeto | O / I | I | Se implementa |
| 46 | Efectos sobre objetos | O | V | Mismos `Op` aplicados a la capa del objeto |
| 47 | Deshacer y rehacer ilimitado | O | V | Pila de comandos inmutables |
| 48 | Restaurar el original | O | V | Re-render desde la fuente |
| 49 | Proyectos editables y recientes | O | V | Formato `.psproj` (ZIP: JSON + imagen fuente + máscaras) |
| 50 | Favoritos | Pro | — | Se implementa |
| 51 | Duplicar proyecto | O | V | Se implementa |
| 52 | Autoguardado y recuperación tras un cierre | N | — | Diario de comandos + WorkManager |
| 53 | Exportar JPEG/PNG con calidad y fondo para transparencia | O | V/P | `:feature:export` · MediaStore/SAF |
| 54 | Conservar transparencia PNG | Pro | — | Se implementa |
| 55 | Elegir resolución de exportación | N | — | Se implementa |
| 56 | Compartir y "Establecer como" | O | V | Sharesheet + `FileProvider`, `ATTACH_DATA` |
| 57 | Asunto y firma por defecto al compartir | O | V | Ajustes |
| 58 | Ocultar barra de estado y paneo multitáctil | O | V | Ajustes (inmersivo) |
| 59 | Modo oscuro, tablets, ES/EN y exportación en segundo plano | N | — | Mejoras separadas |

---

## 6. Pendientes de validación en ejecución

1. Rangos máximos de Temperature, Tint, Saturation, Rainbow, Sepia y Pixelize (solo se vieron los mínimos o los valores por defecto).
2. Fórmulas exactas de las curvas de Twirl, Spherize, Bulge y Stretch, y rangos de cantidad y ángulo.
3. Forma del difuminado de Spotlight, paleta y dirección de Rainbow, y patrón de desplazamientos de Dizziness.
4. Valor por defecto de `apply` (toda la imagen o solo la máscara) y flujo exacto del pincel de máscara.
5. Calidad JPEG de exportación y tamaños reales de los presets Fast y Normal (hay una constante de 1024 en `main`).
6. Número de pinceles disponibles en `brush.bin`.
7. Si "Rotate 90° Left" aparece en la UI.
8. Todo lo marcado como Pro.

Para validarlos hace falta instalar el APK en un dispositivo o emulador de 32 bits (o ARM64 con compatibilidad de 32 bits) y comparar capturas con parámetros fijos. Este entorno no tiene emulador.

---

## 7. Limitaciones del entorno (verificadas)

- No hay Android SDK, ni `kotlinc`, ni acceso a `dl.google.com` o Maven Google. **Aquí no se puede compilar un APK ni ejecutar pruebas instrumentadas.**
- Sí hay JDK 21 y Gradle 8.14.3. Se puede escribir el proyecto completo y probar la lógica pura de Kotlin/JVM (operaciones de imagen y serialización) solo si se dispone del compilador de Kotlin. Sin red tampoco se puede descargar.
- Por eso el código se entregará con instrucciones reproducibles para compilarlo en Android Studio. Ningún resultado de compilación o prueba se dará por bueno sin haberse ejecutado.

---

## 8. Recursos y propiedad intelectual

No se copiará código, iconos, stickers (`shp`), estilos (`pcss`), nombres de marca (PicSay, Shinycore) ni la fuente comercial SilverAge. Las fuentes Foo y Lockergnome son freeware, pero sin una licencia de redistribución clara se sustituirán por fuentes OFL. Los parámetros numéricos y los comportamientos documentados arriba son datos funcionales, que se usan solo para reproducir el comportamiento.

---
---

# PARTE B — PicSay Pro 1.8.0.5 (referencia principal)

## B1. Identificación y diferencias estructurales [V]

| Dato | Lite 1.6.0.1 | **Pro 1.8.0.5** |
|---|---|---|
| Paquete | `com.shinycore.picsayfree` | `com.shinycore.picsaypro` |
| minSdk / targetSdk | 14 / 24 | 8 / 24 |
| Clases propias | 605 | **913** (991 en total) |
| Publicidad, IAP y Analytics | Sí | **No** (ningún componente de Google) |
| Nativo | `libImageOp.so` | `libImageOp.so` + **`libGLImageOp.so`** (OpenGL ES 3: texturas, FBO y shaders para vista previa acelerada). **Siguen siendo solo 32 bits** (armeabi-v7a y x86) |
| Actividades extra | — | `DocumentPicker` y el alias `PicSayDocuments` (`GET_CONTENT image/*`, desactivado por defecto): permite que otras apps elijan imágenes de PicSay |
| Fuentes incluidas | 3 | **12**: Daniel, Yataghan, Anton, Vinque, Delta Hey Max Nine, Deftone Stylus, Foo, Lockergnome y Comic (4 variantes), con `OFL.txt` |
| Fuentes del sistema | 4 | 6: Sans, Sans Bold, Serif, Serif Bold, Serif Italic y Serif Bold Italic |
| Stickers locales | 123 formas (15 paquetes en `assets`) | **15 formas en `res/raw`**. El catálogo completo (`propsids.bin`, 21 KB) se **descargaba** en 23 paquetes (2,1 MB) desde `picsaypro.com` y `shinycore.com` |
| Archivos `res/raw` | 59 | 100 (iconos nuevos, `grain.jpg`, `heatmap.png`, `xray.png`, `pencil_sketch_mapped.jpg` y recursos para ojos rojos) |

**Consecuencia para los stickers:** el "más de 1000 stickers" de Pro **dependía de servidores externos**. La app no los incluía. La reconstrucción necesita un catálogo propio, nuevo y offline.

## B2. Navegación del editor Pro [V]

Barra principal (`PicSayUI.i`): **Undo · Redo · Adjust · Effect · Sticker · Title (varita) · Word Balloon · Export/Share**.

Frente a Lite, hay un menú nuevo **Adjust** y el menú Transform del objeto añade la **Perspective Tool**. El menú contextual del objeto es el mismo: Edit, Replace, Object Effect, Transform, Arrange, Duplicate y Delete.

### B2.1 Menú **Adjust** (`PicSayUI.q`)
| Sección | Herramienta | FourCC | Parámetros verificados |
|---|---|---|---|
| Adjust | Exposure / Contrast | `expo` | exposure −2…2; contrast −0,5…0,5; defecto 0 |
| | Saturation | `satu` | mín −1 |
| | Temperature / Tint | `temp` | temperature y tint con mín −1 |
| | Boost | `bost` | [P] |
| | Sharpen (USM) | `usm ` | máx 2; defecto 1,5 |
| | Smooth | `smth` | 0…10, paso 0,1 |
| Transform | Crop and Straighten | `crop` | enderezar −10°…10°, paso 0,1; proporciones Freeform, Original, Golden, Square, Wallpaper y Custom |
| | Resize | — | Quick Fit, Scale…, Crop…, Expand Edges… y Stretch. Avisos: "too large for cropping" y "too small for expanding" |
| | Rotate and Flip | — | 90° a la derecha, 180°, voltear H y voltear V |
| Misc | Curves | `crvs` | curvas RGB y por canal (campo `rgb` + puntos) [I] |
| | Adjust RGB | `rgbm` [I] | campos r, g y b |
| | Brightness | `brig` | −100…100, paso 1 |
| | Fix Red-Eye | `reye` | círculo ajustable sobre la pupila; se toca para aplicar |
| | Blur | `blrg` | 0…40, paso 0,1 |
| | Paint Mask / Selection | — | ver B3 |
| | Add Background Color | `asbg` | color; solo si la imagen tiene transparencia |
| | History Paint | — | elegir una etapa del historial y pintarla de vuelta con pincel |
| | Picture Location | — | ver el geotag en un mapa externo o **eliminarlo** |

### B2.2 Menú **Effect** (`PicSayUI.ai`)
| Sección | Herramientas (FourCC · parámetros verificados) |
|---|---|
| Effect | Object Effects* · Marker (`pntb`, blend 25) · **Insert Picture** (3 pasos: recortar, colocar/escalar/rotar, recortar silueta con borrador/pincel; se puede guardar el recorte en el álbum "Cutouts" como `cutout-N.png`) · **Pop Color** (`gray` + Gray Brush / Color Brush) |
| Distort | **Warp Brush** (`warp`) · Spherize (`sphr`) · Twirl (`twrl`, −90…90, paso 1) · Bulge (`bulg`) · **Circle Splash** (`cspl`) · Stretch (`strc`) · **Vertical Stretch** (`strv`) · **Horizontal Stretch** (`strh`) · **Mirror** (`mirr`, campo `rightToLeft`) |
| Color | Hue (`hue `, −180…180°, defecto 90) · Colorize (`colr`) · **Grayscale** (`gray`, 0…360, paso 1) · **Black & White** (`bkwt`, umbral 0…255) · **Duotone** (`duot`: colorDark, colorLight, contrast) · Rainbow (`rnbw`) · **Blend with Color** (`mix `, 50 %, modo de mezcla) |
| Artistic | **Comic Print** (`cmcp`, punto 4…30, defecto 7) · **Posterize** (`post`, 2…20 colores, defecto 4) · **Pop Art** (`popa`, contraste 0,5) · **Pencil Sketch** (`pncl`, saturación) · **Neon** (`neon`, color y contraste) |
| Photo effects | **Lomo** (`lomo`) · **Cross Process** (`crsp`) · **Gritty** · **Fading Colors** (`cfad`) · **Film Grain** (`fgrn`, textura `grain.jpg`) · **Red Scale** · **Faux HDR** (defecto 0,6) · Sepia (`spia`) · Invert (`invt`) · **Tilt-shift** (`blrl`, desenfoque lineal con ángulo) · **Soft Glow** (`sglw`) |
| Filter & Frames | **Vignette** (`vgnt`) · **Focal Blur** (`blrf`: centro, radio 0,5, falloff 0,5) · **Top Lens Filter** (`lfgr`, 0,75) · **Edge Lens Filter** (`lfed`, 0,75) · Spotlight (`spot`) · **Instant** (`inst`, marco tipo foto instantánea, −10…10) · **Film** (`film`, marco de película) · **Rounded Corners** (`rndc`, 0,75) · **Solid Border** (`bsld`, 0…80) · **Matte** (`matt`, 0,75) |
| Misc | Pixelize (`pixl`) · **X-ray** (`xray`) · Dizziness (`ofoc`, −30…30) · **Heat Map** (`heat`, paleta `heatmap.png`) |

\* Object Effects solo aparece con un objeto seleccionado.

### B2.3 **Object Effects** (`PicSayUI.ar`), aplicados a un título, globo o sticker
- Objeto: **Circular Wrap** (curvar un título, −360°…360°, paso 0,1) · **Blend with Background** (rasterizar; tras aplicarlo ya no se puede editar) · **Erase Parts** (borrador y Restore Brush, modos Normal, Add, Multiply y Replace) · **Shadow** (`dshd`: blur, offset, color, opacidad 0,75) · **Glow** (`dshd` con modo de mezcla distinto).
- Color: Hue, Duotone, Invert, Exposure/Contrast, Saturation, Brightness, Adjust RGB, Sepia, Grayscale y Black & White.

### B2.4 Transformación avanzada del objeto (`PicSayUI.Filters.ek`)
Stretch to Fit · Scale to Fill · Mirror · Scale & Rotate · **Perspective Tool** · Show/Hide Stickers (para ver el fondo mientras se transforma).

## B3. Máscaras y selecciones [V]
"Paint Mask / Selection" abre dos modos:
- **Mask:** se borra la máscara roja para desproteger zonas y el efecto se aplica solo a lo no protegido. Con el pincel rojo se vuelve a proteger.
- **Select:** se pinta una selección verde y el efecto se aplica solo a lo seleccionado. Con el borrador se quita selección.

Se puede **invertir la máscara** y **difuminarla** (Blur mask). Después se elige el efecto de una lista reducida (`PicSayUI.aq`): ajustes, color, artísticos, foto y misc, **más "Make Transparent"** (`mix ` con modo `clear`: borrar a transparencia). Distorsiones y marcos no están en esa lista.

## B4. Pinceles [V]
Brush, Eraser, Restore Brush, Color Brush, Gray Brush y Warp Brush. Parámetros: Diameter (tamaño), Hardness, Softness, Strength y opacidad.

Opciones (`PicSayUI.z`):
- "Pressure affects brush size"
- "Pressure affects brush strength"
- "Brush size is relative to screen"

Hay un selector de color que toma muestras de la imagen ("Touch the picture to sample a color") y un muestreo de balance de blancos ("select an area that is neutral gray or white").

## B5. Texto, fuentes, globos y stickers en Pro [V salvo indicación]
- **Títulos multilínea** (en Lite estaban bloqueados) y **guías magnéticas** para alinear títulos (`pref_titleguides`).
- Hay cadenas para gestionar estilos propios (Save / Reorder / Delete) y medidas de **Shadow X/Y, Outline y Outline2**, pero no se encontró referencia directa en el código [P]: puede que se carguen de forma indirecta.
- **Fuentes del usuario:** carpeta `Fonts` en el almacenamiento (`.ttf`/`.otf`), con un botón para crearla. Listas **Default Fonts**, **Installed Fonts** (las del sistema, en `fonts_system.lst`) y **Custom Font Files**, más el aviso de fuentes demasiado complejas.
- Los **globos** son los mismos 6 tipos que en Lite, con cola independiente.
- **Stickers:** vectoriales con colores, estilos de forma y línea y Outline Size. "Sticker Blend" y "Erase Parts" funcionan sobre stickers. Al **fijar** un sticker en el fondo se avisa de que deja de ser editable.

## B6. Documento, importación y exportación en Pro [V]
- **Nuevo documento en blanco:** ancho, alto y color (`pref_doc_width`, `pref_doc_height`, `pref_doc_color`).
- **Tamaño de importación:** Fast, Normal y **Maximum** ("Highest quality"). Preferencia "Always ask", "Only ask when larger than Maximum" o "Always use Maximum". Si se supera el máximo, aviso "picture scaled to…".
- **Historial:** la primera etapa es "Picture Opened"; History Paint reutiliza las etapas del historial.
- **Exportar:** JPEG Very High, High y Medium Quality, **PNG** y "Base upon original Picture". Para las imágenes con transparencia se pregunta un formato aparte ("Export Format"). Las constantes asociadas son **90 / 60 / 30 / 0** [V valores, I semántica]. Hay confirmación de sobrescritura y un "In Album" para guardar en el álbum PicSay.
- **Transparencia:** se conserva (Make Transparent, Add Background Color y exportación PNG).
- **Favoritos** sin límite de 8 documentos [I: la restricción solo existe en Lite].

## B7. Pendientes adicionales de validación (Pro)
1. Algoritmos de Boost, Gritty, Red Scale, Faux HDR, Pop Art, Neon, X-ray, Circle Splash, Lomo y Cross Process. Se conocen los nombres de campos y valores por defecto, pero el cálculo está en código nativo o en shaders GLSL: se reimplementarán con algoritmos propios equivalentes.
2. Curvas: interpolación exacta (spline cúbico o monótono).
3. Warp Brush y Perspective Tool: modelo de deformación.
4. Cadenas presentes sin uso localizado: `filter_clone` (Clone), `brush_warp`, `extrasize*` y `extra0–2`. Se tratarán como **[P]**. Clone se implementará solo si se confirma en ejecución, o como mejora separada.
5. Resolución real del preset Maximum y correspondencia exacta entre 90/60/30 y la calidad JPEG.

## B8. Matriz de equivalencia — ampliación por Pro

La matriz de la sección 5 se mantiene. En ella, las filas marcadas **Pro** pasan a **O‑Pro (verificado en Pro 1.8.0.5)**: multilínea (31), fuentes importadas (33), PNG transparente (54) y favoritos (50). Se añaden estas filas:

| # | Función | Evidencia | Implementación nueva |
|---|---|---|---|
| 60 | Menú Adjust separado de Effect | V | `:feature:editor` · hoja de herramientas con 2 menús |
| 61 | Boost, Sharpen (USM) y Smooth | V/P | `ToneOps` · convolución separable y unsharp mask |
| 62 | Curves y Adjust RGB | V/P | `CurvesOp` (spline monótono + LUT) y `RgbOp` |
| 63 | Blur (0–40) | V | `GaussianBlurOp` (box/stack blur multipaso o `RenderEffect` en API 31+) |
| 64 | Fix Red-Eye | V | `RedEyeOp` (círculo + desaturación del rojo) |
| 65 | Add Background Color y Make Transparent | V | `BackgroundOp` y `ClearOp` |
| 66 | History Paint | V | Pincel que pinta desde una etapa renderizada del historial |
| 67 | Ver y eliminar geotag | V | `ExifInterface`; la vista en mapa abre un Intent `geo:` (sin red propia) |
| 68 | Máscara roja y selección verde, invertir y difuminar | V | `MaskLayer` (ALPHA_8) + blur |
| 69 | Insert Picture / Cut Out (3 pasos) y guardar recortes | V | `CompositeOp` + álbum interno "Cutouts" |
| 70 | Pop Color | V | `PopColorOp` (gris + pincel de color) |
| 71 | Warp Brush | V/P | `WarpBrush` (campo de desplazamiento) |
| 72 | Circle Splash, estiramiento vertical y horizontal y Mirror | V/P | `DistortOp` (tipos adicionales) |
| 73 | Grayscale, Black & White, Duotone y Blend with Color | V | `ColorOps` |
| 74 | Comic Print, Posterize, Pop Art, Pencil Sketch y Neon | V/P | `ArtisticOps` (texturas propias, no las originales) |
| 75 | Lomo, Cross Process, Gritty, Fading Colors, Film Grain, Red Scale, Faux HDR, Tilt-shift y Soft Glow | V/P | `PhotoOps` |
| 76 | Vignette, Focal Blur, filtros de lente superior y de borde, Instant, Film, Rounded Corners, Solid Border y Matte | V/P | `FrameOps` |
| 77 | X-ray y Heat Map | V/P | `MiscOps` (paleta propia) |
| 78 | Circular Wrap de títulos | V | `TextOnArc` (`Path` + `drawTextOnPath`) |
| 79 | Sombra y brillo de objeto y Blend with Background | V | `ObjectEffects` |
| 80 | Erase Parts y Restore Brush con modos de mezcla Normal, Add, Multiply y Replace | V | `ObjectMask` + `BlendMode` |
| 81 | Perspective Tool, Stretch to Fit, Scale to Fill y Mirror del objeto | V | `Matrix.setPolyToPoly` |
| 82 | Pincel sensible a la presión y tamaño relativo a la pantalla | V | `MotionEvent.getPressure`, stylus |
| 83 | Cuentagotas y balance de blancos por muestra | V | `ColorSampler` y `WhiteBalanceOp` |
| 84 | Guías magnéticas para títulos | V | Snap a centro y tercios |
| 85 | Fuentes en carpeta del usuario + instaladas + por defecto | V | Importación SAF + `Typeface` del sistema |
| 86 | Lienzo nuevo con tamaño y color | V | Diálogo |
| 87 | Preset de importación Maximum | V | Sin límite artificial; se reduce solo por memoria |
| 88 | Formatos JPEG Very High/High/Medium, PNG y "según el original" | V | `Bitmap.compress` / codificador JPEG |
| 89 | Exponer documentos a otras apps (`GET_CONTENT`) | V | Opcional: `DocumentsProvider` |
| 90 | Catálogo de stickers | V (descargado) | **Set propio original, incluido en la app y offline** |
| 91 | Clone | P | Pendiente de confirmar; si no, mejora separada |

## B9. Impacto en el plan

- La aplicación objetivo crece de **~25 a ~75 herramientas**. El núcleo (documento no destructivo, objetos, globos y textos) es el mismo en ambas ediciones, lo que valida la arquitectura prevista.
- El motor de filtros debe diseñarse desde el principio como un **grafo de operaciones con máscara**: imagen → operación → mezcla con máscara. Así funcionan Lite y Pro, y es la clave de Mask/Select, History Paint y Object Effects.
- Propuesta de orden: (1) núcleo, documento, historial e importación/exportación; (2) transformaciones y ajustes; (3) texto, globos y stickers; (4) dibujo y máscaras; (5) efectos de Lite; (6) efectos de Pro por familias; (7) composición, Warp y Perspective; (8) mejoras modernas.
