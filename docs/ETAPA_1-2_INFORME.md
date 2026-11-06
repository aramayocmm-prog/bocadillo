# Informe de etapa — FASE 2 + Etapas 1 y 2

**Fecha:** 2026-11-06 · **Versión:** 0.2.0-stage2

Etapa 1: núcleo, documento, historial, importación y exportación. Etapa 2: transformaciones y ajustes de color.

## 1. Estado por función

Leyenda:
- ✅ **probado**: prueba automática ejecutada y superada en este entorno (JVM).
- 🟡 **implementado, sin probar en dispositivo**: código Android escrito pero no compilado aquí (no hay SDK).
- ⏳ **pendiente**: etapa posterior.

### Núcleo (Kotlin puro)
| Función | Estado | Pruebas |
|---|---|---|
| Modelo de documento no destructivo | ✅ | `ModelTest` (12 pruebas) |
| Deshacer y rehacer de varios pasos, descarte de la rama de rehacer, límite que conserva el estado inicial, saltar a una etapa | ✅ | 3 pruebas |
| Restaurar original | ✅ | `resetToOriginalClearsEdits` |
| Formato de proyecto `.bcproj` (JSON + original intacto + máscaras + miniatura) | ✅ | ida y vuelta, rechazo de un ZIP ajeno, rechazo de una versión más nueva, limpieza de máscaras huérfanas |
| Índice de proyectos: recientes, favoritos y **sin límite de 8** | ✅ | `projectIndexOrderingFavoritesAndPersistence` |
| Recortar libre y con proporción (manteniendo la proporción en píxeles) | ✅ | `cropWithoutAngleIsExact`, `CropMathTest` (5 pruebas) |
| Enderezar ±10° con recorte automático sin esquinas vacías | ✅ | `straightenKeepsCentreAndAutoCropIsInside` |
| Girar 90°/180°/270° y voltear H/V sin pérdida | ✅ | 2 pruebas |
| Rotación libre (herramienta nueva) | ✅ | coincide con el giro de 90° sin pérdida (diferencia ≤ 2) |
| Cambiar tamaño: estirar, ajuste rápido, escalar y recortar, recortar bordes, ampliar bordes con color | ✅ | `resizeModes…` |
| Remuestreo Lanczos sin halos de transparencia | ✅ | 2 pruebas |
| Brillo, Exposición/Contraste, Saturación, Temperatura/Tinte, Tono, Colorear, Invertir, Sepia | ✅ | identidad con valores neutros, comportamiento, conservación del alfa |
| Máscaras: cobertura, invertir y escalado | ✅ | `maskBlendRespectsCoverageAndInversion` |
| Caché de prefijos, omisión de operaciones desconocidas o neutras, cancelación | ✅ | 2 pruebas |
| Vista previa a escala ≡ geometría de exportación | ✅ | `previewScaleMatchesExportGeometry` |
| Prueba visual de todas las herramientas | ✅ | `build/visual/contact_sheet_stage2.png`, revisada a ojo |
| Rendimiento con 12 MP | ✅ | ver §3 |

**Total: 38 pruebas, 38 superadas**, con `tools/build-core-offline.sh`.

### App Android
| Función | Estado |
|---|---|
| Inicio: recientes y favoritos, abrir foto (Photo Picker), cámara, lienzo en blanco (tamaño y color, incluido transparente) | 🟡 |
| Menú del proyecto: abrir, favorito, duplicar, eliminar (con confirmación) | 🟡 |
| Recibir fotos por «Compartir» y «Editar con» | 🟡 |
| Editor: lienzo con pellizco para zoom (1–12×), desplazamiento y doble toque para encajar | 🟡 |
| Menús Ajustar, Color, Transformar y Más (herramientas nuevas aparte) | 🟡 |
| Panel de herramienta con controles generados, vista previa en vivo, Comparar (mantener pulsado), Restablecer, Aplicar y Cancelar | 🟡 |
| Recorte en el lienzo (asas, mover, cuadrícula de tercios) con 6 proporciones y enderezado | 🟡 |
| Diálogo de tamaño con «Mantener proporciones» y 5 modos | 🟡 |
| Deshacer, Rehacer y Restaurar original (con confirmación) | 🟡 |
| Autoguardado, guardado al pasar a segundo plano y recuperación tras cierre inesperado | 🟡 |
| Exportar JPEG (3 calidades + calidad 1–100), PNG con transparencia o igual que el original; resolución al 100/75/50/25 %; galería, Guardar como… y Compartir; fondo para aplanar la transparencia en JPEG | 🟡 |
| Tema claro y oscuro (Material You en Android 12+), español e inglés, sin permisos, sin red | 🟡 |

## 2. Problemas conocidos y limitaciones
1. **La app Android no se ha compilado.** Este entorno no tiene Android SDK ni acceso a Maven Google. El código se revisó a mano, pero pueden aparecer errores de compilación menores (imports, firmas de API). Hay que compilarlo en Android Studio (`docs/BUILD.md`) y, si falla, enviarme el error.
2. **Fórmulas de color calibradas por equivalencia.** Los rangos y valores por defecto coinciden con el original verificado, pero las curvas exactas del motor nativo de PicSay no se conocen; están marcadas en el código como «calibración pendiente». La comparación visual con el original requiere ejecutar ambas apps.
3. **Los tiempos de rendimiento son de la JVM de escritorio con 2 núcleos**, no de un teléfono. Un ARM64 actual con 8 núcleos debería ser comparable o más rápido en filtros, pero hay que medirlo.
4. **Memoria:** exportar a 48 MP necesita unos 750 MB en el peor caso. El límite se ajusta a la memoria del dispositivo y los errores por memoria se capturan y se muestran como mensaje, pero no se ha probado en dispositivos de gama baja.
5. **No se copian los datos EXIF** a la imagen exportada (fecha, cámara, GPS). El original escribía Orientation, Software y DateTime. Pendiente para la etapa 3 junto con «Insertar fecha».
6. El compilador mostró avisos menores (sin errores) en el núcleo; no afectan al funcionamiento.

## 3. Rendimiento medido (JVM, 2 núcleos, 4000×3000)
| Operación | ms |
|---|---|
| Brillo / Saturación / Tono / Invertir / Sepia | 76–98 |
| Exposición/Contraste, Temperatura/Tinte | 42–51 |
| Colorear | 168–173 |
| Recortar con enderezado | 262 |
| Rotación libre (25°) | 499 |
| Lanczos 4000×3000 → 2000×1500 | 413–465 |
| Vista previa 1000×750 con 8 ajustes encadenados | 31–52 |

## 4. Archivos creados en esta etapa
```
settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradlew(.bat), gradle/wrapper/*
core/model/build.gradle.kts
core/model/src/main/kotlin/app/bocadillo/core/model/{Params,ParamSpec,Document,History,DocumentJson,ProjectArchive,ProjectIndex}.kt
core/model/src/test/kotlin/app/bocadillo/core/model/ModelTest.kt
core/imaging/build.gradle.kts
core/imaging/src/main/kotlin/app/bocadillo/core/imaging/{PixelBuffer,RenderContext,ColorMatrix,Resample,Pipeline}.kt
core/imaging/src/main/kotlin/app/bocadillo/core/imaging/ops/{ImageOp,TransformOps,ColorOps,CropMath}.kt
core/imaging/src/test/kotlin/app/bocadillo/core/imaging/{ImagingTest,CropMathTest,VisualAndPerformanceTest}.kt
app/build.gradle.kts, app/proguard-rules.pro, app/src/main/AndroidManifest.xml
app/src/main/kotlin/app/bocadillo/{BocadilloApp,MainActivity}.kt
app/src/main/kotlin/app/bocadillo/data/{ImageIo,ProjectRepository,ExportRepository}.kt
app/src/main/kotlin/app/bocadillo/editor/{EditorViewModel,EditorScreen,EditorCanvas,ToolPanels,ExportSheet,Labels}.kt
app/src/main/kotlin/app/bocadillo/home/{HomeViewModel,HomeScreen}.kt
app/src/main/kotlin/app/bocadillo/ui/theme/Theme.kt
app/src/main/res/values{,-es,-night}/…, xml/file_paths.xml, drawable/ic_launcher_foreground.xml, mipmap-anydpi-v26/ic_launcher.xml
tools/build-core-offline.sh, tools/apk-analysis/{axml,arsc,dex}.py
docs/{ARQUITECTURA,BUILD,ETAPA_1-2_INFORME}.md
```

## 5. Siguiente etapa (3): texto, globos y stickers
- Implementaciones de `CanvasObject`: Título, Globo y Sticker, con transformación (mover, escalar, rotar, estirar), opacidad y orden Z.
- **Texto:** varias líneas, fuente (incluidas, del sistema e importadas por SAF), tamaño, color, contorno con grosor, sombra con desplazamiento X/Y, alineación, altura de línea, duplicar, frases frecuentes, fecha y hora (EXIF o personalizada), guías magnéticas y texto curvado (Circular Wrap).
- **Globos:** 6 formas, cola arrastrable de forma independiente, estilos y colores.
- **Stickers:** catálogo vectorial original nuevo por categorías, colores editables, importación de PNG y sustitución.
- Interacción en el lienzo: selección por toque, asas de escala y rotación, menú contextual.
