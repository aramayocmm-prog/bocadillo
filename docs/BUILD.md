# Compilar, probar y mantener Bocadillo

## Requisitos
- Android Studio Ladybug (2024.2) o posterior, con JDK 17 incluido.
- Android SDK Platform 35 y Build-Tools 35 (Android Studio los instala solo).
- Conexión a internet **la primera vez**, para que Gradle descargue AGP 8.7.3, Kotlin 2.0.21 y las librerías AndroidX.

## Compilar el APK de depuración
```bash
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
También se puede abrir la carpeta del proyecto en Android Studio y pulsar **Run**.

## Pruebas unitarias (núcleo, JVM)
```bash
./gradlew :core:model:test :core:imaging:test
```
La prueba `VisualAndPerformanceTest` genera dos archivos en `core/imaging/build/visual/` (directorio de trabajo de Gradle):
- `contact_sheet_stage2.png`: una hoja con cada herramienta aplicada a una escena de prueba.
- `performance_12mp.csv`: los tiempos de cada operación sobre una imagen de 12 MP.

### Sin red ni Android SDK
`tools/build-core-offline.sh` compila y ejecuta las pruebas del núcleo con el compilador Kotlin que trae una instalación local de Gradle (por defecto `/opt/gradle-8.14.3/lib`):
```bash
tools/build-core-offline.sh /ruta/a/gradle/lib
```

## APK de lanzamiento firmado
1. Crea una clave, si no la tienes:
   ```bash
   keytool -genkeypair -v -keystore bocadillo.jks -keyalg RSA -keysize 4096 -validity 10000 -alias bocadillo
   ```
2. Crea `keystore.properties` en la raíz del proyecto. Está en `.gitignore`: **no lo subas al repositorio**.
   ```
   storeFile=bocadillo.jks
   storePassword=…
   keyAlias=bocadillo
   keyPassword=…
   ```
3. Compila:
   ```bash
   ./gradlew :app:assembleRelease
   # → app/build/outputs/apk/release/app-release.apk (firma v2/v3)
   ```

## Pasar a Android 16 (API 36)
Sube `agp` a ≥ 8.9 en `gradle/libs.versions.toml` y, si AGP lo pide, el wrapper de Gradle (`./gradlew wrapper --gradle-version <versión>`). Después, en `app/build.gradle.kts`, pon `compileSdk = 36` y `targetSdk = 36`. La app ya usa edge-to-edge y "atrás predictivo", que en Android 15 y 16 son obligatorios u opcionales según la versión.

## Añadir una herramienta
1. Implementa `ImageOp` en `core/imaging/.../ops/` con un `id` nuevo y estable.
2. Regístrala en `OpRegistry.default()`.
3. Añade las etiquetas en `app/.../editor/Labels.kt` y en `values/strings.xml` y `values-es/strings.xml`.
4. Añade pruebas en `core/imaging/src/test`. Los controles de la interfaz se generan solos a partir de `ParamSpec`.

## Lista de pruebas manuales en dispositivo (FASE 11)
Marca cada punto en un dispositivo con Android 10 y en otro con Android 15:
1. Abrir una foto desde la galería, la cámara, «Compartir con» desde otra app y «Editar con».
2. Aplicar cada ajuste y cada transformación; comprobar la vista previa en vivo y el botón Comparar.
3. Deshacer y rehacer varios pasos; usar Restaurar original y deshacerlo.
4. Recortar con cada proporción y enderezar ±10°.
5. Redimensionar en los 5 modos; Ampliar bordes con color.
6. Exportar en JPEG (alta y media), en PNG con transparencia (lienzo transparente) y en "Igual que el original", a la galería, con Guardar como… y con Compartir.
7. Foto de ≥ 12 MP: tiempo de exportación y que no haya cierre por memoria.
8. Rotar el dispositivo durante la edición (vertical y horizontal).
9. Cerrar la app desde Recientes con un proyecto abierto → al volver debe ofrecer recuperarlo.
10. Favoritos, Duplicar y Eliminar en la pantalla de inicio.
