# Bocadillo

Editor de fotos para Android: textos, globos de diálogo, stickers, efectos y ajustes. Es una reconstrucción independiente, con código y recursos propios, de la experiencia de PicSay Pro. Funciona sin conexión, sin anuncios, sin cuentas y sin permisos.

- Análisis del APK original: [`docs/FASE1_INFORME_TECNICO.md`](docs/FASE1_INFORME_TECNICO.md)
- Arquitectura: [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md)
- Compilar y probar: [`docs/BUILD.md`](docs/BUILD.md)
- Estado actual: [`docs/ETAPA_1-2_INFORME.md`](docs/ETAPA_1-2_INFORME.md)

```bash
./gradlew :core:model:test :core:imaging:test   # pruebas del núcleo
./gradlew :app:assembleDebug                    # APK de depuración
```
