# Informe de etapa 4: Dibujo y máscaras

**Fecha:** 2026-11-06 · **Versión:** 0.4.0-stage4

## Estado

Leyenda:
- ✅ probado (JVM o revisión visual)
- 🟡 compilado, pendiente de probar en el dispositivo

| Función | Estado |
|---|---|
| Trazos vectoriales: puntos normalizados, presión, tamaño (fracción del lado menor), dureza, opacidad, color, suavizado Catmull-Rom | ✅ `PaintTest` |
| Codificación de trazos dentro del proyecto (sobrevive al JSON) | ✅ |
| Un trazo que se cruza consigo mismo no se oscurece (como una pasada de rotulador) | ✅ |
| Pincel, marcador (multiplicar) y borrador a transparente | ✅ |
| Independiente de la resolución (vista previa = exportación) | ✅ |
| Máscara y selección: seleccionar (verde) o proteger (rojo), invertir, borde suave, aplicable a cualquier ajuste o color | ✅ |
| History Paint: pintar desde un paso anterior del mismo tamaño | ✅ |
| Interfaz de dibujo: un dedo dibuja y dos dedos hacen zoom; presión solo con lápiz; deshacer y rehacer trazos; cuentagotas | 🟡 |
| Flujo de máscara: pintar → Siguiente → elegir efecto → ajustar → Aplicar (un solo paso de historial) | 🟡 |
| Superposición roja o verde de la máscara mientras se pinta | 🟡 |

**Pruebas del núcleo: 63 de 63 superadas.** Hoja visual: `docs/stage4_paint_mask.png`.

## Limitaciones
- El trazo en curso se previsualiza como una línea simple. La dureza y la presión se ven al soltar el dedo.
- Cada trazo nuevo recalcula todos los trazos de la herramienta abierta. Con cientos de trazos en una foto grande puede notarse cierta lentitud; si ocurre, se optimizará.
- "Pop Color" (Pro) llegará en la etapa de efectos. Se podrá hacer también con máscara + Saturación −100 %.

## Archivos
- Nuevos: `core/imaging/.../paint/Strokes.kt`, `.../ops/PaintOps.kt`, `PaintTest.kt`, `app/.../editor/DrawPanels.kt`.
- Modificados: `Pipeline.kt`, `ImageOp.kt`, `EditorViewModel.kt`, `EditorScreen.kt`, `EditorCanvas.kt`, `Labels.kt`, `strings.xml`.
