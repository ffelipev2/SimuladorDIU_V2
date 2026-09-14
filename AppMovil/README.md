# SimGYODIU — Aplicación Android

App nativa Android para:

- mostrar una o dos cámaras endoscópicas USB UVC mediante OTG;
- conectarse por Bluetooth Low Energy al prototipo con ESP32-S3;
- mostrar fuerza actual, última fuerza, zona, humedad y alarma;
- ejecutar la tara desde el teléfono;
- guiar el caso clínico seleccionado y conservar la conexión BLE hasta el simulador;
- guardar el historial local de presiones y mostrar los umbrales;
- consultar desde un icono de información el estado de BLE, HX711, humedad,
  cámaras USB y motor.

La versión actual incorpora la identidad **SimGYODIU**, icono amarillo y un
flujo clínico guiado: selección de profundidad uterina, evaluación previa,
confirmación de humedad, checklist de materiales, preparación de pinzamiento,
tara visible y simulador. El eje se posiciona automáticamente según el caso y
la conexión BLE se conserva entre estas pantallas. Bajo la cámara se debe
seleccionar un valor medido de 4 a 15 y completar Cargar el DIU, Fijar
medición, Liberar, Retiro Exitoso y Cortar Hilos para abrir el resumen final.
Al realizar la tara, la interfaz muestra un indicador de progreso, bloquea
temporalmente el botón y confirma de forma visible si el proceso terminó o si
fue interrumpido.
Si el HX711 está inicializando o no disponible, la fuerza se representa como
`--` y el botón de tara permanece deshabilitado. La conexión BLE, las cámaras,
la lectura de humedad y los controles del motor siguen disponibles.
La conexión Bluetooth es manual: la app presenta los dispositivos SimGyO
cercanos con su nombre único, dirección Bluetooth e intensidad de señal. Solo
se conecta después de que el usuario selecciona uno.
La interfaz se adapta a orientación vertical y horizontal, y la pantalla
principal es desplazable para conservar todos los controles en teléfonos de
distintos tamaños.

## Abrir el proyecto

1. Abre esta carpeta con Android Studio usando JDK 17.
2. Espera la sincronización de Gradle y ejecuta `app` en un teléfono Android.
3. Conecta el endoscopio mediante un adaptador USB OTG y concede los permisos.
4. Enciende el prototipo, pulsa **Seleccionar dispositivo** y elige el equipo
   específico de la lista.

No es necesario emparejar el ESP32 previamente desde los ajustes de Android.

## Firmware Bluetooth

El firmware correspondiente está en
`../celda_de_carga5/celda_de_carga5.ino`. Selecciona una placa ESP32-S3 en
Arduino IDE e instala las librerías **HX711** y **FastLED**. Las clases BLE
utilizadas vienen incluidas con el paquete de placas ESP32.

La comunicación entre el firmware y la app es exclusivamente BLE; el proyecto
no incluye servidor web ni utiliza LittleFS. También se retiraron del programa
la pantalla I2C, el potenciómetro y el botón físico de tara.

La detección del HX711 es no bloqueante. BLE, el motor y el sensor de humedad
arrancan aunque la celda no responda. Cuando el módulo aparece o recupera la
comunicación, el firmware lo inicializa automáticamente y calcula su tara
inicial, por lo que debe mantenerse sin carga durante ese proceso.

### Protocolo BLE

- Servicio: `4fafc201-1fb5-459e-8fcc-c5c9c331914b`
- Estado (lectura/notificación): `beb5483e-36e1-4688-b7f5-ea07361b26a8`
- Comandos (escritura): `e3223119-9445-4e96-a4a1-85358c4046a2`
- Comandos: `TARE`, `MOTOR,-1`, `MOTOR,0`, `MOTOR,1`, `CENTER`

El estado se transmite como una línea CSV:

```text
S,peso,ultimo,zona,alarma,humedad,direccion,posicion,puedeIzq,puedeDer,centrando,tara,hx711
```

El campo final `hx711` usa `0` para **no disponible**, `1` para
**inicializando** y `2` para **listo**. La app conserva compatibilidad con
firmware anterior sin este campo: mantiene la lectura y la tara, pero muestra
**Lectura activa** en verde mientras recibe telemetría válida. Un valor distinto
de `0`, `1` o `2` se considera inválido y deshabilita fuerza y tara.

La app renueva el comando del motor mientras se mantiene pulsado un botón. El
firmware detiene y desenergiza el motor si se suelta, se pierde Bluetooth o no
llega una renovación durante 800 ms.

El botón **Centrar eje** envía `CENTER`. El ESP32 mueve el motor hasta la
posición lógica 0 y se detiene automáticamente. El centrado depende del conteo
de pasos: al encender, el mecanismo debe estar físicamente en el centro porque
el prototipo no tiene sensor de posición ni finales de carrera.

## Estado del equipo

El icono de información de la pantalla principal abre un diagnóstico con:

- conexión BLE y vigencia de la telemetría;
- estado del HX711;
- lectura actual del sensor de humedad;
- cámaras USB detectadas, conectadas y vista activa;
- disponibilidad del motor y su posición lógica calculada.

El panel presenta una fila compacta por elemento, con una pelotita de color y
un texto breve: verde indica funcionamiento normal, amarillo requiere atención
o todavía no dispone de datos, y rojo identifica una alerta o fallo detectado.

El icono exterior también resume las alertas principales: Bluetooth
desconectado, humedad detectada, HX711 no disponible o un error de cámara se
marcan en rojo; la inicialización y la falta de datos se muestran en amarillo.
Con el firmware actual, mientras el HX711 esté **No disponible** o
**Inicializando**, la app muestra `--`, no registra fuerza y mantiene
deshabilitada la tara. Al
reconectar el módulo, el estado pasa automáticamente por **Inicializando** y
después a **Listo**; la celda debe permanecer sin carga durante la tara inicial.
El estado **Listo** confirma actividad del convertidor HX711, pero no detecta
todos los fallos mecánicos o de cableado de la celda.

La entrada digital D0 del sensor de humedad solo informa si hay humedad o no.
No dispone de autodiagnóstico y no permite distinguir entre una condición seca
y un cable D0 desconectado.

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

## Solución de problemas del HX711

Si la fuerza aparece como `--`, abre el icono de información y comprueba el
estado de la celda. Revisa DOUT (GPIO 4), SCK (GPIO 5), alimentación y GND. El
resto de la app y del simulador continúa disponible mientras se corrige la
conexión. Después de reconectar el módulo, mantenlo sin carga hasta que pase de
**Inicializando** a **Listo**; la recuperación no requiere reiniciar el ESP32.

## Compatibilidad USB

El teléfono debe admitir USB Host/OTG y proporcionar suficiente alimentación
al endoscopio. Algunos modelos no UVC o con formatos propietarios pueden
requerir el controlador específico del fabricante.
