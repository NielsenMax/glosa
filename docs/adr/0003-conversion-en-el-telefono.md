# 3. Conversión de PDF en el teléfono, portando el conversor a Kotlin sobre MuPDF

Estado: propuesta

## Contexto

`converter/convert.py` convierte un escaneo con OCR a EPUB usando PyMuPDF: líneas con posición,
fuente y estilo, más el render de recortes de página. La app tiene que poder convertir
documentos nuevos sin pasar por la computadora.

Correr Python en Android (Chaquopy) obligaría a empaquetar PyMuPDF para Android, sin soporte
oficial. MuPDF publica una biblioteca Android (`com.artifex.mupdf:fitz`) con el mismo motor
y la misma noción de texto estructurado (bloques, líneas, spans con fuente y bbox) que PyMuPDF.

## Decisión

Portar el pipeline a Kotlin sobre MuPDF Android. El script Python queda como prototipo de
escritorio: los cambios de heurística se prueban primero ahí, contra libros reales, y después se
portan.

## Consecuencias

- La conversión de un libro de 600 páginas tarda minutos en el teléfono; corre como trabajo en
  segundo plano (WorkManager) con progreso visible.
- MuPDF es AGPL. Para uso personal no hay problema. Si la app se distribuye, el código tiene
  que publicarse bajo AGPL o hay que comprar una licencia comercial.
- Los libros sin perfil se convierten con detección automática de capítulos (outline del PDF
  si existe; si no, títulos grandes en negrita). El perfil JSON sigue sirviendo para corregir a mano.
