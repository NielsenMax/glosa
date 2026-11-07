# 3. Conversión de PDF en el teléfono, con PdfBox y el renderizador de Android

Estado: propuesta

## Contexto

`converter/convert.py` convierte un escaneo con OCR a EPUB usando PyMuPDF, pero está afinado para
un libro (perfil JSON con capítulos y páginas, filtros propios de ese escaneo). La app tiene que
convertir documentos nuevos sin pasar por la computadora y sin perfil.

Un PDF digital trae su texto exacto. Un escaneo trae fotos de las páginas y, encima, una capa de
texto invisible que generó con OCR quien lo escaneó; es lo único que se puede leer sin volver a
reconocer las fotos, y trae sus errores ("Ualue" por "Value").

La primera propuesta era portar el script a Kotlin sobre MuPDF Android, que da el mismo texto
estructurado que PyMuPDF. MuPDF es AGPL, y desde ADR 0006 el repositorio es público y las
releases se distribuyen: usarlo obligaría a licenciar toda la app bajo AGPL.

## Decisión

- El texto sale de **PdfBox-Android** (Apache 2.0): cada carácter con posición, tamaño efectivo
  y si la fuente es negrita o cursiva. Los dibujos y la portada salen de `PdfRenderer`, el PDFium
  del propio Android, más rápido y fiel que el renderizador de PdfBox.
- El conversor (`convert/`) es Kotlin sin dependencias de Android, detrás de la interfaz
  `PdfSource`, y se prueba en la JVM con un PDF armado en el test. Pasos:
  1. Rearma líneas por línea de base, sin mezclar tamaños muy distintos (una capitular o un título
     al margen comparten la línea de base con el texto).
  2. Saca encabezados y folios: el mismo texto con otro número de página, o una línea aislada que
     aparece en el mismo lugar en al menos un cuarto de las páginas.
  3. Mide el cuerpo de texto del libro (tamaño, interlineado, vocabulario para unir palabras
     cortadas con guion) y en cada página la columna, si hay dos columnas, las notas al pie y las
     notas al margen.
  4. Corrige la V que el OCR leyó como U cuando el libro escribe esa palabra con V al menos cinco
     veces: ninguna palabra en inglés o castellano difiere de otra solo en esa letra. Las palabras
     de menos de cuatro letras quedan como están, porque son siglas como "U.S." o el "v." de las
     citas judiciales.
  5. Arma párrafos por sangría, línea corta y espacio vertical, también entre páginas y columnas.
     Una palabra cortada al final de una línea conserva el guion si el libro la escribe así más
     veces que sin él, si es parte de un compuesto más largo ("over-the-counter") o si sus dos
     mitades son palabras del libro de cuatro letras o más ("liquidity-supplying"). Los títulos se
     reconocen por tamaño (niveles según los tamaños del libro) o por ser una línea corta en
     negrita o mayúsculas.
  6. Las zonas con tinta y sin texto, las tablas y las páginas casi sin prosa se recortan como
     imagen, y van después del párrafo que interrumpen.
  7. Los capítulos salen de los marcadores del PDF; sin marcadores, del nivel de título más
     profundo que deja al menos cuatro páginas por capítulo. Ningún documento pasa de 40 páginas.
  8. Cada página del PDF deja un ancla en el texto y una entrada en el `page-list` del EPUB, con
     el número de página como etiqueta (ADR 7).
- El texto no se corrige con Gemini Nano, ni siquiera solo las palabras raras que no están en un
  diccionario. En *Trading and Exchanges* esas son unas 860, y tres de cada cuatro son
  nombres y términos correctos ("Instinet", "Madoff") que un modelo tiende a "arreglar" sin
  avisar. Además, Gemini Nano no responde con la app en segundo plano, donde corre la conversión,
  y tiene un límite de batería por app.
- La conversión corre dentro del proceso de la app, de a un libro por vez, con el progreso a la
  vista. Si Android cierra la app a mitad de camino, se retoma desde cero al volver a abrirla.
  No se usa WorkManager: pediría un servicio en primer plano y permiso de notificaciones para un
  trabajo de un minuto que se puede repetir.

## Consecuencias

- *Trading and Exchanges* (657 páginas escaneadas) se convierte en un minuto y medio en una
  computadora; en el teléfono, unos minutos. *Think Python* (244 páginas digitales), en segundos.
- La calidad depende del PDF. Salvo la V leída como U, los errores del OCR quedan como están; en
  *Trading and Exchanges* hay uno cada tres páginas, más o menos ("cakulators", "Overthe"). Las
  tablas y fórmulas se ven como imagen, no como texto que se pueda seleccionar.
- Una palabra compuesta escrita sin guion y cortada al final de una línea queda con guion
  ("Mean-while") cuando el libro no la escribe entera en ninguna otra parte. En *Trading and
  Exchanges* pasa 4 veces, contra unas 30 palabras con guion que se arreglan.
- `convert.py` queda como prototipo de escritorio para el libro que tiene perfil.
- El APK pasa de 7 a 15 MB, sobre todo por PDFium y PdfBox. Solo se incluye la biblioteca nativa
  para arm64, y se excluyen las tablas de criptografía post-cuántica que trae BouncyCastle.
