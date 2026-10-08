#include <Arduino.h>

// Prueba independiente: ESP32-S3 + ULN2003 + 28BYJ-48.
// Usa los mismos pines del simulador. Prueba con el motor desacoplado del eje.
// Monitor Serie: 115200 baudios. No comienza a moverse al encender.
// D: giro positivo | I: giro negativo | T: ida y vuelta | S: detener | ?: ayuda

constexpr uint8_t MOTOR_PINS[4] = {17, 16, 15, 18}; // IN1, IN2, IN3, IN4
constexpr uint16_t TEST_HALF_STEPS = 256;
constexpr uint32_t STEP_INTERVAL_US = 3000;
constexpr uint32_t RETURN_PAUSE_MS = 1000;

// Secuencia de ocho medios pasos; no necesita librerias adicionales.
constexpr uint8_t HALF_STEP_PATTERN[8][4] = {
  {1, 0, 0, 0},
  {1, 1, 0, 0},
  {0, 1, 0, 0},
  {0, 1, 1, 0},
  {0, 0, 1, 0},
  {0, 0, 1, 1},
  {0, 0, 0, 1},
  {1, 0, 0, 1}
};

enum class TestStage : uint8_t { IDLE, OUTBOUND, PAUSE, RETURN };
TestStage testStage = TestStage::IDLE;
uint8_t phaseIndex = 0;
int8_t motorDirection = 0;
uint16_t remainingSteps = 0;
uint32_t lastStepUs = 0;
uint32_t pauseStartedMs = 0;

void releaseMotor() {
  for (uint8_t pin : MOTOR_PINS) digitalWrite(pin, LOW);
}

void printHelp() {
  Serial.println("\nPRUEBA DEL MOTOR 28BYJ-48 / ULN2003");
  Serial.printf("D: %u medios pasos en sentido positivo\n", TEST_HALF_STEPS);
  Serial.printf("I: %u medios pasos en sentido negativo\n", TEST_HALF_STEPS);
  Serial.println("T: ida, pausa de 1 segundo y vuelta");
  Serial.println("S: detener y desenergizar las bobinas");
  Serial.println("?: mostrar esta ayuda");
}

void startMovement(int8_t direction) {
  motorDirection = direction;
  remainingSteps = TEST_HALF_STEPS;
  lastStepUs = micros();
  Serial.printf("Moviendo %u medios pasos. Sentido: %s\n",
                TEST_HALF_STEPS, direction > 0 ? "positivo" : "negativo");
}

void stopMotor() {
  remainingSteps = 0;
  motorDirection = 0;
  testStage = TestStage::IDLE;
  releaseMotor();
  Serial.println("Motor detenido. Prueba cancelada.");
}

void handleSerial() {
  while (Serial.available() > 0) {
    char command = static_cast<char>(Serial.read());
    if (command >= 'a' && command <= 'z') command -= 'a' - 'A';
    if (command == '\r' || command == '\n' || command == ' ' || command == '\t') continue;
    if (command == 'S') {
      stopMotor();
    } else if (command == '?') {
      printHelp();
    } else if (command == 'D' || command == 'I' || command == 'T') {
      if (motorDirection != 0 || testStage != TestStage::IDLE) {
        Serial.println("Motor ocupado. Envia S para detenerlo.");
        continue;
      }
      if (command == 'T') testStage = TestStage::OUTBOUND;
      startMovement(command == 'I' ? -1 : 1);
    } else {
      Serial.println("Comando desconocido. Envia ? para ver la ayuda.");
    }
  }
}

void updateMotor() {
  if (testStage == TestStage::PAUSE && millis() - pauseStartedMs >= RETURN_PAUSE_MS) {
    testStage = TestStage::RETURN;
    startMovement(-1);
  }
  if (motorDirection == 0) return;

  const uint32_t nowUs = micros();
  if (nowUs - lastStepUs < STEP_INTERVAL_US) return;
  if (remainingSteps == 0) {
    // La ultima fase tambien permanece energizada durante un intervalo completo.
    motorDirection = 0;
    releaseMotor();
    if (testStage == TestStage::OUTBOUND) {
      testStage = TestStage::PAUSE;
      pauseStartedMs = millis();
      Serial.println("Ida terminada. Pausa antes de volver...");
    } else {
      Serial.println(testStage == TestStage::RETURN ? "Prueba de ida y vuelta terminada." : "Movimiento terminado.");
      testStage = TestStage::IDLE;
    }
    return;
  }

  lastStepUs = nowUs;
  // Avanzar desde la ultima fase evita repetir una fase al invertir el sentido.
  phaseIndex = static_cast<uint8_t>((phaseIndex + motorDirection + 8) % 8);
  for (uint8_t coil = 0; coil < 4; ++coil) {
    digitalWrite(MOTOR_PINS[coil], HALF_STEP_PATTERN[phaseIndex][coil]);
  }
  --remainingSteps;
}

void setup() {
  for (uint8_t pin : MOTOR_PINS) {
    pinMode(pin, OUTPUT);
    digitalWrite(pin, LOW);
  }
  Serial.begin(115200);
  // No espera a que se abra el Monitor Serie; envia ? si no ves el menu inicial.
  printHelp();
}

void loop() {
  handleSerial();
  updateMotor();
  delay(0);
}
