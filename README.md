# Control Remoto (Android)

App de control remoto entre dos teléfonos Android, estilo AnyDesk/TeamViewer casero.
Un mismo APK sirve como **controlador** y como **controlado**: cualquiera de los dos
teléfonos puede generar un código de sesión o ingresar uno.

## ⚠️ Esto NO es una app de vigilancia oculta

Por diseño (y porque Android no lo permite de otra forma), el teléfono que va a ser
controlado necesita, cada vez:

1. Aceptar un diálogo de consentimiento propio de la app.
2. Aceptar el permiso de **captura de pantalla** del sistema (MediaProjection) — Android
   muestra una notificación persistente mientras dura.
3. Activar manualmente el servicio de **Accesibilidad** en Ajustes (solo necesitas
   hacerlo una vez; puedes desactivarlo cuando quieras).

No hay forma de controlar un teléfono sin que su dueño vea y acepte estos tres pasos.
Si tu caso de uso necesita que la persona controlada NO se entere, esta app no sirve
para eso y no voy a ayudarte a quitarle esas protecciones.

## Qué necesitas configurar antes de compilar

1. **Proyecto de Firebase** (gratis): ve a console.firebase.google.com, crea un proyecto,
   agrega una app Android con el paquete `com.pro.remotecontrol`, descarga el
   `google-services.json` real y reemplaza `app/google-services.json.example` (quítale
   el `.example` al nombre).
2. Activa **Firestore** en modo de prueba (o configura reglas; ver abajo).
3. Sube este repo a GitHub. El workflow en `.github/workflows/build.yml` compila el
   APK automáticamente en cada push y lo deja como artifact descargable en la pestaña
   **Actions** del repo (`app-debug.apk`).

### Reglas de Firestore recomendadas (más seguras que "modo de prueba" abierto)

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /sessions/{code} {
      allow read, write: if true; // cualquiera con el código de 6 dígitos puede unirse
      match /{sub=**} {
        allow read, write: if true;
      }
    }
  }
}
```

Esto es intencionalmente abierto (como un código de invitación): la seguridad real
viene de que el código de sesión es efímero y de 6 dígitos al azar, igual que en
apps comerciales de este tipo. Para producción real, lo ideal es agregar expiración
automática de documentos (TTL policy en Firestore) y Firebase App Check.

## Cómo se usa

- **Para ser controlado**: botón "Permitir que me controlen" → aceptar el diálogo →
  aceptar captura de pantalla → (si es la primera vez) activar Accesibilidad en
  Ajustes → aparece un código de 6 dígitos.
- **Para controlar**: escribe ese código en el otro teléfono → "Controlar ese
  teléfono" → verás la pantalla remota y tus toques se enviarán a ella.

## Estado del código

Este es un proyecto completo y funcional en su arquitectura (WebRTC + Firestore +
MediaProjection + AccessibilityService), escrito a mano en este entorno **sin acceso
a internet ni a un SDK de Android para compilarlo y probarlo aquí mismo**. Es muy
probable que compile a la primera con `gradle assembleDebug`, pero si la librería
`io.getstream:stream-webrtc-android` cambió algún nombre de clase entre versiones,
puede que necesites un ajuste menor — revisa el log de errores de GitHub Actions,
son casi siempre de "no encuentra tal clase/método" y se arreglan rápido mirando la
documentación de esa librería.

## Limitaciones conocidas

- Sin audio (solo video de pantalla + toques).
- Gestos complejos (pellizcar para zoom, multitouch) no están implementados — solo
  tap/drag de un dedo.
- No hay autenticación de usuarios, cualquiera con el código de 6 dígitos se conecta.
