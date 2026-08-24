#include "HX711.h"

// Mismos pines del firmware principal.
const int HX_DOUT = 4;
const int HX_SCK = 5;

const byte TARE_SAMPLES = 25;
const byte CAL_SAMPLES = 25;
const byte READ_SAMPLES = 5;

HX711 scale;
float calibrationFactor = 1.0f;
unsigned long lastReading = 0;

String waitLine() {
  while (!Serial.available()) delay(10);
  String text = Serial.readStringUntil('\n');
  text.trim();
  return text;
}

void waitForScale() {
  unsigned long lastMessage = 0;
  while (!scale.is_ready()) {
    if (millis() - lastMessage >= 2000) {
      lastMessage = millis();
      Serial.println("Esperando HX711... revisa DOUT, SCK, VCC y GND.");
    }
    delay(20);
  }
}

float askKnownWeight() {
  float grams = 0;
  while (grams <= 0) {
    Serial.println("Escribe el peso conocido en gramos y pulsa Enviar.");
    Serial.println("Ejemplo: 100 o 500.5");
    Serial.print("> ");
    String input = waitLine();
    input.replace(',', '.');
    grams = input.toFloat();
    if (grams <= 0) Serial.println("Valor no valido. Intenta nuevamente.");
  }
  return grams;
}

void calibrate() {
  Serial.println();
  Serial.println("========================================");
  Serial.println("  CALIBRACION GUIADA HX711 - ESP32-S3");
  Serial.println("========================================");
  Serial.println("Pines: DOUT GPIO 4 | SCK GPIO 5");
  Serial.println();
  Serial.println("PASO 1: retira todo el peso de la celda.");
  Serial.println("Cuando este libre y estable, pulsa Enviar.");
  waitLine();

  waitForScale();
  scale.set_scale(1.0f);
  Serial.println("Realizando tara...");
  scale.tare(TARE_SAMPLES);
  Serial.println("Tara terminada.");

  Serial.println();
  Serial.println("PASO 2: coloca una masa conocida y espera que se estabilice.");
  float knownWeight = askKnownWeight();

  Serial.println("Midiendo... no toques la celda.");
  delay(2000);
  waitForScale();

  long raw = scale.read_average(CAL_SAMPLES);
  float difference = (float)(raw - scale.get_offset());

  if (fabs(difference) < 100.0f) {
    Serial.println("ERROR: la lectura casi no cambio.");
    Serial.println("Revisa el cableado y pulsa Enviar para repetir.");
    waitLine();
    calibrate();
    return;
  }

  calibrationFactor = difference / knownWeight;
  scale.set_scale(calibrationFactor);

  Serial.println();
  Serial.println("PASO 3: calibracion terminada.");
  Serial.println("Copia esta linea en celda_de_carga5.ino:");
  Serial.print("float CAL = ");
  Serial.print(calibrationFactor, 6);
  Serial.println("f;");
  Serial.println("Conserva el signo del resultado.");

  float check = scale.get_units(READ_SAMPLES);
  Serial.print("Comprobacion con la masa colocada: ");
  Serial.print(check, 2);
  Serial.println(" g");
  Serial.println();
  Serial.println("Retira la masa. La lectura debe volver cerca de 0 g.");
  Serial.println("Comandos: R = repetir calibracion | T = nueva tara");
}

void setup() {
  Serial.begin(115200);
  Serial.setTimeout(1000);
  delay(800);
  scale.begin(HX_DOUT, HX_SCK);
  waitForScale();
  calibrate();
}

void loop() {
  if (Serial.available()) {
    String command = waitLine();
    command.toUpperCase();

    if (command == "R") {
      calibrate();
    } else if (command == "T") {
      Serial.println("Retira el peso y pulsa Enviar.");
      waitLine();
      waitForScale();
      scale.tare(TARE_SAMPLES);
      Serial.println("Tara terminada.");
    }
  }

  if (millis() - lastReading >= 1000 && scale.is_ready()) {
    lastReading = millis();
    Serial.print("Peso: ");
    Serial.print(scale.get_units(READ_SAMPLES), 2);
    Serial.println(" g");
  }
}
