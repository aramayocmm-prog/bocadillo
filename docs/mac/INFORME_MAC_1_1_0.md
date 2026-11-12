# MAC Editor para MEMES 1.1.0 — Informe técnico

| Dato | Valor |
|---|---|
| Versión | **1.1.0**, versionCode **14** (antes 1.0.0 / 13) |
| Paquete | `app.bocadillo` (APK de depuración: `app.bocadillo.debug`); **sin cambios** |
| Respaldo previo | rama `backup-1.0.0-before-1.1` (commit `6a23f32`, la 1.0.0 entregada) |
| Registro de cambios | `git log backup-1.0.0-before-1.1..main` |
| Firma | Depuración: la misma clave del repositorio que la 1.0.0. Se instala encima sin desinstalar |

## 0. Dónde se probó (léase primero)

- **Pixel 10 Pro / Android 17: NO PROBADO.** No hay un dispositivo físico en este entorno. La aprobación final corresponde al propietario en su teléfono.
- **Emulador:** las pruebas instrumentadas se ejecutaron en un emulador Android 14 (API 34, perfil Pixel 6, 1080 × 2400), en GitHub Actions, con la APK de **depuración**.
- **Ejecución final:** la del commit `4eaa8f5`, el mismo código de la APK entregada; aprobó **23 de 23** ejecuciones.
- **Pruebas en la JVM:** las del núcleo se ejecutaron en este entorno: **99 de 99**.
- **Tiempos:** los del emulador **no representan** al Pixel 10 Pro. El emulador comparte una CPU de servidor y la APK de depuración es más lenta que una de publicación. Los números sirven para comparar entre versiones y casos, no como promesa del tiempo en el teléfono.

## 1. Corrección 1 — Retraso del trazo

**Problema.** El trazo se dibujaba detrás del dedo.

**Causa (medida en el código de la 1.0).** En cada movimiento del dedo, un hilo de fondo hacía todo esto:

1. copiaba la vista previa completa (hasta 2048 × 1536 = 3,1 Mpx);
2. **repintaba el trazo entero desde el primer punto**;
3. copiaba la imagen entera a un Bitmap y la subía completa a la GPU (12,5 MB).

El coste crecía con la longitud del trazo. Medido en la JVM, solo la parte de pintar: 20,9 ms con 50 puntos y 107,8 ms con 590 puntos. A eso se sumaban las copias y la subida. Además, el resultado llegaba uno o más cuadros después, porque viajaba por un canal a otro hilo.

**Solución.**

| Pieza | Archivo | Qué hace |
|---|---|---|
| Trazo incremental | `core/.../paint/IncrementalStroke.kt` (nuevo) | Solo procesa el segmento nuevo. Un segmento de la curva solo depende de 4 puntos, así que, cuando llega el punto siguiente, se pinta una vez y no se recalcula. Los 1–2 últimos segmentos se recalculan en una ventana pequeña |
| Mismos números que el resultado final | `core/.../paint/Strokes.kt` | La vista en vivo y el render final usan el mismo muestreo y la misma función de dab y de mezcla (`Blender`) |
| Dab más rápido, mismos valores | `Strokes.kt` | Recorre solo las columnas que alcanza el círculo, rellena el núcleo sólido sin raíz cuadrada y salta los píxeles que ya están al máximo |
| Subida por mosaicos | `app/.../render/LiveTiles.kt` (nuevo) | Mosaicos de 256 px con borde de 1 px, dibujados sin antialias para que no haya costuras. Solo se suben a la GPU los mosaicos que cambiaron |
| Latencia mínima sin bloquear | `app/.../render/LivePump.kt` (nuevo) | Mientras cada actualización cuesta ≤ 4 ms, se hace **dentro del propio evento táctil**, y el trazo se ve en ese mismo cuadro. Si cuesta más (pincel muy grueso, dedo rápido), las siguientes van a un hilo de fondo, una a la vez, para no bloquear el hilo principal. La del dedo levantado nunca se descarta |
| Sin salto al soltar | `EditorViewModel.kt`, `EditorScreen.kt`, `EditorCanvas.kt` | Los mosaicos se retiran solo cuando el render definitivo, que contiene el trazo, **ya está en pantalla** |

Se aplica a Pintar, Marcador, Borrador, Pincel de historial y a la máscara de efectos. En la máscara, el difuminado se recalcula en una ventana de 3 × radio.

**Pruebas y resultados.**

- **JVM, igualdad exacta** (`IncrementalStrokeTest`): para trazos lentos, rápidos, curva cerrada, zigzag, por los bordes y largos (600 puntos), con 4 pinceles (fino duro, suave con presión, marcador, borrador cuadrado), la cobertura en vivo es **idéntica bit a bit** a la final en cada paso, y también lo son los píxeles. **Aprobado.**
- **JVM, coste por movimiento** con la vista previa de 2048 × 1536:
  - trazo largo: 0,56 ms al principio y 0,46 ms al final; no crece con la longitud;
  - caso extremo, pincel del 12 % con 9 puntos muy separados: 15,6 ms por movimiento (por eso existe `LivePump`).
- **Emulador, fidelidad** (`Mac11Test#liveStrokeLatencyAndFidelity`, foto de 4000 × 3000): en 5 trazos (lento fino, rápido grueso, curva cerrada, zigzag, largo de 301 puntos), la imagen con el dedo aún apoyado comparada con la imagen definitiva tras soltar da **0 píxeles distintos de 15 728 640**. **Aprobado.**
- **Emulador, tiempo de cálculo por actualización** (APK de depuración):

  | Trazo | Mediana | p95 | Máx | En segundo plano |
  |---|---|---|---|---|
  | Lento fino (0,8 %) | 3,3 ms | 31,9 ms | 63,2 ms | 59 de 122 |
  | Curva cerrada (3 %) | 23,3 ms | 68,2 ms | 112,6 ms | 59 de 60 |
  | Largo (5 %) | 25,9 ms | 81,8 ms | 147,2 ms | 155 de 156 |
  | Zigzag (2 %, puntos separados) | 113,5 ms | 210,1 ms | 210,1 ms | 6 de 7 |
  | Rápido grueso (12 %, 9 puntos) | 78,9 ms | 433,5 ms | 433,5 ms | 2 de 3 |
  | Sesión de 60 trazos seguidos | 69,9 ms | 329,0 ms | 445,1 ms | — |

  - **Cuándo se ve el cambio.** Las actualizaciones «en segundo plano» no bloquean el hilo principal: su resultado se ve en el cuadro siguiente a terminar.
  - **Variación entre ejecuciones.** En otra ejecución del mismo emulador, la mediana del trazo lento fue 0,82 ms.
  - **Memoria.** En la sesión larga, el heap pasó de 58 MB a 124 MB, sin errores.
- **Estadísticas de cuadros (`dumpsys gfxinfo`): no obtenidas.** Cada prueba corre en un proceso que ya terminó cuando se consultan.
- **Deshacer y rehacer** después de los trazos: **aprobado**.
- **Vídeo:** `trazo_en_vivo.mp4`.

**Limitación.** Con pinceles muy gruesos y movimientos muy rápidos, el emulador tarda decenas o cientos de ms por actualización. En el teléfono se espera bastante menos, pero **no está medido**. Si en el Pixel se nota retraso con pinceles grandes, la siguiente mejora sería la APK de publicación, sin depuración y con R8, que suele ser varias veces más rápida en este tipo de bucles. Queda como recomendación, no como hecho comprobado.

## 2. Corrección 2 — Asa azul inferior derecha (Colocar imagen)

**Situación previa.** La capa de objetos ya distinguía toque y arrastre con el umbral táctil de Android (`touchSlop`), pero en Colocar imagen el toque sobre el asa no hacía nada.

**Solución.**

- `ObjectLayer.kt`: `onHandleTap` informa de qué esquina se tocó.
- `EditorScreen.kt` y `EditorViewModel.toggleInsertPerspective()`:
  - un **toque** en el asa inferior derecha (el círculo azul, o el rombo de esa esquina) alterna entre Tamaño y Perspectiva, sin modificar la imagen ni añadir un paso de deshacer;
  - un **arrastre** aplica la transformación del modo activo;
  - tocar los otros rombos no cambia de modo.
- Se conservan las asas redondas, los rombos y las cinco opciones del menú ⋮.

**Prueba** (`handleTapTogglesSizeAndPerspective`). Comprueba:

- el toque pasa a perspectiva y la transformación es idéntica;
- un toque con un temblor de 3 px vuelve a tamaño;
- el rombo superior izquierdo no alterna;
- arrastrar un rombo cambia la perspectiva y mantiene el modo;
- al volver a tamaño, la perspectiva hecha se conserva;
- arrastrar el círculo escala o gira;
- las 5 opciones de ⋮ están presentes.

Resultado: **aprobado**. Vídeo: `asa_tamano_perspectiva.mp4`.

## 3. Corrección 3 — Silueta (Cut Out)

**Causas.**

1. **Retraso.** En cada movimiento se volvía a recortar y escalar la foto, se recalculaba la máscara con **todos** los trazos anteriores y se creaba y subía un Bitmap nuevo.
2. **Precisión.**
   - La máscara tenía como máximo 1024 px de lado, poco para pelo y bordes finos, y eso también limitaba la exportación.
   - La capa de Silueta ignoraba las muestras táctiles intermedias, así que en movimientos rápidos y curvas cerradas el trazo se aproximaba.
   - Se revisó la cadena de coordenadas pantalla → lienzo → objeto → máscara (inversa afín y luego homografía). Es coherente con el dibujo: los mismos vértices en ambos sentidos.

**Solución.**

- Una sesión conserva la foto recortada, la máscara asentada y el resultado. El trazo en curso solo recalcula su zona: `IncrementalStroke`, con los mismos números que la exportación.
- Mosaicos y `LivePump`, como en el trazo.
- Máscara de hasta **2048 px**: `Cutout.MAX_SIDE`. Es compatible con los proyectos de la 1.0, porque los trazos están normalizados.
- Se usan todas las muestras táctiles.

**Prueba** (`cutOutUnderTheFinger`). Con imagen girada 13,7°, perspectiva hecha arrastrando un rombo y lienzo ampliado ×2 con dos dedos:

- se localiza en la **captura de pantalla** un marcador azul de la foto;
- se toca exactamente ahí con el borrador;
- en el píxel de la imagen correspondiente, alfa = **0** (borrado bajo el dedo); el otro marcador conserva alfa = **255**;
- hay borrado, restauración, deshacer y rehacer;
- lo que se ve, comparado con la exportación, da **0 píxeles distintos de 540 000**.

Resultado: **aprobado**. Tiempo de cálculo en el emulador: mediana 10,4 ms y p95 57,3 ms, con 5 de 10 actualizaciones en segundo plano. Vídeo: `silueta_borrador.mp4`.

**Limitaciones.**

- Con estiramiento no uniforme o perspectiva fuerte, la punta redonda se ve ligeramente elíptica en pantalla. La posición sí es exacta.
- La presión solo se usa con lápiz (stylus) y no está probada.

## 4. Corrección 4 — Fuentes

**Restricción de partida.** El repositorio de GitHub y su APK son **públicos**. Subir allí fuentes sin permiso de redistribución sería distribuirlas.

**Revisión de los 247 archivos.** Los 247 se leen correctamente: el lector de la app (`FontMeta`) y fontTools coinciden en familia y estilo en los 247.

| Estado final | Archivos | Dónde están |
|---|---|---|
| 1. Incorporada en la APK | 19 | APK publicada y APK personal (SIL OFL 1.1 / dominio público) |
| 2. Incorporada solo en la APK personal | **95** (54 familias) | `MAC_Editor_para_MEMES-1.1.0-personal.apk`: solo para el propietario, **no publicada** |
| 3. Requiere autorización del autor para empaquetarla | **129** | Instalación local en un paso: Más fuentes → «Importar fuentes…» → elegir el ZIP original |
| 4. Duplicado (OTF igual que un TTF) | 4 | Disponible la versión TTF |

- **Grupo 3: Zetafonts (112 archivos).** Su EULA, incluido en cada fuente, permite instalarlas y usarlas en tus equipos para uso personal. Pero dice que la licencia «doesn't apply to ... software embedding uses» y prohíbe distribuirlas «included as part of your own product».
- **Grupo 3: fsType (17 archivos).** La propia fuente marca `fsType` 2 / 258, «Restricted License embedding».
- **Grupo 2.** Uso personal, demo o sin licencia declarada, **sin prohibición expresa** de incrustarlas. No se excluyeron por carecer de metadatos.
- **Inventario:** archivo por archivo, con motivo, en `Inventario_Fuentes_MAC_1.1.0.csv`.

**Cambios en la app.**

- **`FontRepository`:**
  - lee la biblioteca personal (`assets/fonts/personal/index.json`) como «Mi biblioteca de fuentes»;
  - al importar, salta las fuentes idénticas (por SHA-1) a las que ya trae la APK;
  - agrupa por familia y ordena los estilos por peso.
- **«Más fuentes»:**
  - buscador;
  - familias plegables con «N estilos»;
  - cada nombre en su propia letra, cargada **en segundo plano** con caché, de modo que la lista nunca espera a un archivo al desplazarse;
  - secciones «Archivos de fuentes propios», «Mi biblioteca», «Fuentes incluidas» y «Fuentes del sistema».
- **Fuente del proyecto:** el proyecto guarda la referencia; si falta, avisa al abrir. No hay sustitución silenciosa.
- **Herramienta** `tools/personal_fonts/make_personal_apk.py`: añade las fuentes a una copia de la APK, alineadas y sin comprimir, y la vuelve a firmar (APK Signature Scheme v2) con la misma clave. Instrucciones en `tools/personal_fonts/README.md`.

**Pruebas.**

- `fontCatalogueSearchPickPersist`. **Aprobado.** Comprueba:
  - el buscador filtra;
  - se elige Creato Display Black;
  - el texto y la fuente persisten tras guardar y reabrir, sin aviso.

  La lista se abre en 557 ms y se recorre entera en 849 ms, en el emulador y con 35 fuentes.
- `Mac10FlowsTest#fontsImportPickAndPersist`: importación de un ZIP con informe, duplicados (incluidas las fuentes que ya trae la APK) y errores. **Aprobado.**
- **Mecanismo de APK personal** (`personalLibraryFonts`). **Aprobado.** CI generó con el script una APK personal con 3 fuentes **públicas**. Se comprobó que:
  - se instala **encima** de la normal (misma firma, versionCode 14);
  - las 3 aparecen en «Mi biblioteca» y cargan;
  - se elige una y persiste en el proyecto.
- **No ejecutado:**
  - la carga **en Android** de las 95 fuentes de la APK personal y de las 129 restringidas. No se subieron al servidor de pruebas para no publicarlas. Se verificó su lectura con el mismo código de la app (247/247) y la firma de la APK personal con el verificador del script; falta abrirla en el Pixel;
  - la fluidez del catálogo con ~115 fuentes: medida solo con 35.

## 5. Corrección 5 — Check verde, Atrás y guardado

**Problema.** El ✓ y la flecha ← del editor salían sin preguntar. Guardar la imagen solo era posible desde «Exportar».

**Solución.**

- **Check con palabra.** El ✓ verde lleva una palabra debajo, en todas las barras: «Aplicar» en las herramientas, «Siguiente» / «Insertar» en Insertar imagen y «Finalizar» en el editor (`ClassicComponents.kt`: `CheckWithLabel` y `ConfirmButton`).
- **Diálogo «Terminar la edición»** (`FinishDialogs.kt`): se abre con «Finalizar», o con Atrás si hay cambios pendientes. Opciones:
  - **Guardar imagen**: nombre, formato **PNG** (conserva la transparencia) o **JPG** con calidad de 50 a 100, y ubicación (Galería → Imágenes/MAC Editor para MEMES con MediaStore, o «Elegir ubicación…» con el selector del sistema). Confirma dónde quedó y ofrece «Continuar editando» o «Salir (el proyecto se conserva)»;
  - **Guardar proyecto**: guarda, toma ese estado como punto de guardado y vuelve al inicio;
  - **Continuar editando**;
  - **Salir sin guardar**: pide confirmación y vuelve al último estado guardado. Un proyecto que nunca se guardó se borra; la foto original no se toca.
- **Atrás:**
  - dentro de una herramienta, cancela o retrocede de etapa, como antes; no sale del editor;
  - en el editor, si no hay cambios, sale sin diálogo; si los hay, muestra el diálogo;
  - en el inicio, comportamiento normal de Android.
- **Borradores.** Un proyecto abierto desde una foto es un **borrador** hasta que se guarda (`ProjectRepository.isDraft`). Si se abre y se sale sin cambiar nada, no queda en «Recientes». Los proyectos de la 1.0 no son borradores.

**Prueba** (`finishSaveAndBackNavigation`). **Aprobado.** Comprueba:

- un borrador sin cambios desaparece al salir;
- el diálogo tiene las 4 opciones;
- PNG y JPG se encuentran en la **galería**, en Imágenes/MAC Editor para MEMES, mediante consulta a MediaStore. Firmas de archivo correctas; el PNG mide 900 × 1200, la imagen girada;
- Atrás dentro de una herramienta, con la tecla y con el despachador de gestos, la cierra sin salir del editor y sin diálogo;
- Guardar proyecto lleva al inicio; el proyecto se conserva, deja de ser borrador y su operación persiste;
- Salir sin guardar, con confirmación, devuelve el proyecto al estado guardado;
- salir sin cambios no muestra diálogo.

Vídeo: `navegacion_guardado.mp4`.

**No ejecutado:**

- «Elegir ubicación…»: es el selector de archivos del sistema, que no se automatizó;
- el gesto físico de deslizar desde el borde. Se probó la tecla Atrás y el despachador de Atrás, que es por donde Android entrega ese gesto.

## 6. Corrección 6 — Miniatura del inicio

- La miniatura mide ahora **115 × 86 dp** con una foto 4:3; antes, 44 × 58 dp.
  - El lado largo pasa de 58 a 116 dp, el doble.
  - Conserva la proporción de la foto. Antes se recortaba.
  - Sigue en la esquina superior derecha y sigue abriendo el trabajo reciente.
- Se reduce si la pantalla es pequeña o está en horizontal: como máximo 28 % del ancho y 22 % del alto.
- El logotipo solo baja lo necesario para no quedar debajo. El logotipo, el título, el botón y los colores no se tocaron.
- Prueba `homeThumbnailAboutTwiceAsBig`: **aprobado**.
- Inicio con letra al 100, 130 y 200 %, y en horizontal (`homeVariants`): **aprobado**.

## 7. Compatibilidad y actualización

- **Prueba en el emulador:**
  1. se instaló la 1.0.0, compilada desde la rama de respaldo;
  2. con ella se creó un proyecto, la biblioteca de stickers, fuentes importadas y un proyecto con un título;
  3. se instaló la 1.1.0 encima con `adb install -r` (pasó de versionCode 13 a 14);
  4. `dataSurvivedUpgrade` comprobó proyectos, stickers, fuentes y archivos. **Aprobado.**

  Las preferencias guardadas antes y después están en `results.txt`.
- **Formatos.**
  - Los proyectos 1.0 abren igual. El recorte y la máscara se recalculan con más resolución.
  - Los campos nuevos de proyecto: ninguno.
  - El índice de borradores es un archivo aparte, `drafts.txt`.
- **Android 17:** no probado. `targetSdk` 35, `minSdk` 29.

## 8. Archivos modificados (resumen)

- **Núcleo:**
  - `paint/Strokes.kt`;
  - `paint/IncrementalStroke.kt` (nuevo);
  - `objects/Cutout.kt`;
  - `ops/PaintOps.kt` (`lerp` público).
- **App, editor:**
  - `EditorViewModel.kt`;
  - `EditorScreen.kt`;
  - `EditorCanvas.kt`;
  - `DrawPanels.kt`;
  - `ObjectLayer.kt`;
  - `InsertPictureChrome.kt`;
  - `FinishDialogs.kt` (nuevo);
  - `TitleStyleScreens.kt`;
  - `TextEditorPanel.kt`;
  - `ClassicToolChrome.kt`;
  - `PaintChrome.kt`;
  - `CompactPanel.kt`;
  - `ToolPanels.kt`;
  - `TransformChrome.kt`.
- **App, render:** `LiveTiles.kt` (nuevo), `LivePump.kt` (nuevo), `ObjectRenderer.kt`.
- **App, datos:** `FontRepository.kt`, `ProjectRepository.kt`.
- **App, inicio y UI:**
  - `HomeScreen.kt`;
  - `ui/classic/ClassicComponents.kt`;
  - `values*/strings.xml`.
- **Versión:** `app/build.gradle.kts`.
- **Herramientas:**
  - `tools/personal_fonts/`;
  - `tools/ci-emulator.sh`;
  - `.github/workflows/screens.yml`.
- **Pruebas:**
  - `Mac11Test.kt` (nueva);
  - `TestNav.kt` (nueva);
  - `IncrementalStrokeTest.kt` (nueva);
  - ajustes en las pruebas 1.0. Los proyectos de prueba se marcan como guardados, al salir se elige «Guardar proyecto», y las fuentes del ZIP de prueba ya no son idénticas a las incluidas.

## 9. Registro de pruebas (ejecución final, commit `4eaa8f5`)

| Prueba | Lugar | Resultado |
|---|---|---|
| Núcleo, 99 pruebas (incluye `IncrementalStrokeTest` ×4) | JVM | aprobado |
| `assembleDebug` / `assembleDebugAndroidTest` | CI | aprobado |
| 1.0.0: crear datos (2 pruebas) → instalar 1.1.0 encima → `dataSurvivedUpgrade` | emulador | aprobado |
| `Mac11Test`: trazo en vivo, asa, Silueta, navegación y guardado, catálogo de fuentes, miniatura (6) | emulador | aprobado |
| `homeVariants` con letra 1.0 / 1.3 / 2.0 y en horizontal (4) | emulador | aprobado |
| `Mac10FlowsTest` (3), `ReferenceScreensTest`, `StickerLibraryFlowTest`, `MacFlowsTest` (4) | emulador | aprobado |
| APK personal de prueba instalada encima → `personalLibraryFonts` | emulador | aprobado |
| Pixel 10 Pro / Android 17 | — | **no ejecutado** |
| Carga en Android de las 224 fuentes no públicas | — | **no ejecutado** |
| «Elegir ubicación…» (selector del sistema), presión de lápiz, gesto físico de borde | — | **no ejecutado** |
| Estadísticas de cuadros (`gfxinfo`) | — | **no obtenido** |

Salida completa: `results.txt`. Tiempos y comprobaciones: `mac11_log.txt`.

**Incidencias halladas y corregidas durante las pruebas:**

- `LivePump` dejaba en espera trabajos encolados, incluido el de levantar el dedo, si el siguiente se ejecutaba en línea. Ahora vacía la cola en orden.
- Errores de las propias pruebas: un trazo que empezaba fuera de la foto, una comparación de píxeles sin el redondeo del alfa premultiplicado, y el texto de un encabezado.

## 10. Limitaciones pendientes

- Verificación en el **Pixel 10 Pro con Android 17**, a cargo del propietario. En especial:
  - la sensación de retraso con pinceles gruesos;
  - la carga de las 95 fuentes de la APK personal;
  - la instalación local de las 129 restringidas desde su ZIP.
- Las 129 fuentes con restricción expresa no se empaquetan sin autorización de sus autores.
- La APK personal **no debe compartirse**.
- Los tiempos del emulador no son los del teléfono. No se midió una APK de publicación.
- La «Salida sin guardar» de un proyecto antiguo vuelve al estado del último guardado intencional. Si el proyecto nunca se guardó en la 1.1, ese estado es el del momento en que se abrió en esta sesión.
