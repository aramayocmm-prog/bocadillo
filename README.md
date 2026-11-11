# MAC Editor para MEMES

(Proyecto técnico «Bocadillo»: el nombre interno del código y del paquete se mantiene para que la
aplicación se actualice sobre la versión instalada sin perder proyectos.)

Editor de fotos para Android: textos, globos de diálogo, stickers, efectos, ajustes, dibujo y máscaras. Es una reconstrucción independiente de la experiencia de PicSay Pro, con código y recursos propios. Funciona sin conexión, sin anuncios, sin cuentas y sin permisos especiales.

- Versión: **0.9.0** (versionCode 12) — logotipo de MAC PRODUCCIONES suministrado por su titular
- Paquete: `app.bocadillo` (la versión de depuración usa `app.bocadillo.debug`)
- Android 10 (API 29) a Android 15 (API 35)

## 1. Requisitos

| Herramienta | Versión |
|---|---|
| Android Studio | Ladybug (2024.2) o posterior |
| JDK | 17 (viene incluido en Android Studio) |
| Android SDK | Platform 35 y Build-Tools 35 (Android Studio los instala solo) |
| Gradle | 8.11.1, mediante el Gradle Wrapper incluido (`gradlew`) |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 (Compose BOM 2024.12.01) |

La **primera** compilación necesita internet para que Gradle descargue el plugin de Android, Kotlin y las librerías AndroidX. Las versiones exactas están en `gradle/libs.versions.toml`.

## 2. Abrir y ejecutar en Android Studio

1. Descomprime el ZIP.
2. En Android Studio: **File › Open…** y elige la carpeta raíz, la que contiene `settings.gradle.kts`.
3. Espera a que termine **Gradle Sync**. Si Android Studio pide instalar el SDK 35, acepta.
4. Elige un emulador o un teléfono con la depuración USB activada y pulsa **Run ▶** (configuración `app`).

## 3. Compilar desde la terminal

```bash
./gradlew :app:assembleDebug                    # APK de depuración
# resultado: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk

./gradlew :core:model:test :core:imaging:test   # pruebas unitarias del núcleo (JVM)
./gradlew :app:connectedDebugAndroidTest        # capturas de las 18 pantallas (necesita un emulador o un teléfono)
```

En Windows usa `gradlew.bat` en lugar de `./gradlew`.

## 4. Firma

- **Depuración:** el repositorio de GitHub usa una clave de depuración fija, `app/debug.keystore`, para que cada APK de CI se instale encima del anterior. **No está incluida en el ZIP de código fuente.** Sin ella, Android Studio firma con su propia clave de depuración (`~/.android/debug.keystore`). La única consecuencia es que, para instalar tu compilación sobre un APK descargado de GitHub, antes hay que desinstalar ese APK.
- **Lanzamiento:** crea tu propia clave y un archivo `keystore.properties`, que nunca se sube al repositorio. Los pasos están en [`docs/BUILD.md`](docs/BUILD.md#apk-de-lanzamiento-firmado).

## 5. Estructura del proyecto

```
settings.gradle.kts, build.gradle.kts, gradle.properties   configuración de Gradle
gradle/libs.versions.toml                                  versiones de dependencias
gradle/wrapper/, gradlew, gradlew.bat                      Gradle Wrapper
core/model/      módulo Kotlin puro: documento, historial, objetos, formato de proyecto .bcproj
core/imaging/    módulo Kotlin puro: motor de imagen y las ~70 herramientas
                 (ajustes, transformaciones, efectos, pinceles, máscaras, miniaturas de herramientas)
app/             aplicación Android (Jetpack Compose)
  src/main/AndroidManifest.xml
  src/main/kotlin/app/bocadillo/
    editor/      editor: menús, paneles de herramientas, lienzo, objetos, dibujo y máscaras
    home/        inicio, More…, Ajustes, proyectos
    data/        proyectos, exportación, fuentes, stickers, Noto Emoji, preferencias
    render/      dibujo de textos, globos y stickers
    ui/          tema clásico oscuro y componentes reutilizables
  src/main/res/          strings (en / es), iconos del lanzador, temas XML
  src/main/assets/fonts/     fuentes OFL (Poppins, Lora) y sus licencias
  src/main/assets/stickers/  catalog.json: 59 stickers vectoriales propios en 6 categorías
  src/main/assets/noto/      Noto Emoji en SVG (Apache 2.0) y avisos de licencia
  src/androidTest/       test de capturas de las 18 pantallas de referencia
tools/           build-core-offline.sh, generadores de stickers y del paquete Noto,
                 analizador de APK, comparación visual (SSIM)
docs/            análisis, arquitectura, informes de cada etapa, hojas visuales
.github/workflows/   compilación del APK y capturas en emulador (GitHub Actions)
```

## 6. Documentación

- Análisis del APK original: [`docs/FASE1_INFORME_TECNICO.md`](docs/FASE1_INFORME_TECNICO.md)
- Arquitectura: [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md)
- Compilar, firmar, añadir herramientas y pruebas manuales: [`docs/BUILD.md`](docs/BUILD.md)
- Informes de cada etapa: `docs/ETAPA_*.md` y `docs/etapa6/`
