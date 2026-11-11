# MAC Editor para MEMES 1.0.0 — Informe técnico

Base: código 0.9.0 (commit `c1ea647`). Antes de tocar nada se creó la rama de respaldo
`backup-0.9.0-before-1.0`, que apunta a ese commit. El registro de cambios es el historial de
Git a partir de ahí (`git log backup-0.9.0-before-1.0..main`).

| Dato | Valor |
|---|---|
| Versión | **1.0.0**, versionCode **13** (la 0.9.0 era el 12) |
| Paquete | `app.bocadillo` (la compilación de depuración usa `app.bocadillo.debug`); **no cambia** |
| Android | 10 a 15 (minSdk 29) |
| Firma de la APK entregada | **Depuración** (clave de depuración del repositorio, `app/debug.keystore`). La misma clave firmó la 0.9.0, por eso se instala encima sin desinstalar. La clave **no** va en el ZIP de código |

## 1. Cómo se verificó

| Prueba | Dónde | Resultado |
|---|---|---|
| Núcleo (modelo, geometría, pincel, máscara) | JVM local, `tools/build-core-offline.sh` | **95 pruebas OK** (3 nuevas, apartado 6) |
| Compilación | GitHub Actions, `assembleDebug` + `assembleDebugAndroidTest` | **BUILD SUCCESSFUL** |
| Pruebas instrumentadas | Emulador Android 14 (perfil Pixel 6) en GitHub Actions | **15 de 15 aprobadas** (apartado 6) |
| Actualización 0.9.0 → 1.0.0 | Mismo emulador: se instala la 0.9.0 compilada desde la rama de respaldo, se crean datos con ella y se instala la 1.0 con `adb install -r` | **Aprobada**: no se perdió nada (apartado 6) |
| Lector de nombres de fuente de la app (`FontMeta`) | JVM local, sobre las 247 fuentes | **247 de 247** leídas; familia y estilo coinciden en las 247 con fontTools |

**No probado:** en un Pixel 10 Pro real (**NO PROBADO**: no hay dispositivo físico disponible en
este entorno). Todo lo que dice «emulador» se hizo en un emulador, no en un teléfono.

## 2. Matriz de cambios

| ID | Archivos principales | Qué hace ahora | Capturas | Prueba | Estado |
|---|---|---|---|---|---|
| 14 Inicio | `home/HomeScreen.kt` (`AppTitle`, página principal), `values*/strings.xml` (`app_title_line2`, `home_tagline_mac`) | Bajo el logotipo (sin modificar), título en dos líneas centradas: «MAC» grande con degradado y «Editor para MEMES» debajo, más pequeño. Cada línea mide su texto y reduce la letra si no cabe, así que nunca se corta ni lleva puntos suspensivos. La columna se desplaza si la pantalla es baja (horizontal, letra grande). Abajo, el texto pedido, discreto. Se conservan ABRIR IMAGEN, ☰ y la miniatura | 90_home_font1.0 / 1.3 / 2.0 / landscape | `homeVariants` comprueba que «MAC», «Editor para MEMES» y el botón quedan dentro de la pantalla, en cada variante | **Hecho**; pruebas aprobadas (apartado 6) |
| 15 Licencias | `home/HomeScreen.kt` (`CreditsScreen`, ahora una página de inicio como Ajustes), `values*/strings.xml` (`credits_*`) | Mismo camino: ☰ → «Más...» → «Licencias y créditos». Muestra el logotipo, el nombre, la versión y el build **leídos del paquete instalado** (PackageManager), «Marcelo Aramayo» / «Gestor del proyecto», el crédito del logotipo y una nota de independencia (no usa código, gráficos ni stickers de la aplicación que la inspiró ni tiene relación con sus autores). Las licencias de terceros siguen completas en una sección desplegable: fuentes (`CREDITS.txt`), Noto, bibliotecas y textos completos | 92_credits_* | `homeVariants` comprueba el nombre, el cargo y que la versión mostrada es la del paquete | **Hecho**; pruebas aprobadas (apartado 6) |
| 16 Insertar imagen | Núcleo: `Objects.kt` (`crop`, `cutout`), `ObjectJson.kt`, `Cutout.kt` (nuevo), `ObjectGeometry.kt` (`canvasToUnit`, `Homography`), `Strokes.kt` (punta cuadrada). App: `InsertPictureChrome.kt` (nuevo), `EditorViewModel.kt` (`ToolState.InsertPicture`, `InsertStage`), `EditorScreen.kt`, `ObjectLayer.kt` (asas de rombo), `ObjectRenderer.kt` (`processPicture`), `AppSettings.kt` (guía) | Tres pasos antes de insertar (detalle debajo de la tabla). El recorte y la máscara se guardan en el proyecto como datos, no se aplican a la foto: se pueden reabrir y cambiar. La exportación usa el mismo dibujo | 50–65 | `insertPictureCropPlaceCutOut` | **Hecho**; pruebas aprobadas (apartado 6) |
| 17 Trazo en vivo | `Strokes.kt` (`StrokePainter.paintInPlace`), `EditorViewModel.kt` (`strokeLive`, `livePaint`, trabajador de vista previa), `DrawPanels.kt` (`DrawLayer`), `EditorScreen.kt` | El trazo en curso ya no lo dibuja Compose con otro estilo. Mientras el dedo se mueve, la app pinta el trazo con **el mismo código** que el resultado final (`paintInPlace`: mismo color, opacidad, tamaño, dureza, mezcla, remuestreo Catmull-Rom) sobre una copia de la imagen, y la muestra. Al soltar, el render final sustituye a la vista en vivo solo cuando ya está listo, sin salto. Vale para Paint, Marker, Eraser y las máscaras de efectos (el contorno de la máscara usa el trazo en vivo), Restore (History Paint) y el borrador y el pincel restaurador de Cut Out | 70_live_finger_down, 71_after_release; vídeo `liveStrokeEqualsAppliedStroke.mp4` | `liveStrokeEqualsAppliedStroke` compara píxel a píxel la imagen en vivo antes de soltar con la imagen final | **Hecho**; pruebas aprobadas (apartado 6) |
| 18 Fuentes | `TitleStyleScreens.kt` (`TitleStylePanel`, `MoreFontsScreen`), `EditorScreen.kt`, `data/FontRepository.kt` (`importAll`, `ImportReport`, `FontMeta`, índice `files/fonts/index.json`), `assets/fonts/*` (19 fuentes nuevas), `assets/fonts/CREDITS.txt` | Tocar la vista previa de New Title abre el panel clásico **sobre la cuadrícula ABC**: Font ›, Text Size, Alignment (izq./centro/der.), Line Height, Fill Color, Outline Color y «Todas las opciones…» (la pantalla Title Style completa de la 0.9.0). Font abre «More Fonts»: «Custom Font Files» (ayuda, «Importar fuentes…» y las importadas), «Incluidas» y «System Fonts», cada nombre en su propia letra. Importa .ttf, .otf y .zip (varios a la vez): valida la cabecera, limita a 25 MB, detecta duplicados por SHA-1, lee familia y estilo de la tabla `name` y muestra un informe (añadidas, duplicadas, errores con el motivo). Pulsación larga borra una importada. Al abrir un proyecto con una fuente que falta avisa (no la cambia en silencio) | 80, 81, 81b, 82, 83 | `fontsImportPickAndPersist` | **Hecho**; pruebas aprobadas (apartado 6) |

### Insertar imagen, paso a paso

1. **Crop** (de la imagen elegida, antes de insertarla). La imagen se muestra entera con un marco
   de cuatro escuadras en las esquinas, el exterior oscurecido y el tamaño exacto en píxeles en la
   barra («Crop 726 × 486»). El lienzo no se toca: el objeto aún no existe.
2. **Place Picture**. La imagen recortada sobre el lienzo, con el asa azul circular (mover,
   escalar, girar). Barra: ↶, ⋮, «Place Picture 100%», el icono ▶|◀ de alternar modo (separado de
   ⋮) y ✓. Menú ⋮: **Stretch to Fit**, **Scale to Fill**, **Mirror**, **Perspective Tool** ✓,
   **Show Stickers** ✓, Volver a Crop, Rehacer, Guía. «Perspective Tool» cambia a cuatro **rombos
   azules**, uno por esquina, que se mueven por separado. Abajo, un deslizador de opacidad.
3. **Cut Out**. **Eraser** y **Restore Brush** en la barra. El borrador quita píxeles de la imagen
   insertada con una máscara alfa y deja ver el fondo, que no se modifica. El restaurador devuelve
   lo borrado (no pinta). Panel de puntas plegable: 4 tamaños × (4 durezas de círculo + cuadrado),
   la elegida en azul. Un dedo pinta; dos dedos cancelan el trazo en curso y hacen zoom o
   desplazamiento. ↶ ↷ deshacen y rehacen. Con lápiz (stylus) la presión modula el trazo; con el
   dedo la presión es fija, porque en muchos teléfonos el valor del dedo no es fiable. El ✓ de este paso inserta el objeto; antes de eso nada queda en el
   proyecto.

Las guías «Step 1/2/3» aparecen la primera vez y tienen «No volver a mostrar».

Reeditar una imagen insertada vuelve a abrir el mismo flujo con su recorte, su máscara y su
transformación.

**Inferencias (no comprobadas con las capturas):**
- Que ▶|◀ alterna entre asas circulares y rombos. Las capturas lo muestran junto a ambos modos,
  pero no cómo cambia.
- Que «Place Picture 100%» es la opacidad de la imagen.
- Que «Show Stickers» oculta los demás objetos mientras se coloca la imagen.

**Límite:** con perspectiva fuerte, el tamaño del pincel de Cut Out en pantalla es aproximado,
porque la escala cambia de una esquina a otra. La posición sí es exacta: el punto se invierte por
la homografía (prueba `canvasToUnitInvertsRotationFlipAndPerspective`).

## 3. Pendientes de la 0.9.0

| N.º | Pendiente | Qué se hizo | Estado |
|---|---|---|---|
| 1 | Pixel 10 Pro, fuentes grandes, horizontal | Capturas automáticas de inicio, «Más…» y créditos con escala de letra 1.0, 1.3 y 2.0 y en horizontal, comprobando que el título no se sale | Emulador: **aprobado** (escala 1.0, 1.3, 2.0 y horizontal). **Pixel 10 Pro real: NO PROBADO** |
| 2 | Actualización con firma compatible | Prueba automática: 0.9.0 instalada, crea un proyecto y una biblioteca de stickers, se le añade una fuente importada y un archivo, y se instala la 1.0 encima con `adb install -r` (sin desinstalar). `dataSurvivedUpgrade` comprueba que siguen los proyectos (y que se abren), la biblioteca, la fuente y el archivo | **Hecho**; pruebas aprobadas (apartado 6) |
| 3 | Importación de fuentes y stickers | Fuentes: apartado 2, cambio 18. Stickers: la biblioteca y su importación siguen como en la 0.9.0, y `StickerLibraryFlowTest` vuelve a pasar. **Las 32 categorías siguen vacías**: no se añadió ningún gráfico | Fuentes y biblioteca: **aprobado**. Categorías: vacías, como antes |
| 4 | SVG con `style=` en línea | Al aplicar Set Shape Style o Set Line Style, la app quita del atributo `style` las propiedades de relleno y trazo (fill, fill-opacity, stroke, stroke-opacity, stroke-width, stroke-linecap, stroke-linejoin) para que manden los colores elegidos. El resto (opacity, display, fill-rule…) se conserva. Verificado con un arnés JVM sobre la función real | **Hecho**. **Límite:** las reglas dentro de un bloque `<style>` (CSS) no se reescriben |
| 5 | Fidelidad de Resize, Exposure/Contrast, Crop, Title, Export y More | En 1.0 solo cambió Title (panel clásico sobre la cuadrícula, ver la hoja de comparación). Resize, Exposure/Contrast, Crop, Export y More no se modificaron. Sus capturas de regresión (`ReferenceScreensTest`) se repitieron y se revisaron a ojo: mismo aspecto que en la 0.9.0. More ahora se desplaza (apartado 6.3). El SSIM de la hoja es solo orientativo: las fotos, los teléfonos y las barras de estado son distintos | Revisado; sin cambios de fidelidad respecto a 0.9.0, salvo Title y More |
| 6 | Deslizador de Crop | Sigue igual que en la 0.9.0 (endereza). **Su función en la aplicación original no quedó demostrada con ninguna captura**: es una inferencia | Documentado |

## 4. Fuentes del usuario (247)

- Catálogo completo: `Catalogo_Fuentes_MAC_1.0.csv` (archivo, formato, familia, estilo, licencia
  declarada en la fuente, fsType y estado).
- Las 247 se leen sin error: 173 TTF y 74 OTF.

| Estado | Cantidad | Tratamiento |
|---|---|---|
| Incluidas en la APK | **19** | 18 con licencia SIL OFL 1.1 (Bebas Neue, Comfortaa ×3, Creato Display ×14) y 1 de dominio público (Bradley Gratis). Avisos en `CREDITS.txt` y en Licencias y créditos |
| No redistribuibles (trial, demo, uso personal) | 144 | No se empaquetan. El usuario las importa en su teléfono desde More Fonts |
| Sin permiso verificable | 79 | Igual: importables solo localmente |
| No incluidas por otros motivos | 5 | Igual |

- 17 fuentes tienen fsType restrictivo.
- 8 archivos repiten una familia y un estilo que ya están en otro archivo (el importador avisa de
  los duplicados exactos por contenido).

**No verificado:** la carga de las 226 fuentes no incluidas dentro de Android. No se subieron al
servidor de pruebas para no redistribuirlas. Su lectura sí se verificó fuera de Android con el
mismo código de la app (247 de 247). Android puede rechazar alguna con tablas raras. En ese caso
el importador la lista como error con el motivo, sin cerrarse.

## 5. Actualización y respaldo

1. **Antes de actualizar (recomendado):** ☰ → Más… → «Guardar proyecto…» guarda cada proyecto
   como archivo `.bcproj` (con su foto, las imágenes y stickers insertados y las fuentes que usa)
   fuera de la app; «Abrir proyecto…» lo
   recupera. Los proyectos, stickers, fuentes y ajustes viven en la memoria privada de la app y
   una actualización encima no los borra, pero el manifiesto tiene `allowBackup="false"`, así que
   la copia automática de Android no los guarda: si se desinstala, solo quedan los archivos
   guardados con «Guardar proyecto…». Conviene conservar también los archivos de fuentes
   originales (.ttf, .otf, .zip).
2. **Instalar la 1.0.0 encima, sin desinstalar.** Funciona porque las dos APK están firmadas con
   la misma clave de depuración del repositorio. Si se desinstala primero, se pierden los datos.
3. **Si Android dice «conflicto con un paquete existente»,** la APK instalada se firmó con otra
   clave, por ejemplo una compilada en otro equipo. No desinstalar sin exportar antes lo
   necesario (paso 1).
4. **Proyectos guardados con 1.0** (recorte, máscara, perspectiva) se abren en la 0.9.0, pero esa
   versión ignora los campos nuevos y muestra la imagen sin recortar.
5. **Para publicar en una tienda** hará falta una clave de publicación propia, que no está en el
   repositorio ni en el ZIP. Cambiar de la clave de depuración a la de publicación obliga a
   desinstalar una vez: antes, guardar cada proyecto con «Guardar proyecto…» (paso 1).

## 6. Registro de pruebas

**Lugares de ejecución:**
- **JVM**: en este entorno, con `tools/build-core-offline.sh`.
- **Emulador**: Android 14, perfil Pixel 6, 1080 × 2400, en GitHub Actions, con `tools/ci-emulator.sh`.

**Resultados publicados** en la rama `screens-output`: `results.txt` y `crash.txt`, las capturas y los vídeos.

**Ninguna prueba se hizo en un teléfono real.**

### 6.1 Automáticas

| Prueba | Dónde | Qué comprueba | Resultado |
|---|---|---|---|
| Núcleo, 95 pruebas | JVM | Modelo, efectos, pincel, geometría. Nuevas: `canvasToUnitInvertsRotationFlipAndPerspective` (un punto del lienzo vuelve al mismo píxel de la imagen con giro, espejo y perspectiva), `cutoutEraseThenRestore` (borrar y luego restaurar devuelve el alfa) y `insertedPictureCropAndCutoutRoundTrip` (el recorte y la máscara sobreviven a guardar y leer el JSON) | **aprobado** (95/95) |
| `FontMeta` sobre las 247 fuentes | JVM | El lector de nombres de la app obtiene familia y estilo de cada archivo y coinciden con fontTools | **aprobado** (247/247) |
| `stripInlinePaint` | JVM (arnés con la función real) | Con un SVG con `style=` en línea, quita relleno y trazo y conserva `opacity` y `display` | **aprobado** |
| `assembleDebug` y `assembleDebugAndroidTest` | GitHub Actions | Compilan la app y las pruebas | **aprobado** |
| Datos creados con la 0.9.0 | Emulador | La 0.9.0 (compilada desde `backup-0.9.0-before-1.0`) crea un proyecto y una biblioteca de stickers; se añade una fuente importada | **aprobado** |
| `dataSurvivedUpgrade` | Emulador, tras `adb install -r` de la 1.0 encima de la 0.9.0 | Siguen y se abren los proyectos; siguen la biblioteca de stickers, la fuente importada y los archivos de la 0.9.0. Registro: `projects=1 stickers=2 fonts=[Comfortaa Bold]` | **aprobado** |
| `fontsImportPickAndPersist` | Emulador (app en español) | Importa un ZIP con dos fuentes válidas, una copia duplicada, un archivo dañado, un .txt y basura de `__MACOSX`. Informe: 2 añadidas (familia y estilo leídos), 1 duplicada, 1 error con su motivo. Reimportar no añade nada. Panel Title Style con sus filas; More Fonts con «Custom Font Files» y «System Fonts»; elige la fuente importada; el proyecto la guarda y la sigue mostrando al reabrir, aunque se borre del teléfono | **aprobado** |
| `liveStrokeEqualsAppliedStroke` | Emulador, con vídeo | Trazo rojo suave (dureza 20 %, opacidad 80 %) con el dedo simulado. Antes de soltar ya hay imagen en vivo; se compara píxel a píxel con la imagen final tras soltar. Medido: **0 píxeles distintos de 1 080 000** | **aprobado** |
| `homeVariants` con escala de letra 1.0, 1.3 y 2.0, y en horizontal | Emulador | «MAC», «Editor para MEMES» y ABRIR IMAGEN dentro de la pantalla. ☰ → Más… → Licencias y créditos llega a la pantalla (en horizontal, desplazando la lista), con «Marcelo Aramayo», «Gestor del proyecto» y la versión del paquete | **aprobado** (4/4) |
| `insertPictureCropPlaceCutOut` | Emulador, dos veces (una con vídeo) | Ver el detalle debajo | **aprobado** (2/2) |
| `ReferenceScreensTest`, `StickerLibraryFlowTest`, `MacFlowsTest` (4 pruebas) | Emulador | Regresión de las funciones de 0.8 y 0.9: pantallas, biblioteca de stickers, imagen grande, inicio y pincel, sesgado y perspectiva con exportación, SVG Shape y Line Style | **aprobado** (6/6) |

**Detalle de `insertPictureCropPlaceCutOut`:**

1. **Crop.**
   - Muestra 900 × 600.
   - Se arrastran dos esquinas y el tamaño pasa a 726 × 486.
   - Mientras se recorta, ningún objeto nuevo entra en el proyecto.
2. **Place Picture.**
   - Stretch to Fit y Scale to Fill cambian la transformación como se espera.
   - Mirror activa el espejo.
   - Show Stickers alterna la visibilidad.
   - Deshacer quita los pasos en orden inverso; rehacer los repone.
   - Perspective Tool muestra los rombos. Arrastrar uno crea perspectiva.
   - ▶|◀ vuelve a los círculos.
3. **Cut Out.**
   - Panel del borrador; se elige la punta del 8 % y la dureza del 40 %.
   - Se borra un trazo y se restaura una parte: quedan 2 trazos.
   - Deshacer deja 1; rehacer, 2.
4. **Proyecto guardado.** Se comprueba que el proyecto conserva:
   - el recorte;
   - los 2 trazos;
   - el espejo;
   - la perspectiva.
5. **Máscara.** La imagen procesada tiene:
   - zona borrada (alfa 0);
   - zona intacta (alfa 255);
   - bordes semitransparentes (alfa entre 20 y 235).
6. **Coincidencia con la exportación.** La imagen que se ve en pantalla es idéntica (`sameAs`) a la que usa la exportación.
7. **Final.** Se reabre el proyecto y se exporta a la galería.

### 6.2 No ejecutado

| Prueba | Motivo |
|---|---|
| Pixel 10 Pro real | No hay dispositivo físico disponible: **NO PROBADO** |
| Cualquier prueba manual en un teléfono | Ídem. Todo lo anterior es automático y en emulador |
| Presión con lápiz | El emulador no simula un lápiz. El código usa la presión solo si el puntero es un stylus; **no está verificado** |
| Zoom con dos dedos en Cut Out | No automatizado en el emulador. La conversión de coordenadas con giro, espejo y perspectiva sí está probada en la JVM; el zoom del lienzo usa la misma conversión, pero **no hay prueba de extremo a extremo con zoom** |
| Reeditar una imagen ya insertada (volver a Crop, Place y Cut Out desde el objeto) | Implementado (`editPicture`), **sin prueba automática** |
| Comparar píxel a píxel la imagen exportada a la galería | La prueba verifica que la exportación termina, y que la imagen procesada que ve el usuario es la misma que entra en la exportación; no lee el archivo final de la galería |
| Carga en Android de las 226 fuentes del usuario no incluidas | No se subieron al servidor de pruebas para no redistribuirlas (apartado 4) |

### 6.3 Incidencias encontradas y corregidas durante las pruebas

- **«Más…» no se desplazaba.** En horizontal, «Licencias y créditos» quedaba fuera de la pantalla y no se podía alcanzar. Ahora la lista se desplaza.
- **Créditos en horizontal.** Era un diálogo y en horizontal no cubría la pantalla. Ahora es una página como Ajustes.
- **Error de hilo al exportar.** Tras exportar a la galería, el aviso de «Guardado» podía tocar la interfaz desde un hilo de fondo (`CalledFromWrongThreadException`, visto una vez en el emulador). Ahora el resultado se publica en el hilo principal.
- **Menú ⋮ de Place Picture.** La marca ✓ iba dentro del texto de la opción. Ahora es un icono aparte, alineado.
- **«Todas las opciones…»** se mostraba con puntos suspensivos dobles.
- **Barras clásicas.** Con letra grande, los títulos largos («Licencias y créditos») se cortaban. Ahora pasan a dos líneas.
- **Botón ABRIR IMAGEN.** En horizontal ocupaba casi todo el ancho. Ahora tiene un máximo de 420 dp.

**Estabilidad del emulador de CI.** El emulador de GitHub Actions se cerraba a veces solo, con un error de contexto OpenGL del renderizador SwiftShader del anfitrión. Pasaba en pruebas distintas cada vez, también en las de la 0.9.0. Se cambió al renderizador ANGLE (`swangle_indirect`) y se añadió un reinicio automático. No se observó ningún cierre de la app en `crash.txt`.

## 7. Entregables

| Id | Archivo | Contenido |
|---|---|---|
| A | `MAC_Editor_para_MEMES-1.0.0-debug.apk` | versionName 1.0.0, versionCode 13, firma de **depuración** (clave del repositorio) |
| B | `MAC_Editor_para_MEMES-1.0.0-fuente.zip` | `git archive` del commit entregado, **sin** `app/debug.keystore`; sin `build/`, `.gradle/` ni contraseñas |
| C | este informe (`docs/mac/INFORME_MAC_1_0.md`) | |
| D | `Capturas_MAC_1.0.zip` y `Comparacion_PicSay_vs_MAC_Editor_1.0.jpg` | Capturas del emulador de cada pantalla modificada (50–65 Insertar imagen, 70–71 trazo, 80–83 fuentes, 90–92 inicio, Más… y créditos en 4 variantes) y hoja de comparación con las referencias |
| E | `Video_CutOut_Insertar_imagen.mp4`, `Video_Trazo_en_vivo.mp4` | Grabaciones de pantalla del emulador durante las pruebas |
| F | apartado 6 de este informe y `results.txt` | Salida completa de las pruebas instrumentadas |
| G | apartado 5 | |
| H | `Catalogo_Fuentes_MAC_1.0.csv` | Las 247 fuentes con familia, estilo, licencia declarada, fsType y estado |

## 8. Limitaciones conocidas

- Pixel 10 Pro real: **NO PROBADO**.
- La carga en Android de las 226 fuentes no incluidas: **no verificada** (apartado 4).
- Los efectos costosos (Twirl, desenfoques) con pincel de máscara: el contorno de la máscara se
  ve en vivo, pero el efecto completo se calcula al soltar, como en la 0.9.0.
- Perspectiva extrema: el tamaño del pincel de Cut Out es aproximado (apartado 2).
- Bloques `<style>` CSS en SVG: no se reescriben (apartado 3).
- Las 32 categorías de stickers siguen vacías.
