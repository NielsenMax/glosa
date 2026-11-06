# Glosa

Lector de libros para Android que explica lo que no entendés, con IA en el propio teléfono. Una *glosa* es la nota que aclara un pasaje difícil de un texto.

- Convierte PDFs (incluidos escaneos con OCR) a EPUB reflujable: texto que se adapta a la
  pantalla, tablas y figuras recortadas como imágenes, recuadros laterales como bloques aparte.
- Guarda el progreso de lectura de cada libro.
- Permite seleccionar un término o un pasaje y pedir que lo explique, usando Gemini Nano
  en el teléfono, que antes de responder busca en el libro, en tus notas, en Wikipedia, en
  Wiktionary y en la web; o pasando la consulta ya armada a la app de Claude.

## Estado

| Parte | Estado |
|---|---|
| `converter/` — conversor PDF → EPUB en Python | Funciona; probado con *Trading and Exchanges* (Harris, 2002) |
| `android/` — app | Biblioteca, lector, resaltados, notas, marcadores, búsqueda, modo oscuro, "Explicar" con Gemini Nano y herramientas de búsqueda, historial de explicaciones y pase a Claude |

## App

Kotlin + Jetpack Compose, Readium Kotlin Toolkit para leer EPUB, Room para la biblioteca y las
anotaciones, ML Kit Prompt API para Gemini Nano.

- **Biblioteca**: portadas, porcentaje leído, se ordena por último abierto. Mantener apretado un
  libro para quitarlo.
- **Lectura**: tocar los bordes pasa de página; tocar el centro muestra u oculta los controles.
  La posición se guarda sola.
- **Selección**: mantener apretado sobre el texto → Explicar, Resaltar, Nota o Copiar.
- **Explicar**: Gemini Nano responde en el teléfono. Antes de responder decide qué buscar: el
  término en el libro, tus notas y explicaciones anteriores, Wikipedia (las partes del artículo
  que tienen que ver con lo que leés), Wiktionary (las acepciones que encajan y su traducción), la
  web (con una clave gratuita de Tavily) o una cuenta. Muestra qué busca mientras lo hace y cita cada fuente; tocar
  una fuente abre el lugar del libro o la página. Se puede pedir "más simple", "un ejemplo" o
  "traducir", repreguntar, guardar la explicación como nota o pasarle todo a la app de Claude.
  Detalle en `docs/adr/0005-herramientas-del-asistente.md`.
- **Explicaciones guardadas**: cada explicación queda con sus preguntas y fuentes, marcada en
  violeta en el texto. Tocar la marca o elegirla en el panel la reabre para seguir preguntando.
- **Índice / Buscar / Notas / Explicaciones / Marcadores** en un mismo panel. Las notas se
  exportan en Markdown.
- **Apariencia**: automático (sigue al teléfono), claro, sepia u oscuro, para la biblioteca y las
  páginas a la vez. Se cambia desde Ajustes en la biblioteca o desde el panel de letra al leer.
- **Lectura**: tamaño, tipografía, interlineado, márgenes, páginas o desplazamiento, alineación.

### Instalar y actualizar

La última versión está en [Releases](https://github.com/NielsenMax/glosa/releases/latest):
descargar `Glosa.apk` en el teléfono e instalarlo. Desde ahí, Glosa avisa sola cuando hay una
versión nueva y se actualiza con un toque (la primera vez Android pide permiso para que instale
apps). Cada push a `main` publica una release; detalle en
`docs/adr/0006-actualizaciones-desde-github.md`.

### Compilar

```bash
cd android
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew testDebugUnitTest assembleRelease
```

Para firmar la release, `~/.gradle/gradle.properties` necesita `glosa.keystore`,
`glosa.keystore.password` y `glosa.key.alias`; sin eso sale `app-release-unsigned.apk`. Una
versión firmada con otra clave no se puede instalar encima de la publicada.

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
3. ~~Herramientas de búsqueda para el asistente, historial de explicaciones y modo oscuro.~~
4. ~~Actualizaciones automáticas desde GitHub Releases.~~
5. Importar PDF y convertirlo en el teléfono, portando el conversor a Kotlin sobre MuPDF.
6. Números de página impresos: el conversor emite `page-list` y el lector los muestra.
7. Glosario por libro con los términos ya explicados y tarjetas de repaso.
8. Lectura en voz alta (Readium `TtsNavigator`) y visor ampliable de figuras.
