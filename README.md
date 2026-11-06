# lector

Lector de libros para Android con explicaciones de IA en el dispositivo.

- Convierte PDFs (incluidos escaneos con OCR) a EPUB reflujable: texto que se adapta a la
  pantalla, tablas y figuras recortadas como imágenes, recuadros laterales como bloques aparte.
- Guarda el progreso de lectura de cada libro.
- Permite seleccionar un término o un pasaje y pedir que lo explique, usando Gemini Nano
  en el teléfono (sin costo ni conexión) o pasando la consulta ya armada a la app de Claude.

## Estado

| Parte | Estado |
|---|---|
| `converter/` — conversor PDF → EPUB en Python | Funciona; probado con *Trading and Exchanges* (Harris, 2002) |
| `android/` — app | Por empezar: falta instalar JDK y Android SDK en la Mac |

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

1. Esqueleto Android (Kotlin + Compose) que abre EPUB y guarda el progreso por libro.
2. "Explicar" sobre una selección: Gemini Nano vía ML Kit Prompt API, con la opción de
   compartir la consulta formateada a la app de Claude.
3. Importar PDF y convertirlo en el teléfono, portando el conversor a Kotlin sobre MuPDF.
4. Glosario por libro con los términos ya explicados.
