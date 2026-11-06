# 5. El asistente busca antes de responder: herramientas sobre Gemini Nano

Estado: propuesta

## Contexto

Gemini Nano responde bien a "qué significa X" cuando el pasaje alcanza, pero al repreguntar se
queda sin datos: no conoce el resto del libro, inventa definiciones y hace mal las cuentas. Para
que el modelo local siga siendo el que responde (ADR 0004), tiene que poder consultar fuentes.

La Prompt API de ML Kit trae llamadas a funciones nativas (`FunctionDeclaration`,
`candidate.functionCalls`), pero solo con Gemini Nano 4, en etapa `PREVIEW`. El Pixel 9 Pro XL no
lo tiene. La ventana de contexto es de unos 4000 tokens.

## Decisión

- Un agente propio (`ExplainAgent`) en dos pasos: primero el modelo planifica qué buscar,
  después responde con lo encontrado y cita cada dato como `[n]`.
- El plan es texto plano, una línea `HERRAMIENTA: consulta` por búsqueda o `RESPONDER`, porque
  funciona con cualquier versión de Nano. Si la respuesta no respeta el formato, se responde sin
  buscar. Hasta 3 búsquedas por ronda, 2 rondas, sin repetir consultas, 12 s por búsqueda.
- Herramientas:
  - `LIBRO`: búsqueda de Readium en el propio libro; los primeros resultados suelen ser donde el
    autor define el término. Abre el lugar al tocar la fuente.
  - `WIKIPEDIA`: el artículo completo, primero en el idioma del libro y después en español. Se
    queda con la introducción y los dos párrafos que más palabras comparten con el pasaje y la
    pregunta (Lesk simplificado), así entra lo pertinente sin pasar el límite. No necesita clave.
  - `DICCIONARIO`: Wiktionary. Del inglés, las acepciones de la palabra que encajan con el pasaje,
    con el mismo criterio (una palabra común tiene decenas); del Wikcionario, sus significados en
    español. No necesita clave.
  - `WEB`: Tavily, solo si el usuario cargó una clave (plan gratuito, 1000 búsquedas por mes).
    Devuelve extractos cortos que entran en el contexto. Los buscadores sin clave (DuckDuckGo,
    Mojeek, Marginalia) bloquean a los clientes automáticos; leer su HTML haciéndose pasar por un
    navegador va contra sus términos y se rompe cuando cambian la página, así que no se hace.
  - `NOTAS`: subrayados, notas y explicaciones anteriores del mismo libro.
  - `CALCULAR`: evaluador aritmético, para que las cifras no salgan del modelo.
- La primera explicación de un término no gasta una llamada en planificar: busca el término en
  el libro, en el diccionario (si tiene hasta tres palabras) y en Wikipedia directamente. Las repreguntas sí planifican; "más simple", "un ejemplo"
  y "traducir" reutilizan lo ya encontrado.
- Cada parte del prompt tiene presupuesto en caracteres (pasaje, últimas 3 vueltas, hallazgos)
  para no pasar el límite del modelo.
- Cada explicación se guarda con sus preguntas, respuestas y fuentes, se lista en el panel de
  contenidos y queda marcada en violeta en el texto.

## Consecuencias

- Cada repregunta cuesta dos inferencias en vez de una: más latencia y más consumo de la cuota de
  AICore por app.
- Del teléfono salen solo las palabras de cada búsqueda, hacia Wikipedia, Wiktionary o Tavily; el
  pasaje y la conversación no: la elección de secciones y acepciones se hace en el teléfono.
- La calidad del plan depende de que un modelo chico siga el formato. Cuando el teléfono tenga
  Gemini Nano 4, se puede pasar el planificador a llamadas a funciones nativas sin tocar las
  herramientas.
