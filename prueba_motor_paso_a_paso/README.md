# Prueba independiente del motor paso a paso

Ejemplo para el **ESP32-S3**, el **28BYJ-48** y el driver **ULN2003** del
simulador. Controla únicamente el motor desde el Monitor Serie y no requiere
librerías adicionales.

## Conexiones

| ULN2003 | ESP32-S3 |
|---|---|
| IN1 | GPIO 17 |
| IN2 | GPIO 16 |
| IN3 | GPIO 15 |
| IN4 | GPIO 18 |
| GND | GND común con el ESP32 y la fuente del motor |
| VCC / + | Fuente según la tensión nominal indicada en el motor |

Conecta el motor a su conector del ULN2003. La alimentación del motor entra
al driver; no conectes el motor directamente a los GPIO.

## Uso

1. Para esta prueba, desacopla el motor del mecanismo del simulador. El ejemplo
   no utiliza finales de carrera ni límites del eje.
2. Abre `prueba_motor_paso_a_paso.ino` en Arduino IDE.
3. Selecciona tu placa ESP32-S3 y el puerto correspondiente. Si utilizas el USB
   nativo del ESP32-S3, activa **USB CDC On Boot: Enabled** para el Monitor Serie.
4. Carga el ejemplo y abre el **Monitor Serie a 115200 baudios**. El motor queda
   detenido al encender. Envía `?` si el menú inicial no aparece.
5. Envía uno de estos comandos (se aceptan mayúsculas y minúsculas):

| Comando | Resultado |
|---|---|
| `D` | 256 medios pasos en sentido positivo y se detiene |
| `I` | 256 medios pasos en el sentido contrario y se detiene |
| `T` | 256 medios pasos de ida, pausa de 1 segundo y 256 de vuelta |
| `S` | Detiene inmediatamente el movimiento, cancela la vuelta y desenergiza las bobinas |
| `?` | Muestra los comandos |

Cada movimiento dura aproximadamente 0,77 segundos. El sentido físico de
giro depende del montaje. Al terminar se desenergizan las bobinas, por lo que
el motor no mantiene fuerza de sujeción. La vuelta de `T` usa el conteo de
pasos; no es una búsqueda de centro mediante un sensor.

Si solamente vibra, revisa el orden de IN1 a IN4 y la alimentación. Puedes
hacer una prueba más lenta aumentando `STEP_INTERVAL_US` de `3000` a `5000`.
Para cambiar el recorrido, ajusta `TEST_HALF_STEPS`.

Al cargar este ejemplo se reemplaza temporalmente el programa del ESP32.
Para volver a utilizar la app, carga nuevamente `celda_de_carga5.ino`.

## Compilación con Arduino CLI

```powershell
arduino-cli compile --fqbn esp32:esp32:esp32s3:CDCOnBoot=cdc --build-path ./build .
```
