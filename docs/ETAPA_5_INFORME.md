# Etapa 5: efectos

Versión 0.5.0 (versionCode 7).

## Qué se implementó

Hay 46 efectos nuevos en `core/imaging/.../effects/`. Todos están escritos en Kotlin puro y conservan el tamaño de la imagen, así que se pueden usar con máscaras.

| Familia (menú) | Efectos |
|---|---|
| Ajustar (extras de Pro) | Realzar (Boost), Enfocar (USM, máx. 2, defecto 1,5), Suavizar (0…10), Desenfocar (0…40), Ajustar RGB |
| Efectos › Distorsionar | Esferizar, Remolino (−90…90), Abultar, Ondas (Circle Splash), Estirar, Estirar vertical, Estirar horizontal, Espejo (`rightToLeft`) |
| Color | Escala de grises (filtro 0…360°), Blanco y negro (umbral 0…255), Duotono (color oscuro, color claro y contraste), Arcoíris (defecto 1), Mezclar con color (50 %, modos normal, multiplicar, trama, superponer, luz suave, color y borrar) |
| Efectos › Artístico | Impresión de cómic (punto 4…30, defecto 7), Posterizar (2…20, defecto 4), Pop art (contraste 0,5), Dibujo a lápiz (saturación), Neón (color y contraste) |
| Efectos › Foto | Lomo, Proceso cruzado, Áspero, Colores desvaídos, Grano de película, Redscale, Falso HDR (0,6), Tilt-shift, Resplandor suave |
| Efectos › Filtros y marcos | Viñeta, Desenfoque focal (radio 0,5, transición 0,5), Filtro degradado superior (0,75), Filtro de bordes (0,75), Foco (Spotlight), Instantánea (−10…10), Película, Esquinas redondeadas (0,75), Borde sólido (0…80), Paspartú (0,75) |
| Efectos › Varios | Pixelar, Mareo (−30…30), Rayos X, Mapa de calor |
| Solo con máscara | Hacer transparente |

Todos los tamaños (radios, bloques, desenfoques y bordes) se expresan en función del lado corto de la imagen. Así la vista previa reducida y la exportación a tamaño completo se ven igual, y un test lo comprueba.

### En la app

- Hay un menú nuevo, **Efectos**, con chips para cada familia.
- Los efectos que tienen centro muestran un punto que se arrastra sobre la foto con un dedo:
  - remolino, esferizar, abultar, ondas, estirar, foco, desenfoque focal y tilt-shift;
  - un toque coloca el punto en ese lugar;
  - con dos dedos se sigue haciendo zoom.
- Los paneles con muchos controles se desplazan en lugar de tapar la foto.
- Lista de efectos después de pintar una máscara:
  - incluye ajustes, color, artísticos, foto y varios, más «Hacer transparente», como en el original;
  - no incluye distorsiones ni marcos;
  - está agrupada por familia.
- Las opciones de los selectores (modos de mezcla) están traducidas al español y al inglés.
- Si un efecto añade transparencia a la foto (esquinas redondeadas, «Hacer transparente»), al exportar se ofrece el color de fondo para JPEG y PNG pasa a ser el formato por defecto.

## Fidelidad (sin inventar comportamiento)

Del original solo se reproducen el nombre, el menú, los nombres de campo, los rangos y los valores por defecto **verificados** (FASE1 §B2.2). Las fórmulas de píxel del original están en código nativo, que no se descompiló. Las de este proyecto son propias y quedan **pendientes de calibrar visualmente** contra capturas. Lo mismo ocurre con estos rangos y valores, que no estaban verificados y se eligieron aquí:

- radio de las distorsiones;
- ángulo de Estirar;
- número de ondas;
- colores por defecto de Duotono, Neón y los filtros de lente;
- fondo de Instantánea;
- la intensidad de Lomo, Proceso cruzado y otros efectos de foto.

No se usaron las texturas del APK (`grain.jpg`, `heatmap.png`, `xray.png`, `pencil_sketch_mapped.jpg`): el grano y el papel son ruido procedural, y las paletas son propias.

## Pruebas (ejecutadas)

Se ejecutó `tools/build-core-offline.sh`: **75 tests OK**, de los cuales 11 son nuevos en `EffectsTest`. Los tests nuevos comprueban lo siguiente:

- **Registro:** todos los efectos están registrados y sus identificadores son únicos.
- **Robustez:** cada efecto conserva el tamaño y es determinista. No falla con transparencia, con una imagen de 1×1 ni con los valores extremos de cada control.
- **Visibilidad e identidad:** los valores por defecto cambian la foto, y la cantidad 0 la deja igual.
- **Distorsiones:** solo modifican el interior del radio, y el remolino conserva el centro.
- **Comportamientos concretos:**
  - el espejo es simétrico;
  - pixelar produce bloques uniformes;
  - blanco y negro produce solo blanco o negro;
  - posterizar respeta el número de niveles;
  - duotono lleva el negro al color oscuro y el blanco al claro;
  - el modo «borrar» deja alfa 0;
  - Ajustar RGB aplica las ganancias exactas.
- **Desenfoques:** dejan intactas las zonas planas, y el borde de un área opaca no se oscurece junto a la transparencia.
- **Marcos y filtros:** viñeta, esquinas, borde, foco, filtro superior, instantánea y película.
- **Vista previa y exportación:** coinciden a escala. En las texturas finas (grano, lápiz y puntos de cómic) se compara el tono medio.

Material visual y de rendimiento:

- Hoja visual: `docs/stage5_effects.png`.
- Tiempos en JVM con 2 núcleos: `docs/performance_effects.csv`. A 12 MP, entre 13 ms y 1,9 s. En la vista previa de 2048 px, ≤ 0,56 s. **No se midió en el teléfono.**

## Pendiente

- Calibrar las fórmulas contra el original.
- Curvas (necesita un editor de curvas).
- Warp Brush, Perspectiva, Pop Color, Insertar imagen, efectos de objeto (sombra, resplandor) y copia de EXIF.
- Arrastrar el centro recalcula la vista previa completa. En los efectos con desenfoque grande puede notarse un retraso en el teléfono; si se nota, se añadirá una vista previa reducida durante el arrastre.
