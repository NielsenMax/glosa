# 6. Actualizaciones desde GitHub Releases, sin Play Store

Estado: propuesta

## Contexto

La app se instala a mano en un solo teléfono. Publicarla en Play Store pide cuenta de
desarrollador, revisión y firma gestionada por Google, para un único usuario. Android 12+ permite
que una app se actualice a sí misma (`PackageInstaller` con `USER_ACTION_NOT_REQUIRED` y el
permiso `UPDATE_PACKAGES_WITHOUT_USER_ACTION`) una vez que el usuario la autorizó a instalar apps.

## Decisión

- Cada push a `main` que toque `android/` corre las pruebas, compila el APK firmado y lo publica
  como release `v0.3.<n>` con el archivo `Glosa.apk` (`.github/workflows/release.yml`).
- `versionCode` es la cantidad de commits, así cada release supera a la anterior sin tocar nada a
  mano.
- La app consulta `releases/latest` en la API pública de GitHub al abrir la biblioteca, cada 6
  horas como mucho, y ofrece actualizar; descarga el APK y se instala sobre sí misma.
- El repositorio es público: leer releases de uno privado obliga a llevar un token dentro del APK.
- La firma usa una clave propia de Glosa (`~/.android/glosa-release.jks`, contraseña en el
  llavero de macOS). En CI llega por los secretos `GLOSA_KEYSTORE_BASE64` y
  `GLOSA_KEYSTORE_PASSWORD`. Sin la clave, la release sale sin firmar, y cualquiera puede compilar.

## Consecuencias

- Si se pierde la clave, ninguna versión nueva se puede instalar encima: habría que desinstalar y
  perder los datos. La clave y su contraseña tienen que estar respaldadas fuera de esta Mac.
- Android puede pedir confirmación igual en algunos casos; el receptor del resultado la muestra.
- La API de GitHub sin autenticar admite 60 consultas por hora por IP, de sobra para una
  consulta cada 6 horas.
