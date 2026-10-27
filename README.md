# Control Remoto L — Android (versión de prueba)

Versión 1.2 beta: Samsung con modo Selección por giroscopio/panel táctil (flechas + OK) y modo Puntero experimental; LG conserva su puntero webOS.

App Android sin anuncios, sin nube y sin cuentas. Habla directamente con TVs LG (webOS) y Samsung (Tizen) mediante WebSocket en Wi-Fi local. Interfaz en español: giroscopio, panel táctil, botón grande de clic, flechas, volumen y teclado.

## Instalar / compilar

1. Abre esta carpeta con **Android Studio** (en Windows o macOS).
2. Deja que Android Studio instale **Android SDK 35** y sincronice Gradle (plugin 8.9.2; Java 17+).
3. En el menú: **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. El resultado queda en `app/build/outputs/apk/debug/app-debug.apk`.
5. Pasa el APK al celular Android e instálalo autorizando esa fuente si Android lo solicita.

No es necesario GitHub. Como alternativa, el flujo incluido en `.github/workflows/android.yml` permite compilar un APK de prueba en GitHub Actions si voluntariamente subes el proyecto a un repositorio.

## Conectar

1. TV y Android deben estar en la **misma red Wi-Fi** (o LAN sin aislamiento entre clientes).
2. En ajustes de red de la TV, busca su dirección **IP local** (ejemplo `192.168.1.20`).
3. Abre la app, escoge **LG webOS** o **Samsung**, introduce la IP y pulsa **Conectar**.
4. **Acepta la solicitud de emparejamiento en la pantalla de la TV**.
5. Para usar el Air Mouse, activa **Mover el celular**. También puedes arrastrar el dedo en el panel táctil.
6. El botón **HACER CLIC** intenta seleccionar el elemento señalado.
7. Para escribir, abre antes un cuadro de texto en la TV, escribe en el teléfono y toca **Enviar**.

## Compatibilidad y limitaciones

- **LG webOS:** utiliza `wss:3001` con alternativa `ws:3000`; SSAP para permiso, texto y comandos; socket de puntero para clic, movimiento y navegación. Algunas pantallas/apps bloquean la entrada de teclado. El soporte varía por firmware.
- **Samsung Tizen:** Para NU7090 usa **Navegar sin clic** (predeterminado): mover el teléfono o deslizar en el panel envía únicamente flechas UP/DOWN/LEFT/RIGHT mediante Press/Release, con límites de velocidad para evitar saltos; nunca envía ENTER ni clic. Solo el botón grande confirma con OK. **Puntero (prueba)** envía eventos de mouse reales, pero pueden ser ignorados por el televisor.
- **Samsung Tizen, protocolo:** utiliza `wss:8002` con alternativa `ws:8001`; comandos de control remoto, texto por IME (SendInputString) y el comando de mouse `ProcessMouseDevice`. **No existe garantía de cursor libre ni de teclado universal en todos los modelos**. Usa las flechas como alternativa.
- La escritura requiere **un campo de texto previamente seleccionado** en la TV. Puede fallar en Netflix, YouTube u otras apps que usan teclados propios.
- El encendido desde apagado **no está implementado** (requiere Wake-on-LAN y configuración). Sí se incluye apagar la TV conectada.
- El movimiento del cursor Samsung usa coordenadas virtuales dentro de 1920×1080 como punto de partida; requiere ajustes/prueba en modelos reales.
- Se requiere Android 8.0+ (API 26) y sensor giroscópico para Air Mouse. Sin giroscopio, el panel táctil funciona.
- La app **no ha sido probada contra equipos físicos**; es una versión de prueba y su funcionamiento puede variar.

## Seguridad

- Los comandos se envían solo a direcciones privadas IPv4: 10.x, 172.16–31.x o 192.168.x.
- El emparejamiento necesita autorización de la TV. Las claves/tokens se guardan en las preferencias privadas del dispositivo Android.
- Se aceptan certificados TLS autofirmados de TVs en red local (un requisito habitual de las APIs de fabricantes). Úsala solo en una red Wi-Fi de confianza; es preferible sustituir esta estrategia por huella digital de certificado (TOFU) en una versión de producción.
- Permiso solicitado: Internet y estado de red. No usa servicios externos, analíticas ni micrófono.

## Estructura

- `app/src/main/assets/index.html` — interfaz
- `MainActivity.java` — WebView y giroscopio
- `TvClient.java` — protocolos LG/Samsung y estado
- `MiniWebSocket.java` — cliente RFC 6455 nativo y conexiones locales
- `app/build.gradle` — configuración APK

## Estado

Código fuente preparado para compilación con Android Studio. No se incluye un APK ya compilado dentro de este proyecto.
## Corrección 1.2

La ruta del giroscopio/panel táctil Samsung envía exclusivamente movimientos; no utiliza el evento de tecla `Cmd: Click`, ni `KEY_ENTER`, ni `LeftClick`. `Press` y `Release` pertenecen solo a flechas. El botón grande es la única manera de hacer clic desde el área Air Mouse. El modo Navegar sin clic resalta opciones (no dibuja un puntero libre) porque el protocolo Samsung puede rechazar el ratón en NU7090.
