# 2. Lectura y progreso con Readium Kotlin Toolkit

Estado: propuesta

## Contexto

La app tiene que mostrar EPUB con tipografía, tamaño y modo oscuro ajustables, permitir
seleccionar texto y recordar dónde quedó cada libro. Paginar y medir la posición dentro de un
EPUB es un problema resuelto por bibliotecas maduras.

## Decisión

Usar Readium Kotlin Toolkit (licencia BSD-3) como motor de lectura. El progreso de cada libro se
guarda como el `Locator` de Readium (JSON) en una base Room local, junto con los metadatos del
libro.

## Consecuencias

- El `Locator` sobrevive a cambios de fuente y de tamaño, cosa que una página o un offset no logran.
- La selección de texto de Readium alimenta la acción "Explicar" (ADR 4).
- La base y las portadas quedan en Auto Backup de Android; los EPUB no, porque superan el límite
  de 25 MB del backup en la nube. Al reinstalar o cambiar de teléfono hay que volver a agregar
  los libros: el importador reconoce título y autor y los reconecta con su progreso y sus notas.
