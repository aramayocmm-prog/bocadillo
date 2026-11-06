# Arquitectura de Bocadillo (FASE 2)

Bocadillo es la reconstrucción independiente de PicSay Pro. El nombre, los iconos y todos los recursos son nuevos, y no contiene código ni recursos del APK original.

## 1. Módulos

```
:core:model    Kotlin/JVM puro. Documento, parámetros, historial, formato de proyecto e índice de galería.
:core:imaging  Kotlin/JVM puro. PixelBuffer, remuestreo, operaciones de imagen, máscaras y pipeline con caché.
:app           Android. Compose + MVVM: pantallas, ViewModels, decodificación y codificación, almacenamiento.
```

Los dos módulos `core` no dependen de Android. Por eso todo el procesamiento de imagen y la lógica de proyecto se prueban en la JVM sin emulador, y se pueden reutilizar.

## 2. Modelo no destructivo (equivalente al patrón Command del original)

```
Document
 ├─ source: SourceInfo              ← la foto original, guardada tal cual (nunca se recodifica)
 ├─ ops: List<OpInstance>           ← operaciones raster en orden (id estable + Params + máscara opcional)
 ├─ objects: List<CanvasObject>     ← títulos, globos y stickers (etapa 3)
 └─ masks: List<MaskRef>            ← máscaras de cobertura de 8 bits por operación
```

- **Render** = original → op₁ → op₂ → … → opₙ. Así se puede deshacer siempre, reeditar parámetros y restaurar el original. Es el mismo principio del original (`historyIndex` y archivos `.his`).
- **Historial** (`History<Document>`): cada paso es un `Document` inmutable. Las listas que no cambian se comparten entre pasos, así que cada uno ocupa poco. El límite por defecto es de 200 pasos y la etapa "Imagen abierta" se conserva siempre. `stages()` queda preparado para History Paint (Pro).
- **Máscaras**: un op no geométrico puede llevar `maskId`. El resultado es `entrada + (filtrado − entrada) × cobertura`, con opción de invertir. En Pro esto equivale a Mask/Select y Erase Parts.

## 3. Herramientas extensibles

Cada herramienta implementa `ImageOp`:

```kotlin
interface ImageOp {
    val id: String                 // se guarda en el proyecto; nunca se renombra
    val category: OpCategory       // menú donde aparece
    val params: List<ParamSpec>    // sliders, interruptores, opciones, colores, puntos
    val geometric: Boolean         // si cambia el tamaño (no admite máscara)
    val origin: Origin             // PICSAY_BOTH | PICSAY_PRO | NEW (trazabilidad con la matriz)
    fun apply(src, params, ctx): PixelBuffer
    fun outputSize(w, h, params, ctx)
}
```

Para añadir una herramienta basta con crear el `ImageOp` y registrarlo en `OpRegistry.default()`. La interfaz construye los controles a partir de `ParamSpec` (`ParamControls`) y la caché, el historial, el guardado y la exportación funcionan sin más cambios. Las herramientas nuevas (`Origin.NEW`, categoría `MODERN`) aparecen en un menú aparte ("Más"), separadas de las originales, como pide la FASE 10.

## 4. Rendimiento y memoria

- **Proxy de vista previa**: la foto se decodifica a un máximo de 2048 px en el lado largo para editar. La exportación vuelve a decodificar la original a tamaño completo y repite las mismas operaciones.
- **`RenderContext.scale`**: los parámetros en píxeles (como el tamaño de Resize) se escalan, así que la vista previa coincide con la exportación. Hay una prueba que lo verifica.
- **Caché de prefijos (LRU)**: mientras el usuario arrastra un slider solo se recalcula la última operación.
- **Paralelismo** por bandas de filas y **cancelación cooperativa**: si llega un render nuevo, el anterior se abandona (`collectLatest`).
- **Tamaño de trabajo** hasta 48 MP, limitado por la memoria disponible (`ImageIo.maxWorkingPixels`). Si una foto se reduce, se avisa al usuario.
- **Remuestreo** Lanczos‑3 separable en espacio premultiplicado, para que no aparezcan halos al reducir imágenes con transparencia.

## 5. Persistencia

| Qué | Dónde | Formato |
|---|---|---|
| Proyectos | `filesDir/projects/<id>.bcproj` | ZIP con `manifest.json`, `document.json`, `source/…`, `masks/*.mask` y `thumbnail.jpg` |
| Índice (recientes y favoritos) | `filesDir/projects/index.json` | JSON |
| Miniaturas | `filesDir/thumbs/<id>.jpg` | JPEG |
| Marca de recuperación | `filesDir/projects/open_session` | id del proyecto abierto |

- Las escrituras son **atómicas** (archivo temporal + renombrado).
- **Autoguardado** 1,2 s después de cada cambio y también al pasar la app a segundo plano (`ON_STOP`).
- Si la app muere con un proyecto abierto, la marca sigue ahí y la pantalla de inicio **ofrece recuperarlo**.

## 6. Importación y exportación (sin permisos)

- **Importar**: Photo Picker, cámara (`TakePicture` hacia `FileProvider`) e Intents `SEND` y `EDIT` con `image/*`.
- **Exportar**: MediaStore (`Pictures/Bocadillo`), SAF (`CreateDocument`) o compartir (`FileProvider` + selector del sistema).
- **Formatos**: JPEG muy alta, alta y media (95, 85 y 70; calidad ajustable de 1 a 100), PNG con transparencia y "Igual que el original". Resolución al 100, 75, 50 o 25 %.
- La exportación corre en `appScope`: termina aunque el usuario salga del editor.
- **Permisos del manifiesto: ninguno.** No hay acceso a internet.

## 7. Compatibilidad

minSdk 29 (Android 10), compileSdk y targetSdk 35 (Android 15). Pasar a 36 (Android 16) requiere AGP ≥ 8.9; ver `docs/BUILD.md`. No hay código nativo, así que funciona igual en ARM64, ARMv7 y x86_64.
