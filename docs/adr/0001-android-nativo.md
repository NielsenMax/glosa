# 1. App Android nativa en Kotlin

Estado: propuesta

## Contexto

La app tiene que usar Gemini Nano en el dispositivo (Pixel 9 Pro XL, nano-v3). Google expone
Gemini Nano a terceros solo a través de las ML Kit GenAI APIs, que corren sobre el servicio del
sistema AICore y son bibliotecas Android nativas. La inferencia solo se permite con la app en
primer plano, y AICore aplica una cuota por app.

## Decisión

App Android nativa en Kotlin con Jetpack Compose. Sin PWA ni frameworks multiplataforma.

## Consecuencias

- Acceso directo a la Prompt API de ML Kit, sin puentes.
- Solo Android. iOS queda fuera de alcance.
- Compilar requiere JDK 17+ y Android SDK en la máquina de desarrollo.
