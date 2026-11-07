# 7. Leer un libro como PDF o como EPUB, en la misma página

Estado: propuesta

## Contexto

Un PDF importado se lee tal cual apenas llega, y una vez convertido (ADR 3) también como texto
reflujable, con letra ajustable, resaltados y "Explicar". Las figuras, tablas y fórmulas se ven
mejor en el original, así que hay que poder pasar de una forma a la otra sin perder el lugar.

## Decisión

- El PDF se muestra con el adaptador de PDFium de Readium (`readium-adapter-pdfium`), el mismo
  modelo de `Locator` que el EPUB: la posición es la página. Sus binarios están solo en JitPack,
  que se agrega restringido al grupo `com.github.marain87`.
- Cada libro guarda la ruta del PDF y la del EPUB, una posición para cada uno y con qué formato
  se abre. Un PDF que todavía se convierte, o que no se pudo convertir, se abre como PDF.
- Cambiar de formato traduce la posición con el `page-list` del EPUB convertido, sin datos
  aparte:
  - del PDF al EPUB, la página va al ancla donde empieza;
  - del EPUB al PDF, se toma el documento que se está leyendo y su avance: las páginas de un
    documento se suponen de igual largo. Un capítulo que empieza a mitad de página repite esa
    página al principio de su documento, para que el cálculo sepa dónde arranca.
- Un lector por formato (`ReaderActivity` y `PdfReaderActivity`). El que cambia guarda las dos
  posiciones, abre la otra publicación y le pasa la sesión al otro lector.
- Las páginas del PDF no tienen colores propios. Sepia y oscuro las tiñen con una matriz de color
  que lleva el blanco al papel y el negro a la tinta; en oscuro eso las invierte. El sepia tiene un
  tono de 0 a 1 elegido con un control deslizante, y el mismo papel colorea el EPUB (colores de
  fondo y de texto de Readium) y las pantallas de la app.

## Consecuencias

- Resaltados, notas, marcadores, búsqueda y "Explicar" son del EPUB; el PDF solo guarda la página.
- En el EPUB el cambio al PDF cae en la página aproximada (más o menos una) cuando las páginas de un
  capítulo tienen largos muy distintos, por ejemplo, con figuras grandes.
- En modo oscuro las fotos del PDF se ven en negativo.
