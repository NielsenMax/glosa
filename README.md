# Glosa

Lector de libros para Android que explica lo que no entendés, con IA en el propio teléfono. Una *glosa* es la nota que aclara un pasaje difícil de un texto.

- Convierte PDFs (incluidos escaneos con OCR) a EPUB reflujable: texto que se adapta a la
  pantalla, tablas y figuras recortadas como imágenes, recuadros laterales como bloques aparte.
- Guarda el progreso de lectura de cada libro.
- Permite seleccionar un término o un pasaje y pedir que lo explique, usando Gemini Nano
  en el teléfono (sin costo ni conexión) o pasando la consulta ya armada a la app de Claude.

## Estado

| Parte | Estado |
|---|---|
| `converter/` — conversor PDF → EPUB en Python | Funciona; probado con *Trading and Exchanges* (Harris, 2002) |
| `android/` — app | Biblioteca, lector, resaltados, notas, marcadores, búsqueda, ajustes de lectura y "Explicar" con Gemini Nano + pase a Claude |

## App

Kotlin + Jetpack Compose, Readium Kotlin Toolkit para leer EPUB, Room para la biblioteca y las
anotaciones, ML Kit Prompt API para Gemini Nano.

- **Biblioteca**: portadas, porcentaje leído, se ordena por último abierto. Mantener apretado un
  libro para quitarlo.
- **Lectura**: tocar los bordes pasa de página; tocar el centro muestra u oculta los controles.
  La posición se guarda sola.
- **Selección**: mantener apretado sobre el texto → Explicar, Resaltar, Nota o Copiar.
- **Explicar**: Gemini Nano responde en el teléfono, sin conexión. Se puede pedir "más simple",
  "un ejemplo" o "traducir", repreguntar, guardar la explicación como nota o pasarle todo a la
  app de Claude con un toque.
- **Índice / Buscar / Notas / Marcadores** en un mismo panel. Las notas se exportan en Markdown.
- **Ajustes**: tamaño, tema (claro, sepia, oscuro con figuras atenuadas), tipografía,
  interlineado, márgenes, páginas o desplazamiento, alineación.

```bash
cd android
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew testDebugUnitTest assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

La release se firma con la clave de debug: sirve para instalar en el teléfono propio, no para
publicar.

## Conversor

```bash
python3 -m venv .venv && .venv/bin/pip install -r converter/requirements.txt
.venv/bin/python -I converter/convert.py libro.pdf converter/profiles/<perfil>.json libro.epub
```

El perfil (`converter/profiles/*.json`) aporta lo que el escaneo no dice de forma confiable:
metadatos, qué páginas conservar, dónde empieza cada capítulo o parte, y desde qué página la
sangría es francesa (bibliografía). El resto se deduce de la geometría y las fuentes del OCR:

1. Lee las líneas del texto OCR con posición, tamaño y estilo; descarta encabezados y folios.
2. Separa la columna principal del margen exterior, donde van los recuadros y algunas figuras.
3. Agrupa las líneas en filas y las filas en bandas separadas por espacio vertical. Clasifica cada
   banda como texto, epígrafe (`TABLE n-m.` / `FIGURE n-m.`) o gráfico (filas en columnas o casi
   sin letras). Las zonas con tinta y sin texto también cuentan como gráfico.
4. Rearma los párrafos por sangría y por líneas cortas, también entre páginas. Une las palabras
   cortadas con guion consultando el vocabulario del propio libro.
5. Recorta los gráficos de la página escaneada y los ubica después del párrafo que interrumpen.

## Plan de la app

Ver las decisiones en `docs/adr/`. En orden:

1. ~~App que abre EPUB y guarda el progreso por libro.~~
2. ~~"Explicar" sobre una selección con Gemini Nano y pase a Claude.~~
3. Importar PDF y convertirlo en el teléfono, portando el conversor a Kotlin sobre MuPDF.
4. Números de página impresos: el conversor emite `page-list` y el lector los muestra.
5. Glosario por libro con los términos ya explicados y tarjetas de repaso.
6. Lectura en voz alta (Readium `TtsNavigator`) y visor ampliable de figuras.
