# SimGyO-DIU

SimGyO-DIU es un prototipo de simulación clínica para el entrenamiento en la
inserción de dispositivos intrauterinos (DIU). El proyecto combina una
aplicación Android con un dispositivo basado en ESP32-S3 que mide fuerza,
controla la extensión mecánica del útero simulado y transmite su estado por
Bluetooth Low Energy (BLE).

> **Importante:** este proyecto es un simulador de entrenamiento. No es un
> dispositivo médico, no realiza diagnósticos y no debe utilizarse en pacientes.

Contacto: [simgyo25@gmail.com](mailto:simgyo25@gmail.com)

## Estructura del proyecto

```text
SimuladorDIU/
├── AppMovil/                         Aplicación Android nativa
│   ├── app/src/main/                 Código Kotlin y recursos visuales
│   ├── gradlew / gradlew.bat         Gradle Wrapper
│   └── README.md                     Información específica de la app
├── celda_de_carga5/
│   ├── celda_de_carga5.ino           Firmware BLE para ESP32-S3
│   └── data/                          Interfaz web antigua, no usada por BLE
└── README.md                         Este documento
```

## Funcionalidades

### Aplicación Android

- Pantalla inicial SimGyO sin avance automático, con botón **Siguiente** y
  enlace directo al correo de contacto.
- Checklist obligatorio de seis materiales antes de ingresar al simulador:
  espéculo, solución y aplicador antiséptico, pinza Pozzi, histerómetro,
  tijeras curvas afiladas y los guantes requeridos.
- Conexión directa al ESP32-S3 por Bluetooth Low Energy; no requiere una red
  Wi-Fi ni emparejamiento previo desde los ajustes de Android.
- Visualización en tiempo real de fuerza actual, última fuerza, zona de color,
  humedad y alarma.
- Tara remota desde la aplicación.
- Control de la extensión del útero hacia la izquierda y derecha, además del
  retorno al centro lógico.
- Visualización de una o dos cámaras USB UVC conectadas mediante OTG.
- Historial local de hasta 50 registros de presión, con fecha, fuerza máxima y
  zona alcanzada.
- Diseños para teléfonos y tablets en orientación vertical y horizontal.

### Firmware ESP32-S3

- Lectura de una celda de carga mediante HX711.
- Comunicación BLE con notificaciones de estado y recepción de comandos.
- Control no bloqueante de un motor 28BYJ-48 mediante ULN2003.
- Límites lógicos de movimiento y detención automática si se pierde la
  conexión o dejan de llegar comandos.
- Tara precisa, autozero, captura de último valor estable y seguimiento de
  picos de fuerza.
- Indicador WS2812B por zona, buzzer de alarma y sensor digital de humedad.
- No utiliza pantalla I2C, potenciómetro, botón físico de tara, Wi-Fi ni
  LittleFS.

## Requisitos

### Para la aplicación

- Android Studio.
- JDK 17.
- Android SDK 35.
- Dispositivo Android 6.0 o superior (API 23).
- Bluetooth Low Energy.
- USB Host/OTG para usar la cámara endoscópica.

La aplicación actual corresponde a la versión **2.7** (`versionCode 18`).

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
- Buzzer.
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
6. Abre el monitor serie a `115200` baudios para comprobar el inicio del HX711
   y la publicidad BLE.

El factor de calibración de la celda se encuentra en el firmware:

```cpp
float CAL = 1040.6f;
```

Este valor depende de la celda, el montaje y la orientación. Debe recalibrarse
si las mediciones no corresponden a masas conocidas.

La carpeta [`celda_de_carga5/data`](./celda_de_carga5/data/) pertenece a la
versión Wi-Fi anterior y no es utilizada por el firmware BLE actual.

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

1. Enciende el prototipo con el eje físicamente centrado.
2. Abre SimGyO-DIU y pulsa **Siguiente** en la pantalla inicial.
3. Confirma los seis materiales del checklist y vuelve a pulsar **Siguiente**.
4. En la pantalla **Simulador DIU**, pulsa **Conectar dispositivo**.
5. Con el dispositivo libre de carga, pulsa **Realizar tara** y espera la
   confirmación.
6. Mantén pulsado **Izquierda** o **Derecha** para controlar la extensión.
   Suelta el botón para detener el movimiento.
7. Usa **Centrar eje** para regresar a la posición lógica inicial.
8. Conecta la cámara endoscópica mediante OTG para activar la vista USB.

## Indicadores de fuerza

| Zona | Fuerza | Indicador |
|---|---:|---|
| Azul | Menor que 40 g | LED azul |
| Amarillo | Desde 40 g y menor que 80 g | LED amarillo |
| Verde | Desde 80 g y hasta 95 g | LED verde |
| Rojo | Mayor que 95 g | LED rojo y alarma |

La humedad detectada también activa la alarma. La escala gráfica de la app se
muestra hasta `120+ g`.

Un registro de presión comienza al alcanzar `1 g` y termina cuando la fuerza
permanece en `0,5 g` o menos durante 250 ms. Los registros se guardan únicamente
en el almacenamiento privado de la aplicación y pueden borrarse desde la misma
interfaz.

## Protocolo Bluetooth Low Energy

El ESP32 anuncia el nombre `CeldaCarga-S3`. Ese nombre se conserva por
compatibilidad interna aunque la interfaz muestre la palabra **dispositivo**.

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

Mientras se mantiene pulsado un control de movimiento, la app renueva el
comando aproximadamente cada 250 ms. El firmware detiene el motor si no recibe
una renovación durante 800 ms o si se desconecta Bluetooth.

### Mensaje de estado

El firmware envía una línea CSV con este formato:

```text
S,peso,ultimo,zona,alarma,humedad,direccion,posicion,puedeIzq,puedeDer,centrando
```

Los valores booleanos se representan con `0` o `1`; la dirección utiliza
`-1`, `0` o `1`, y la posición corresponde al conteo lógico de medios pasos.

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

- Comprueba que el ESP32 muestre `Bluetooth listo: CeldaCarga-S3` en el monitor
  serie.
- Activa Bluetooth y concede los permisos solicitados por Android.
- En Android 11 o anterior también puede ser necesario activar ubicación para
  el escaneo BLE.
- No emparejes manualmente el ESP32; inicia la conexión desde la app.

### La fuerza es incorrecta o no vuelve a cero

- Revisa DOUT, SCK, alimentación y GND del HX711.
- Realiza la tara sin tocar ni cargar el dispositivo.
- Recalibra el valor `CAL` con una masa conocida.
- Verifica que la estructura no esté aplicando una precarga permanente.

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
- [Gestor BLE de Android](./AppMovil/app/src/main/java/com/felipe/endoscopeviewer/BleScaleManager.kt)
- [Interfaz principal](./AppMovil/app/src/main/java/com/felipe/endoscopeviewer/UsbCameraFragment.kt)
- [Pantalla inicial](./AppMovil/app/src/main/java/com/felipe/endoscopeviewer/SplashActivity.kt)
- [Checklist de materiales](./AppMovil/app/src/main/java/com/felipe/endoscopeviewer/MaterialChecklistActivity.kt)

