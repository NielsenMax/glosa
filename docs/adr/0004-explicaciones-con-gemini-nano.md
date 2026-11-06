# 4. Explicaciones con Gemini Nano en el dispositivo y opción de pasar la consulta a Claude

Estado: propuesta

## Contexto

Al leer, el usuario quiere seleccionar un término o un pasaje y recibir una explicación. Las
opciones con costo (Claude API, Gemini API de pago) no entran en el presupuesto: una
suscripción a la app de Claude no incluye créditos de API. El Pixel 9 Pro XL corre Gemini Nano v3,
que la Prompt API de ML Kit expone gratis y sin conexión.

## Decisión

- Por defecto, "Explicar" arma un prompt con el texto seleccionado, el párrafo que lo rodea y el
  título del libro y del capítulo, y lo manda a Gemini Nano mediante la Prompt API de ML Kit.
- Siempre está disponible "Preguntar a Claude": el mismo prompt se comparte con un `Intent`
  `ACTION_SEND` a la app de Claude (o se copia al portapapeles), para preguntas que superen a un
  modelo chico.
- La app verifica en tiempo de ejecución si el modelo está disponible o descargándose y lo informa,
  en vez de fallar.

## Consecuencias

- Sin costo por consulta y funciona sin conexión.
- Gemini Nano es un modelo chico: alcanza para definir términos, no para análisis largos. El
  pase a Claude cubre ese hueco.
- AICore solo permite inferencia con la app en primer plano y aplica cuotas por app. Ante
  `BUSY` se reintenta con espera creciente.
