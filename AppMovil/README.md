# Visor Endoscopio Android

App nativa Android para:

- mostrar una o dos cámaras endoscópicas USB UVC mediante OTG;
- conectarse por Bluetooth Low Energy al prototipo con ESP32-S3;
- mostrar fuerza actual, última fuerza, zona, humedad y alarma;
- ejecutar la tara desde el teléfono;
- controlar la extensión del útero hacia izquierda o derecha manteniendo presionado el botón;
- guardar el historial local de presiones y mostrar los umbrales.

La versión 2.7 incorpora la identidad visual SimGyo, una pantalla inicial sin
avance automático, un enlace de contacto por correo y una lista de preparación
previa al simulador. El botón **Siguiente** del checklist se habilita solamente
después de confirmar los seis materiales clínicos. En tablets verticales se
conserva la composición vertical del teléfono, ampliando y distribuyendo los
bloques para aprovechar toda la altura disponible.
La interfaz se adapta a orientación vertical y horizontal, y la pantalla
principal es desplazable para conservar todos los controles en teléfonos de
distintos tamaños.

## Abrir el proyecto

1. Abre esta carpeta con Android Studio usando JDK 17.
2. Espera la sincronización de Gradle y ejecuta `app` en un teléfono Android.
3. Conecta el endoscopio mediante un adaptador USB OTG y concede los permisos.
4. Enciende el prototipo y pulsa **Conectar dispositivo** en la app.

No es necesario emparejar el ESP32 previamente desde los ajustes de Android.

## Firmware Bluetooth

El firmware correspondiente está en
`../celda_de_carga5/celda_de_carga5.ino`. Selecciona una placa ESP32-S3 en
Arduino IDE e instala las librerías **HX711** y **FastLED**. Las clases BLE
utilizadas vienen incluidas con el paquete de placas ESP32.

El sketch ya no crea una red Wi-Fi, no usa LittleFS y no necesita la carpeta
`data`. También se retiraron del programa la pantalla I2C, el potenciómetro y
el botón físico de tara.

### Protocolo BLE

- Servicio: `4fafc201-1fb5-459e-8fcc-c5c9c331914b`
- Estado (lectura/notificación): `beb5483e-36e1-4688-b7f5-ea07361b26a8`
- Comandos (escritura): `e3223119-9445-4e96-a4a1-85358c4046a2`
- Comandos: `TARE`, `MOTOR,-1`, `MOTOR,0`, `MOTOR,1`, `CENTER`

La app renueva el comando del motor mientras se mantiene pulsado un botón. El
firmware detiene y desenergiza el motor si se suelta, se pierde Bluetooth o no
llega una renovación durante 800 ms.

El botón **Centrar eje** envía `CENTER`. El ESP32 mueve el motor hasta la
posición lógica 0 y se detiene automáticamente. El centrado depende del conteo
de pasos: al encender, el mecanismo debe estar físicamente en el centro porque
el prototipo no tiene sensor de posición ni finales de carrera.

## Conexiones que permanecen

- HX711: DOUT GPIO 4, SCK GPIO 5
- Buzzer: GPIO 6
- LED WS2812B: GPIO 10
- Sensor de humedad digital: GPIO 11
- ULN2003: IN1 GPIO 17, IN2 GPIO 16, IN3 GPIO 15, IN4 GPIO 18

El motor usa límites lógicos y el firmware supone que parte centrado al
encender. Si el mecanismo puede dañarse al llegar a un extremo, instala finales
de carrera físicos; los límites por software no detectan deslizamientos ni una
posición inicial incorrecta.

## Compatibilidad USB

El teléfono debe admitir USB Host/OTG y proporcionar suficiente alimentación
al endoscopio. Algunos modelos no UVC o con formatos propietarios pueden
requerir el controlador específico del fabricante.
