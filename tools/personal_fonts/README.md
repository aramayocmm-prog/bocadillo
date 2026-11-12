# APK personal con tu biblioteca de fuentes

El repositorio de GitHub es **público**, y la APK que publica (`apk-output`) también. Por eso esa APK solo lleva las fuentes con licencia libre (SIL OFL 1.1 y dominio público).

Tus demás fuentes se añaden a **una copia de la APK que solo es tuya**. Esa copia no se sube a ningún sitio, ni ella ni las fuentes.

## Qué fuentes van en la APK personal

| Grupo | Archivos | Tratamiento |
|---|---|---|
| Libres (OFL / dominio público) | 19 | En todas las APK |
| Uso personal, demo o sin licencia declarada, **sin prohibición expresa de incrustarlas** | 95 | Solo en tu APK personal |
| **Prohibición expresa** de incrustarlas en software: EULA de Zetafonts (112 archivos) o `fsType` = «Restricted License embedding» (17 archivos) | 129 | No se empaquetan. Se instalan en tu teléfono desde tu ZIP (Más fuentes → «Importar fuentes…» → elegir el ZIP) |
| Duplicados (OTF con la misma familia y estilo que un TTF) | 4 | No se empaquetan |

La lista completa, archivo por archivo, está en `Inventario_Fuentes_MAC_1.1.0.csv`. Empaquetar las 129 restringidas requiere autorización escrita de sus autores; con ella, basta con añadirlas a la carpeta del paso 1.

## Cómo crearla

Necesitas Python 3 con el paquete `cryptography` (`pip install cryptography`). No hace falta el SDK de Android.

1. Pon en una carpeta las fuentes del grupo «Solo en tu APK personal».
2. Ejecuta:

   ```
   python3 tools/personal_fonts/make_personal_apk.py \
       --apk MAC_Editor_para_MEMES-1.1.0-debug.apk \
       --fonts <carpeta> --keystore app/debug.keystore \
       --out MAC_Editor_para_MEMES-1.1.0-personal.apk
   ```

El script hace lo siguiente:

- copia la APK tal cual;
- añade las fuentes en `assets/fonts/personal/`, junto con un índice de familia y estilo;
- vuelve a firmar con APK Signature Scheme v2 y la **misma clave** de depuración, comprueba la firma y escribe la APK nueva.

`app/debug.keystore` está en el repositorio de GitHub. No va en el ZIP de código fuente, que nunca incluye claves.

Como la clave es la misma, la APK personal se instala encima de la 1.0 o la 1.1 sin desinstalar nada y sin perder datos. La app muestra estas fuentes en «Mi biblioteca de fuentes».

**No compartas la APK personal:** contiene fuentes que solo puedes usar tú.
