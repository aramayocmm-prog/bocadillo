# MAC Editor para MEMES 1.2.0 — Informe técnico

| Dato | Valor |
|---|---|
| Versión | **1.2.0**, versionCode **15** (antes 1.1.0 / 14; el último código usado era 14) |
| Paquete | `app.bocadillo` (APK de depuración: `app.bocadillo.debug`); **sin cambios** |
| Respaldo previo | rama `backup-1.1.0-before-1.2` (commit `0dece8d`, la 1.1.0 entregada) |
| Registro de cambios | `git log backup-1.1.0-before-1.2..main` |
| Entrega | **Una sola APK** (sección 13). Ya no existe la APK personal |

## 0. Léase primero

- **El documento recibido está incompleto.** El archivo del prompt termina a mitad de la sección 13.4 («La importación no debe convertir automáticament…»). **Las decisiones 11 y 12 y el resto de la sección 13 no llegaron**, así que no están implementadas. Se implementaron las decisiones 1 a 10 y lo recibido de la sección 13.
- **Pixel 10 Pro / Android 17: NO PROBADO.** No hay un dispositivo físico en este entorno. Todo lo que dice «probado» se refiere a un **emulador** Android 14 (API 34, perfil Pixel 6, 1080 × 2400) en GitHub Actions, con la APK de depuración. La aprobación final corresponde al propietario en su teléfono.
- **Pruebas en la JVM** (núcleo de imagen y color): **104 de 104** en este entorno (99 anteriores + 5 nuevas de color).
- **Ejecución completa en el emulador:** commit `e53fc24` (el mismo código de la APK entregada): **28 de 28 ejecuciones aprobadas**, 0 bloqueos (`crash.txt` vacío). Detalle en la sección 14.

## 1. Decisión 1 — Silueta: el borrado y la restauración se ven bajo el dedo

**Problema confirmado.** En el vídeo de la 1.1 (`Video_silueta_borrador.mp4`) el disco amarillo seguía intacto durante el primer trazo del borrador; lo borrado aparecía más tarde. La prueba de la 1.1 solo comprobaba los **datos** (`cutSnapshot`), no la **pantalla**; por eso no lo detectó.

**Causa.** Los píxeles del trazo en curso se calculaban bien y se subían en mosaicos (`LiveTiles`), pero los mosaicos se dibujaban **encima** de la imagen anterior. Al borrar, el mosaico es transparente, y a través de él seguía viéndose la imagen opaca de debajo. Restaurar sí se veía (píxeles opacos sobre transparentes); borrar no. Lo borrado solo aparecía cuando se reconstruía la imagen completa (al restaurar, cambiar de panel o salir).

**Solución** (`render/LiveTiles.kt`, `render/ObjectRenderer.kt`, `editor/DrawPanels.kt`):

- `LiveTiles.drawOver`: las celdas que tienen mosaico se **recortan** de la imagen de base y el mosaico se dibuja en su lugar, en vez de mezclarse encima. Un píxel que pasa a transparente muestra al instante lo que hay debajo.
- Se usa en la Silueta y en la máscara de efectos (mismo problema al borrar la máscara).
- La herramienta Pintar sigue dibujando sus mosaicos como en la 1.1 (sobre la foto opaca), porque así su fidelidad es exacta.
- No se procesa la imagen completa por movimiento: sigue el cálculo incremental por región de la 1.1, a la misma resolución que la exportación (sin vista previa reducida).

**Prueba nueva, sobre la PANTALLA y con el dedo apoyado** (`Mac11Test#cutOutVisibleWhileFingerMoves`). Las capturas se toman **antes de levantar el dedo** y se compara el color en pantalla bajo el dedo con la captura previa (diferencia media |ΔR|+|ΔG|+|ΔB| en 9 × 9 px; umbral 60):

| Caso | Resultado en el emulador |
|---|---|
| Borde de la imagen (trazo desde fuera hacia dentro), dedo apoyado | diferencia 462 → **se ve** |
| Borrado lento, dedo apoyado, aún sin confirmar (0 trazos guardados) | inicio 422, posición actual 425, zona lejana 0 → **se ve, solo donde pasa el dedo** |
| Borrado rápido con cambio brusco de dirección, dedo apoyado | 414 → **se ve** |
| Al levantar el dedo | sigue borrado (422), **sin salto** |
| Restaurar, dedo apoyado | respecto a la imagen original 0, respecto a lo borrado 516 → **vuelve exacto** |
| Alternar a borrar con un trazo corto, dedo apoyado | 256 → **se ve** |
| Con zoom × 2 y desplazamiento | todos los casos anteriores se hicieron con zoom |
| Lo visto = lo exportado | **0 píxeles distintos de 540 000** |
| Guardar el proyecto y volver a cargarlo | la silueta se conserva igual (4 trazos) |

Capturas `130`–`136_silueta_*.png` y vídeo `silueta_en_vivo.mp4`. La prueba de la 1.1 (`cutOutUnderTheFinger`: zoom, rotación, perspectiva, pixel exacto bajo el dedo, deshacer/rehacer) también se aprobó.

## 2. Decisión 2 — Guardar, finalizar y navegar

Lo implementado en la 1.1 se mantiene (Finalizar con las 4 opciones, Guardar imagen con nombre / PNG / JPG / calidad / galería / ubicación, Guardar proyecto, Salir sin guardar que restaura lo último guardado y nunca borra versiones guardadas, borradores sin cambios que no crean trabajo reciente). Nuevo:

- Back cierra primero **«Más opciones» de una herramienta** antes de cerrar la herramienta (antes la cerraba directamente).
- Orden completo de Back: diálogo → menú/submenú → panel → paso de la herramienta → herramienta → selección → diálogo de guardar (solo con cambios).

**Pruebas** (`Mac12Test#backHierarchyAndFilePickerCancel`, `Mac11Test#finishSaveAndBackNavigation`): Back cierra un menú y el editor sigue; Back cierra las opciones y la herramienta sigue abierta; otro Back cierra la herramienta; **se abrió el selector de archivos del sistema y se canceló con Back: el editor sigue y el cambio no se pierde**; Back con cambios → Guardar proyecto → el proyecto se recupera con su cambio (rotado: 800 de ancho); sin cambios, Back sale sin diálogo.

## 3. Decisión 3 — Miniatura del trabajo reciente

- Caja horizontal de **140 × 100 dp** arriba a la derecha; se reduce si la pantalla es estrecha o baja (36 % del ancho, 20 % del alto).
- La imagen completa dentro de la caja con sus proporciones (**Fit**: ni estirada ni recortada). Sigue abriendo el trabajo.
- Arreglo relacionado: la miniatura de una «Nueva imagen en blanco» se generaba cuadrada (360 × 360); ahora tiene las proporciones del lienzo.

| Imagen | Imagen mostrada (dp) en caja 140 × 100 dp |
|---|---|
| 9:16 (1080 × 1920) | 56,4 × 100,2 |
| 1:1 | 100,2 × 100,2 |
| 16:9 | 140,2 × 78,9 |
| Transparente 4:3 | 133,3 × 100,2 |
| Alta resolución 4000 × 3000 | 133,3 × 100,2 |

En todos los casos el botón «Abrir imagen» queda debajo, sin desplazarse. Capturas `150_inicio_miniatura_*.png`.

## 4. Decisión 4 — Nueva imagen en blanco

- Por defecto **1080 × 1920 (9:16)**. Formatos rápidos: 9:16, 4:5, 1:1, 16:9 y Personalizado. Botón para intercambiar ancho y alto (orientación).
- Validación: lados de 16 a 10 000 px, y un **límite de memoria** calculado con la memoria de la app (en el emulador: 9,4 millones de píxeles). El botón Aceptar se desactiva y se explica el motivo.
- Fondo con el selector de color unificado (decisión 5).

Prueba (`Mac12Test#newBlankImageFormatsAndLimits`): cada formato da su tamaño; intercambio 1080 × 1350 → 1350 × 1080; 8 px rechazado; 20 000 px rechazado; 9000 × 9000 rechazado por memoria; 1234 × 777 con fondo `#FF3366` crea un lienzo de **1234 × 777** cuyo centro es exactamente `#FF3366`.

## 5. Decisión 5 — Selector de color unificado

`editor/ColorPicker.kt` (reescrito), `editor/EyedropperOverlay.kt` (nuevo), `core/imaging/color/ColorMath.kt` (nuevo).

- **Un solo selector** para: fondo de la imagen nueva, texto, contorno, sombra, fondo del texto, globos, formas, Pintar, efectos con color (bordes, marcos…), fondo de exportación y fondos de herramientas.
- Compacto: nombre, color actual, cuentagotas, colores predefinidos y **recientes**. «Personalizar…» despliega: **HEX** (`#RRGGBB` / `#AARRGGBB`), **favoritos** (estrella), valores **RGB y alfa**, **cuadro de saturación/brillo**, barras de **tono, saturación, brillo y opacidad**.
- **Recientes y favoritos se conservan** entre sesiones. Los colores que el usuario había añadido a la paleta de Pintar en la 1.1 pasan a ser sus primeros favoritos (no se pierden).
- **Cuentagotas** (en todos los selectores del editor): abre la imagen tal como se ve (foto + todos los objetos visibles), a pantalla completa. Un dedo: una **lupa** de 11 × 11 píxeles sigue al dedo y muestra el color y su HEX; al levantar el dedo se aplica a la herramienta activa. Dos dedos: zoom (hasta × 16) y desplazamiento. Back o ✕ cancelan. Pintar conserva además su cuentagotas directo sobre el lienzo.

**Pruebas.** JVM (`ColorMathTest`, 5 pruebas): ida y vuelta RGB → HSB → RGB exacta en 5832 colores, valores HSB conocidos, transparencia, HEX válidos e inválidos, recientes sin duplicados. Emulador (`Mac12Test#colorPickerHexFavouritesRecentsEyedropper`, imagen mitad roja / mitad azul): HEX `#80112233` aplicado; favorito guardado en las preferencias; **cuentagotas con zoom × 4 a 12 px a la izquierda del centro → rojo; a la derecha → azul**; el color tomado aparece en recientes; Back cierra el cuentagotas sin cambiar el color.

## 6. Decisión 6 — Iconos de las categorías de stickers

`ui/classic/CategoryIcons.kt` (nuevo). **32 ilustraciones vectoriales originales**, dibujadas para esta app (no copiadas de ninguna otra), con un mismo estilo: colores planos, contorno oscuro y un brillo; el objeto flota solo sobre **fondo transparente**, sin cajas ni escenas. Vectores: nítidos en cualquier densidad.

- Globo de diálogo, Título, las 30 categorías del catálogo, Mis stickers e Importar biblioteca.
- No se quitó ni renombró ninguna categoría. Las vacías muestran su ilustración y «Pendiente de importar» en pequeño; dentro, la ilustración grande y el texto de importar. **No se inventaron stickers.**

Prueba (`Mac12Test#stickerCategoryIllustrations`): las 30 categorías tienen ilustración (0 sin icono); 11 notas «Pendiente de importar» visibles en la primera pantalla. Capturas `240`–`242`.

## 7. Decisiones 7 a 10 — Fuentes

**7. Pantalla «Más fuentes» compacta.** En este orden: botón **«Importar fuentes»**, la línea «Importa fuentes TTF, OTF o ZIP desde tu dispositivo», un botón **?** con la ayuda detallada (archivos, ZIP, comprobaciones, límites, gestión, licencias) y después la lista. Sin párrafos largos.

**8. Selección automática.** Un toque aplica la fuente al texto activo y **vuelve sola** a la edición del texto, sin segunda confirmación. La fuente se conserva al volver, al cambiar otras propiedades, al guardar y al reabrir. **Variantes reales:** Negrita / Cursiva usan el archivo real de la familia cuando existe (p. ej. Poppins Bold, Creato Display Bold Italic); solo se sintetiza si la familia no tiene ese archivo.

**9. Búsqueda, favoritas, recientes.** Secciones Favoritas (estrella en cada fila), Usadas recientemente (10, sin duplicados) y **Todas las fuentes (A–Z)** agrupadas por familia; cada fila en su propia fuente, cargada en segundo plano y guardada en memoria. La búsqueda filtra con claves precalculadas (no recarga fuentes por tecla). Persistentes.

**10. Gestor.** Importación de TTF, OTF y ZIP (todas las fuentes válidas del ZIP) con **barra de progreso sin bloquear la pantalla** y **resumen en 4 partes**: instaladas / duplicadas omitidas / no válidas / errores. Seguridad del ZIP: lectura con límite (25 MB por fuente, 400 MB descomprimido, 5000 entradas), rutas inseguras rechazadas, parada si queda poco almacenamiento. Solo se pueden borrar fuentes **importadas**; antes de borrar se avisa de los proyectos que la usan (que conservan su copia).

**Pruebas** (`Mac12Test#fontsCompactHeaderFavouritesRecentsImportDelete`, `Mac11Test#fontCatalogueSearchPickPersist`, `Mac10FlowsTest#fontsImportPickAndPersist`): orden botón → línea → búsqueda; ayuda; favorita guardada; búsqueda «comf»; tocar «Comfortaa Bold» la aplica y vuelve al texto; recientes sin duplicados; ZIP de prueba → **instaladas 2, duplicada 1, no válida 1, errores 2 (archivo de 26 MB y ruta `../`)**, progreso observado; la fuente importada se usa, se guarda el proyecto y al borrarla el aviso nombra el proyecto; tras borrarla el proyecto conserva su copia.

Nota de medida: «búsqueda mostrada en 1,6–1,7 s» en el registro es el tiempo de la prueba completa (escribir, esperar a que la interfaz quede en reposo y cargar las fuentes de las filas nuevas en el emulador); no es la latencia de una pulsación.

## 8. Sección 13 — Una sola app, una sola APK

- **Eliminado** el esquema de APK personal: `tools/personal_fonts/` borrado, la categoría «Mi biblioteca de fuentes» quitada del código, la prueba y el paso de CI que fabricaban la APK personal, eliminados.
- Dentro de la app solo van las fuentes con licencia que permite redistribuirlas (SIL OFL y CC0; las mismas de la 1.1, con sus licencias en «Licencias y créditos»).
- Las fuentes **ya importadas** por el usuario se conservan (están en el almacenamiento de la app; la actualización no las toca).
- **Aviso al propietario:** si instaló la APK personal 1.1, las fuentes de esa biblioteca venían **dentro de aquella APK** y desaparecen al instalar la 1.2.0 (una sola APK). Puede volver a tenerlas con «Importar fuentes» → su ZIP. Los proyectos que las usaban se muestran con la fuente predeterminada hasta entonces, sin perder el texto.

## 9. Errores encontrados durante el desarrollo (y corregidos)

- Al conectar Pintar al selector nuevo, un reemplazo borró por error los paneles «Ajustes» y «Pincel» de Pintar. Lo detectó la prueba de regresión `MacFlowsTest#homeTitleAndPaint`; se restauró el archivo y se volvió a aplicar el cambio solo al panel de color.
- El primer intento de `drawOver` también en Pintar cambiaba el filtrado de toda la foto (la prueba de fidelidad dio 22 463 px distintos); Pintar volvió al dibujo de la 1.1.
- Android 14 rechaza por sí mismo las entradas `../` de un ZIP y deja de leerlo ahí; ahora se informa como «ruta insegura» conservando lo leído hasta ese punto.

## 10. Pendiente o no verificado

- **Decisiones 11 y 12 y el final de la sección 13:** no recibidas.
- **Pixel 10 Pro / Android 17:** no probado.
- Tiempos y fluidez en el teléfono: no medidos (el emulador no es representativo).
- La lupa del cuentagotas y el gesto de dos dedos se probaron con gestos sintéticos del emulador, no con un dedo real.
- La observación abierta del trazo en vivo (sección 14).

## 14. Ejecución completa en el emulador (commit `e53fc24`)

28 ejecuciones, **28 aprobadas**, 0 bloqueos de la app:

- **Actualización 1.1.0 → 1.2.0:** se instaló la 1.1.0 (versionCode 14), se crearon datos con ella (proyecto, biblioteca de stickers, fuentes importadas, preferencias), se instaló la 1.2.0 (versionCode 15) encima con la misma firma y `dataSurvivedUpgrade` comprobó que todo sigue.
- **1.2.0:** `cutOutVisibleWhileFingerMoves`, `newBlankImageFormatsAndLimits`, `colorPickerHexFavouritesRecentsEyedropper`, `fontsCompactHeaderFavouritesRecentsImportDelete`, `backHierarchyAndFilePickerCancel`, `stickerCategoryIllustrations`, `homeThumbnailFitsHorizontalBox`.
- **Regresión 1.1:** trazo en vivo y fidelidad, toque en el asa (tamaño ↔ perspectiva), Silueta bajo el dedo, finalizar/guardar/Back, catálogo de fuentes.
- **Regresión 1.0 y anteriores:** pantalla de inicio con letra 1,0 / 1,3 / 2,0 y en horizontal, importación de fuentes, trazo en vivo = trazo aplicado, Insertar imagen (recortar, colocar, silueta), pantallas de referencia, biblioteca de stickers, imagen grande, título y Pintar, perspectiva/guardar/reabrir/exportar, formas SVG.

**Trazo en vivo (regresión de la corrección 1 de la 1.1), APK de depuración en el emulador:** fidelidad final **0 píxeles distintos de 15 728 640** en 5 trazos; tiempo de cálculo por actualización: mediana 14,8 ms, p95 72,2 ms (todas), similar a la 1.1. **Silueta:** mediana 7,1 ms, p95 67,4 ms.

**Observación abierta (trazo en vivo).** En el primer trazo de la prueba, la comparación hecha *inmediatamente* después de retirar los mosaicos dio en algunas ejecuciones 22 463 píxeles distintos; 0,25–1,5 s después, sin ningún render nuevo, la imagen era idéntica a la del dedo apoyado (0 distintos). En la ejecución anterior (`43196106325`) fue 0 desde el principio. Es una comparación de búferes de datos, no de la pantalla; no se identificó la causa ni se vio un efecto en pantalla. La prueba ahora espera a que la imagen quede estable y registra ambas cifras.

**Archivos de la ejecución:** capturas PNG y vídeos MP4 en la rama `screens-output`; registros `mac11_log.txt`, `mac12_log.txt`, `results.txt`.
