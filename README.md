# SimGyO-DIU

SimGyO-DIU es un prototipo de simulación clínica para el entrenamiento en la
inserción de dispositivos intrauterinos (DIU). El proyecto combina una
aplicación Android con un dispositivo basado en ESP32-S3 que mide fuerza,
controla la extensión mecánica del útero simulado y transmite su estado por
Bluetooth Low Energy (BLE).

> **Importante:** este proyecto es un simulador de entrenamiento. No es un
> dispositivo médico, no realiza diagnósticos y no debe utilizarse en pacientes.

Contacto: [simgyo25@gmail.com](mailto:simgyo25@gmail.com)

## Flujo clínico actual de la app

La aplicación se identifica como **SimGYODIU** y utiliza un icono con fondo
amarillo. El entrenamiento guiado actual sigue esta secuencia:

1. Bienvenida y selección del caso clínico: **5 cm**, **8 cm** o **12 cm**.
   Los casos se habilitan únicamente tras conectar el ESP32 por BLE. El caso
   elegido se confirma visualmente y el firmware centra el eje para luego
   posicionarlo de forma proporcional: 12 cm queda en el centro, 8 cm usa una
   apertura intermedia y 5 cm la apertura máxima.
2. Evaluación previa: descarte de embarazo/contraindicaciones y examen
   bimanual.
3. Preparación con espéculo, limpieza antiséptica y confirmación de la alarma
   de humedad.
4. Checklist obligatorio de materiales.
5. Preparación de pinzamiento y tracción, confirmación del sonido de la pinza
   y tara visible antes de ingresar al simulador.
6. Simulador DIU: la conexión BLE se conserva entre las pantallas, se muestra
   fuerza, cámara USB, estado de sensores, selector de **valor medido** (4 a
   15) y el checklist final: cargar el DIU, fijar medición, liberar, retiro
   exitoso y cortar hilos.
7. Resumen final con valor medido, diferencia frente a la profundidad elegida
   y el estado de cada confirmación registrada durante el procedimiento.

La búsqueda BLE reintenta automáticamente una vez cuando el primer enlace
GATT no termina de establecerse, evitando tener que seleccionar dos veces el
mismo dispositivo.

## Estructura del proyecto

```text
SimuladorDIU/
├── AppMovil/                         Aplicación Android nativa
│   ├── app/src/main/                 Código Kotlin y recursos visuales
│   ├── gradlew / gradlew.bat         Gradle Wrapper
│   └── README.md                     Información específica de la app
├── celda_de_carga5/
│   └── celda_de_carga5.ino           Firmware BLE para ESP32-S3
├── calibracion_hx711/
│   └── calibracion_hx711.ino          Calibración guiada desde Monitor Serie
└── README.md                         Este documento
```

## Funcionalidades

### Aplicación Android

- Pantalla inicial SimGyO sin avance automático, con botón **Siguiente** y
  enlace directo al correo de contacto.
- Checklist obligatorio de seis materiales antes de ingresar al simulador:
  espéculo, solución y aplicador antiséptico, pinza Pozzi, histerómetro,
  tijeras curvas afiladas y los guantes requeridos.
- Conexión directa al ESP32-S3 exclusivamente por Bluetooth Low Energy; no
  requiere una red Wi-Fi ni emparejamiento previo desde los ajustes de Android.
- Selector manual de dispositivos cercanos, mostrando nombre único, dirección
  Bluetooth e intensidad de señal antes de conectar.
- Visualización en tiempo real de fuerza actual, última fuerza, zona de color,
  humedad y alarma; con el firmware actual, si el HX711 está no disponible o
  inicializando, la fuerza se muestra como `--`.
- Tara remota con indicador de progreso y confirmación explícita al finalizar;
  el control se deshabilita mientras el HX711 no esté listo.
- Icono de información con filas visuales para BLE, HX711, humedad, cámaras USB
  y motor; cada fila usa verde, amarillo o rojo junto con un estado breve.
- Selección de caso clínico que ordena al firmware centrar y posicionar el
  eje mediante el comando BLE `CASE,pasos`.
- Flujo de preparación clínica con confirmaciones de humedad, pinza, tara y
  checklist final antes del resumen del procedimiento.
- Visualización de una o dos cámaras USB UVC conectadas mediante OTG.
- Historial local de hasta 50 registros de presión, con fecha, fuerza máxima y
  zona alcanzada.
- Diseños para teléfonos y tablets en orientación vertical y horizontal.

### Firmware ESP32-S3

- Detección e inicialización no bloqueante del HX711, con recuperación
  automática si el módulo se conecta o vuelve a responder.
- Comunicación BLE con notificaciones de estado y recepción de comandos.
- Control no bloqueante de un motor 28BYJ-48 mediante ULN2003.
- Límites lógicos de movimiento y detención automática si se pierde la
  conexión o dejan de llegar comandos.
- Tara precisa, autozero, captura de último valor estable y seguimiento de
  picos de fuerza.
- Indicador WS2812B por zona, buzzer pasivo de alarma y sensor digital de
  humedad.
- Comunicación exclusivamente por BLE, sin servidor web ni LittleFS.
- No utiliza pantalla I2C, potenciómetro ni botón físico de tara.

## Requisitos

### Para la aplicación

- Android Studio.
- JDK 17.
- Android SDK 35.
- Dispositivo Android 6.0 o superior (API 23).
- Bluetooth Low Energy.
- USB Host/OTG para usar la cámara endoscópica.

La aplicación actual corresponde a la versión **2.9** (`versionCode 20`).

### Para el firmware

- Arduino IDE 2.x o una instalación equivalente de Arduino CLI.
- Paquete de placas **ESP32 by Espressif Systems** con soporte para ESP32-S3.
- Librería **HX711**.
- Librería **FastLED**.
- Las clases BLE utilizadas están incluidas en el paquete de placas ESP32.

## Hardware principal

- ESP32-S3.
- Celda de carga con módulo HX711.
- Motor paso a paso 28BYJ-48 con controlador ULN2003.
- Un LED direccionable WS2812B.
- Buzzer pasivo.
- Sensor digital de humedad HW-08 o equivalente con salida D0.
- Teléfono o tablet Android con BLE.
- Cámara endoscópica USB UVC y adaptador USB OTG, si se utilizará video.

## Conexiones del ESP32-S3

| Componente | Señal | GPIO |
|---|---|---:|
| HX711 | DOUT | 4 |
| HX711 | SCK | 5 |
| Buzzer | Señal | 6 |
| WS2812B | DIN | 10 |
| Sensor de humedad | D0 | 11 |
| ULN2003 | IN1 | 17 |
| ULN2003 | IN2 | 16 |
| ULN2003 | IN3 | 15 |
| ULN2003 | IN4 | 18 |

Todos los módulos deben compartir GND. Alimenta el motor y los periféricos de
acuerdo con sus especificaciones; no alimentes el motor directamente desde un
GPIO del ESP32.

## Instalación del firmware

1. Abre
   [`celda_de_carga5/celda_de_carga5.ino`](./celda_de_carga5/celda_de_carga5.ino)
   en Arduino IDE.
2. Instala las librerías **HX711** y **FastLED** desde el gestor de librerías.
3. Selecciona la placa correspondiente, normalmente **ESP32S3 Dev Module**, y
   el puerto serie correcto.
4. Revisa el pinout y coloca físicamente el eje en el centro antes de encender.
5. Compila y carga el firmware.
6. Abre el monitor serie a `115200` baudios. La publicidad BLE debe comenzar
   aunque el HX711 no responda; el monitor informa si está inicializando, listo
   o no disponible.

Para obtener el factor de calibración utiliza primero el sketch independiente
[`calibracion_hx711/calibracion_hx711.ino`](./calibracion_hx711/calibracion_hx711.ino):

1. Cárgalo en el ESP32-S3 con la celda y el HX711 conectados.
2. Abre el Monitor Serie a `115200` baudios.
3. Sigue la guía para realizar la tara y coloca una masa de valor conocido.
4. Escribe el valor de la masa en gramos.
5. Copia la línea `float CAL = ...;` que entrega el monitor al firmware
   principal.

El sketch de calibración es una herramienta independiente y sí requiere que el
HX711 esté conectado, ya que no tiene otra función. No se ejecuta junto con el
firmware operativo ni afecta al arranque no bloqueante del simulador.

El factor utilizado por el firmware se encuentra en:

```cpp
float CAL = 207.395996f;
```

Este valor depende de la celda, el montaje y la orientación. Debe recalibrarse
si las mediciones no corresponden a masas conocidas.

Para la celda actual de 10 kg, el firmware aplica una mediana móvil de tres
lecturas y una zona cero con histéresis. Permanece en 0,00 g mientras el ruido
no supere 0,80 g y vuelve a bloquear el cero al bajar de 0,55 g. Estos límites
se configuran con ZERO_UNLOCK_G y ZERO_RELOCK_G.

La detección del HX711 no bloquea el arranque. El firmware inicia BLE, el motor
y la lectura de humedad aunque la celda no esté disponible. Al detectar el
HX711 toma muestras para calcular la tara inicial; por eso la celda debe quedar
sin carga mientras su estado sea `inicializando`. Si el módulo deja de responder
y luego se recupera, la detección y esa tara inicial se repiten automáticamente.

## Instalación de la aplicación

1. Abre la carpeta [`AppMovil`](./AppMovil/) como proyecto en Android Studio.
2. Configura Android Studio para utilizar JDK 17.
3. Espera la sincronización de Gradle y la descarga de dependencias.
4. Conecta un teléfono o tablet Android con la depuración USB habilitada.
5. Ejecuta la configuración `app` y concede los permisos solicitados.

También se puede verificar o compilar desde una terminal.

En Windows:

```powershell
cd AppMovil
.\gradlew.bat :app:compileDebugKotlin :app:lintDebug
.\gradlew.bat :app:assembleDebug
```

En macOS o Linux:

```bash
cd AppMovil
./gradlew :app:compileDebugKotlin :app:lintDebug
./gradlew :app:assembleDebug
```

El APK de depuración generado queda en:

```text
AppMovil/app/build/outputs/apk/debug/app-debug.apk
```

## Uso básico

1. Enciende el prototipo con el eje físicamente centrado y la celda de carga
   libre de fuerza mientras termina su inicialización.
2. Abre SimGyO-DIU y pulsa **Siguiente** en la pantalla inicial.
3. Confirma los seis materiales del checklist y vuelve a pulsar **Siguiente**.
4. En la pantalla **Simulador DIU**, pulsa **Seleccionar dispositivo** y elige
   el simulador específico de la lista.
5. Pulsa el icono de información para revisar BLE, HX711, humedad, cámaras y
   motor. Espera a que el HX711 indique **Listo** antes de medir.
6. Si necesitas repetir la tara, deja el dispositivo libre de carga, pulsa
   **Realizar tara** y espera la confirmación.
7. Mantén pulsado **Izquierda** o **Derecha** para controlar la extensión.
   Suelta el botón para detener el movimiento.
8. Usa **Centrar eje** para regresar a la posición lógica inicial.
9. Conecta la cámara endoscópica mediante OTG para activar la vista USB.

Con el firmware actual, si el HX711 informa **No disponible** o
**Inicializando**, la app muestra `--` en lugar de una fuerza válida y
deshabilita la tara. BLE, el diagnóstico, las cámaras, la lectura de humedad y
el control del motor continúan disponibles. Con firmware antiguo sin el campo
de diagnóstico, la app conserva la lectura y la tara por compatibilidad y
muestra **Lectura activa** en verde mientras recibe telemetría válida.

## Indicadores de fuerza

| Zona | Fuerza | Indicador |
|---|---:|---|
| Azul | Menor que 40 g | LED azul |
| Amarillo | Desde 40 g y menor que 80 g | LED amarillo |
| Verde | Desde 80 g y hasta 95 g | LED verde |
| Rojo | Mayor que 95 g | LED rojo y alarma |

La humedad detectada también activa la alarma. La escala gráfica de la app se
muestra hasta `120+ g`.

Cuando el HX711 está inicializando o no disponible, no se generan registros ni
alarmas de fuerza. La lectura y la alarma de humedad siguen funcionando.

El buzzer pasivo conectado a GPIO 6 recibe una señal de `2400 Hz` en intervalos
de 200 ms mientras la alarma está activa. La frecuencia se puede ajustar con
`BUZZER_FREQUENCY_HZ` sin cambiar el pin.

Un registro de presión comienza al alcanzar `1 g` y termina cuando la fuerza
permanece en `0,5 g` o menos durante 250 ms. Los registros se guardan únicamente
en el almacenamiento privado de la aplicación y pueden borrarse desde la misma
interfaz.

## Protocolo Bluetooth Low Energy

Cada ESP32 anuncia automáticamente un nombre como `SimGyO-DIU-A1B2C3`. Los
seis caracteres finales se obtienen del identificador de la placa y permiten
diferenciar físicamente varios simuladores. La app conserva compatibilidad con
firmware anterior que anuncie `CeldaCarga-S3` o `CeldaCarga-C3`.

### Uso con varios dispositivos

- La app no se conecta al primer equipo detectado. El usuario debe elegirlo en
  la lista; la señal en dBm ayuda a reconocer el equipo más cercano.
- Conviene colocar una etiqueta física en cada simulador con el sufijo de su
  nombre Bluetooth, por ejemplo `A1B2C3`.
- Cada teléfono debe conceder sus propios permisos de Bluetooth. No se requiere
  emparejar manualmente desde los ajustes de Android.
- Se recomienda un teléfono o tablet activo por simulador. Un equipo conectado
  deja de anunciarse normalmente, evitando que otro usuario lo seleccione.
- Para distribuir actualizaciones de la app, deben conservarse el mismo
  `applicationId`, la misma clave de firma y un `versionCode` creciente.

| Elemento | UUID | Propiedades |
|---|---|---|
| Servicio | `4fafc201-1fb5-459e-8fcc-c5c9c331914b` | Servicio principal |
| Estado | `beb5483e-36e1-4688-b7f5-ea07361b26a8` | Read, Notify |
| Comandos | `e3223119-9445-4e96-a4a1-85358c4046a2` | Write, Write without response |

### Comandos

| Comando | Acción |
|---|---|
| `TARE` | Realiza la tara y detiene el motor |
| `MOTOR,-1` | Mueve hacia la izquierda |
| `MOTOR,0` | Detiene y desenergiza el motor |
| `MOTOR,1` | Mueve hacia la derecha |
| `CENTER` | Regresa a la posición lógica 0 |
| `CASE,pasos` | Centra el eje y lo posiciona en el número de medios pasos indicado |

Mientras se mantiene pulsado un control de movimiento, la app renueva el
comando aproximadamente cada 250 ms. El firmware detiene el motor si no recibe
una renovación durante 800 ms o si se desconecta Bluetooth.

### Mensaje de estado

El firmware envía una línea CSV con este formato:

```text
S,peso,ultimo,zona,alarma,humedad,direccion,posicion,puedeIzq,puedeDer,centrando,tara,hx711
```

Los valores booleanos se representan con `0` o `1`; la dirección utiliza
`-1`, `0` o `1`, y la posición corresponde al conteo lógico de medios pasos.
El campo `tara` vale `1` mientras el ESP32 está realizando la tara y cambia a
`0` al finalizar. La app utiliza esa transición para mostrar el progreso y
confirmar el resultado. El campo final `hx711` representa el estado de la celda:
`0` significa **no disponible**, `1` **inicializando** y `2` **listo**. La app
acepta mensajes de firmware anterior que no incluyan este último campo y en ese
caso permite la lectura y la tara por compatibilidad y muestra **Lectura
activa** en verde. Si el campo existe pero contiene otro valor, la app lo trata
como inválido y bloquea fuerza y tara.

El estado **listo** confirma que el firmware recibe conversiones del HX711; no
puede garantizar por sí solo la integridad mecánica de la celda ni detectar
todos los fallos de su puente o cableado.

El campo `humedad` comunica únicamente la lectura digital de D0 (`1` cuando se
detecta humedad). Esa señal no ofrece autodiagnóstico: con la conexión actual no
es posible distinguir por software entre una condición seca y el cable D0
desconectado.

## Seguridad del movimiento

El firmware supone que la posición del eje al encender es el centro lógico
(`0`). El comando `CENTER` vuelve a esa posición usando solamente el conteo de
pasos. El prototipo no realiza homing y no detecta deslizamientos, bloqueos ni
una posición inicial incorrecta.

Los límites actuales son lógicos y no sustituyen finales de carrera físicos.
Si el mecanismo puede dañarse en los extremos, se deben instalar sensores de
posición o finales de carrera y adaptar el firmware antes de continuar las
pruebas.

## Solución de problemas

### La app no encuentra el dispositivo

- Comprueba que el ESP32 muestre un nombre como
  `Bluetooth listo: SimGyO-DIU-A1B2C3` en el monitor serie.
- Activa Bluetooth y concede los permisos solicitados por Android.
- En Android 11 o anterior también puede ser necesario activar ubicación para
  el escaneo BLE.
- No emparejes manualmente el ESP32; inicia la conexión desde la app.

### La fuerza es incorrecta o no vuelve a cero

- Revisa DOUT, SCK, alimentación y GND del HX711.
- Realiza la tara sin tocar ni cargar el dispositivo.
- Recalibra el valor `CAL` con una masa conocida.
- Verifica que la estructura no esté aplicando una precarga permanente.

### La app muestra `--` o HX711 no disponible

- Abre el icono de información y revisa el estado detallado de la celda.
- Comprueba DOUT (GPIO 4), SCK (GPIO 5), alimentación y GND del HX711.
- El simulador continúa conectado por BLE y permite usar el motor, las cámaras y
  la lectura de humedad; solamente se suspenden la fuerza, su alarma, el
  historial asociado y la tara.
- Después de corregir la conexión, deja la celda sin carga. El firmware la
  detectará, mostrará **Inicializando**, realizará la tara inicial y cambiará a
  **Listo** sin reiniciar el ESP32.

### El eje no centra correctamente

- Apaga el equipo, coloca físicamente el eje en el centro y vuelve a encender.
- Revisa el orden de IN1–IN4 y la alimentación del ULN2003.
- Recuerda que el centro actual depende del conteo de pasos y no de un sensor.

### La cámara USB no aparece

- Confirma que el dispositivo Android admita USB Host/OTG.
- Usa una cámara compatible con UVC.
- Concede el permiso USB cuando Android lo solicite.
- Si la cámara consume demasiada corriente, utiliza un hub OTG alimentado.

## Archivos principales

- [Firmware ESP32-S3](./celda_de_carga5/celda_de_carga5.ino)
- [Calibración guiada del HX711](./calibracion_hx711/calibracion_hx711.ino)
- [Gestor BLE de Android](./AppMovil/app/src/main/java/com/felipe/endoscopeviewer/BleScaleManager.kt)
- [Interfaz principal](./AppMovil/app/src/main/java/com/felipe/endoscopeviewer/UsbCameraFragment.kt)
- [Pantalla inicial](./AppMovil/app/src/main/java/com/felipe/endoscopeviewer/SplashActivity.kt)
- [Checklist de materiales](./AppMovil/app/src/main/java/com/felipe/endoscopeviewer/MaterialChecklistActivity.kt)
