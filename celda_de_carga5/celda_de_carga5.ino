#include "HX711.h"
#include <math.h>

#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <FastLED.h>

// =====================================================
//              PINOUT ESP32-S3 (DevKit típico)
// =====================================================
// La pantalla I2C, el potenciómetro y el botón de tara ya no se usan.
// Toda la interacción del usuario se realiza desde la app Android por BLE.
static const int HX_DOUT = 4;
static const int HX_SCK = 5;
static const int BUZZER_PIN = 6;

// Sensor de humedad digital HW-08: conectar D0 a este GPIO.
static const int HUMIDITY_PIN = 11;
static const bool HUMIDITY_ACTIVE_LOW = true;

// WS2812B (DIN)
#define LED_PIN 10
#define NUM_LEDS 1
CRGB leds[NUM_LEDS];

// Motor paso a paso con driver ULN2003 y motor 28BYJ-48.
static const int STEPPER_IN1 = 17;
static const int STEPPER_IN2 = 16;
static const int STEPPER_IN3 = 15;
static const int STEPPER_IN4 = 18;

// =====================================================
//                  BLUETOOTH LOW ENERGY
// =====================================================
// Estos UUID deben coincidir con BleScaleManager.kt.
static const char* BLE_DEVICE_NAME = "CeldaCarga-S3";
static const char* BLE_SERVICE_UUID = "4fafc201-1fb5-459e-8fcc-c5c9c331914b";
static const char* BLE_STATE_UUID = "beb5483e-36e1-4688-b7f5-ea07361b26a8";
static const char* BLE_COMMAND_UUID = "e3223119-9445-4e96-a4a1-85358c4046a2";

BLECharacteristic* stateCharacteristic = nullptr;
volatile bool bleConnected = false;
volatile bool tareRequested = false;

// =====================================================
//                      HX711
// =====================================================
HX711 scale;
float CAL = 1040.6f;

const float DEADBAND_G = 0.3f;
float prettyZero(float grams) {
  return fabs(grams) < DEADBAND_G ? 0.0f : grams;
}

float peso_g_now = 0.0f;
float peso_g_last = 0.0f;
bool alarmOn = false;
bool humidityDetected = false;

// =====================================================
//                    TEMPORIZADORES
// =====================================================
const unsigned long PERIOD_PESO_MS = 40;
const unsigned long PERIOD_SERIAL_MS = 300;
const unsigned long PERIOD_BLE_MS = 200;
const unsigned long PERIOD_LED_MS = 80;
const unsigned long PERIOD_HUMIDITY_MS = 50;

unsigned long tPeso = 0;
unsigned long tSerial = 0;
unsigned long tBle = 0;
unsigned long tLed = 0;
unsigned long tHumidity = 0;

// =====================================================
//             MOTOR PASO A PASO DESDE LA APP
// =====================================================
const int STEPPER_STEPS_PER_MOVE = 2048 / 9;
const long STEPPER_LIMIT_HALFSTEPS = (long)STEPPER_STEPS_PER_MOVE * 8L;

// Se supone que al encender el equipo el motor está centrado.
// Estos límites son lógicos, no reemplazan finales de carrera físicos.
const long STEPPER_LEFT_LIMIT_HALFSTEPS = -STEPPER_LIMIT_HALFSTEPS;
const long STEPPER_RIGHT_LIMIT_HALFSTEPS = STEPPER_LIMIT_HALFSTEPS;
const unsigned long STEPPER_STEP_US = 2000;

// La app renueva el comando mientras el botón está presionado. Si se pierde
// Bluetooth o dejan de llegar renovaciones, el motor se detiene solo.
const unsigned long STEPPER_COMMAND_TIMEOUT_MS = 800;

const int stepperPattern[8][4] = {
  { 1, 0, 0, 0 },
  { 1, 1, 0, 0 },
  { 0, 1, 0, 0 },
  { 0, 1, 1, 0 },
  { 0, 0, 1, 0 },
  { 0, 0, 1, 1 },
  { 0, 0, 0, 1 },
  { 1, 0, 0, 1 }
};

long stepperPositionHalfSteps = 0;
volatile int stepperBleDirection = 0;
volatile unsigned long stepperBleUntil = 0;
volatile int stepperRequestedDirection = 0;
volatile bool stepperCentering = false;
int stepperPatternIndex = 0;
unsigned long tStepperUs = 0;

void setStepperPattern(int patternIndex) {
  digitalWrite(STEPPER_IN1, stepperPattern[patternIndex][0]);
  digitalWrite(STEPPER_IN2, stepperPattern[patternIndex][1]);
  digitalWrite(STEPPER_IN3, stepperPattern[patternIndex][2]);
  digitalWrite(STEPPER_IN4, stepperPattern[patternIndex][3]);
}

void releaseStepper() {
  digitalWrite(STEPPER_IN1, LOW);
  digitalWrite(STEPPER_IN2, LOW);
  digitalWrite(STEPPER_IN3, LOW);
  digitalWrite(STEPPER_IN4, LOW);
}

int normalizeStepperDirection(int direction) {
  if (direction > 0) return 1;
  if (direction < 0) return -1;
  return 0;
}

bool stepperCanMove(int direction) {
  if (direction > 0) return stepperPositionHalfSteps < STEPPER_RIGHT_LIMIT_HALFSTEPS;
  if (direction < 0) return stepperPositionHalfSteps > STEPPER_LEFT_LIMIT_HALFSTEPS;
  return false;
}

void setBleStepperDirection(int direction, unsigned long now) {
  // Cualquier orden manual reemplaza un centrado que estuviera en curso.
  stepperCentering = false;
  direction = normalizeStepperDirection(direction);
  if (direction != 0 && !stepperCanMove(direction)) direction = 0;

  stepperBleDirection = direction;
  stepperBleUntil = direction == 0 ? 0 : now + STEPPER_COMMAND_TIMEOUT_MS;
}

void startStepperCentering() {
  stepperBleDirection = 0;
  stepperBleUntil = 0;
  stepperCentering = stepperPositionHalfSteps != 0;
  if (!stepperCentering) stepperRequestedDirection = 0;
}

void stopStepper() {
  stepperCentering = false;
  stepperBleDirection = 0;
  stepperBleUntil = 0;
  stepperRequestedDirection = 0;
  releaseStepper();
}

void updateStepperCommand(unsigned long now) {
  int direction = 0;
  if (!bleConnected) stepperCentering = false;

  if (bleConnected && stepperCentering) {
    if (stepperPositionHalfSteps < 0) direction = 1;
    else if (stepperPositionHalfSteps > 0) direction = -1;
    else stepperCentering = false;
  } else {
    const bool commandAlive =
      bleConnected && stepperBleDirection != 0 && (long)(stepperBleUntil - now) > 0;
    direction = commandAlive ? stepperBleDirection : 0;
  }

  if (direction != 0 && !stepperCanMove(direction)) {
    direction = 0;
    stepperCentering = false;
    stepperBleDirection = 0;
    stepperBleUntil = 0;
  }
  stepperRequestedDirection = direction;
}

void updateStepperMotor(unsigned long now) {
  updateStepperCommand(now);
  const int direction = stepperRequestedDirection;

  if (direction == 0) {
    releaseStepper();
    return;
  }

  const unsigned long nowUs = micros();
  if (nowUs - tStepperUs < STEPPER_STEP_US) return;
  tStepperUs = nowUs;

  setStepperPattern(stepperPatternIndex);
  if (direction > 0) {
    stepperPatternIndex = (stepperPatternIndex + 1) % 8;
    stepperPositionHalfSteps++;
  } else {
    stepperPatternIndex--;
    if (stepperPatternIndex < 0) stepperPatternIndex = 7;
    stepperPositionHalfSteps--;
  }
}

// =====================================================
//              CAPTURA DE ÚLTIMA FUERZA
// =====================================================
const float CAPTURE_MIN_G = 1.0f;
const float STABLE_DELTA_G = 0.15f;
const unsigned long STABLE_TIME_MS = 250;
const unsigned long PEAK_WINDOW_MS = 500;

float lastNowForStable = 0.0f;
unsigned long stableSince = 0;
float peakTracker = 0.0f;
unsigned long peakHoldStart = 0;

void updateLastCapture(unsigned long now) {
  const float grams = peso_g_now;

  if (grams < CAPTURE_MIN_G) {
    peakTracker = 0.0f;
    stableSince = now;
    lastNowForStable = grams;
    return;
  }

  if (fabs(grams - lastNowForStable) <= STABLE_DELTA_G) {
    if (stableSince == 0) stableSince = now;
    if (now - stableSince >= STABLE_TIME_MS) {
      peso_g_last = grams;
      stableSince = 0;
    }
  } else {
    stableSince = now;
    lastNowForStable = grams;
  }

  if (grams > peakTracker) {
    peakTracker = grams;
    peakHoldStart = now;
  } else if (now - peakHoldStart <= PEAK_WINDOW_MS) {
    if (peakTracker >= CAPTURE_MIN_G) peso_g_last = peakTracker;
  } else {
    peakTracker = grams;
    peakHoldStart = now;
  }
}

// =====================================================
//                       AUTOZERO
// =====================================================
const bool AUTOZERO_ENABLED = true;
const float AUTOZERO_MAX_G = 3.0f;
const float AUTOZERO_STABLE_DELTA_G = 0.15f;
const unsigned long AUTOZERO_STABLE_MS = 4000;
const unsigned long AUTOZERO_EVERY_MS = 15000;

unsigned long lastAutoZero = 0;
unsigned long autoZeroStableStart = 0;
float autoZeroPrev = 0.0f;
bool autoZeroHavePrev = false;

bool autoZeroAdjustOffset(uint8_t samples = 18) {
  long values[32];
  if (samples > 32) samples = 32;

  for (uint8_t i = 0; i < samples; i++) {
    const unsigned long waitStarted = millis();
    while (!scale.is_ready()) {
      delay(0);
      if (millis() - waitStarted > 800) return false;
    }
    values[i] = scale.read();
    delay(0);
  }

  for (uint8_t i = 0; i < samples - 1; i++) {
    for (uint8_t j = i + 1; j < samples; j++) {
      if (values[j] < values[i]) {
        const long temp = values[i];
        values[i] = values[j];
        values[j] = temp;
      }
    }
  }

  int trim = (int)(samples * 0.2f);
  if (trim < 1) trim = 1;
  int start = trim;
  int end = samples - trim;
  if (end <= start) {
    start = 0;
    end = samples;
  }

  long long sum = 0;
  int count = 0;
  for (int i = start; i < end; i++) {
    sum += values[i];
    count++;
  }

  scale.set_offset(count > 0 ? (long)(sum / count) : values[samples / 2]);
  return true;
}

void updateAutoZero(unsigned long now) {
  if (!AUTOZERO_ENABLED || now - lastAutoZero < AUTOZERO_EVERY_MS) return;
  // El ajuste toma varias muestras seguidas; no debe interrumpir el movimiento.
  if (stepperRequestedDirection != 0 || stepperBleDirection != 0) {
    autoZeroHavePrev = false;
    autoZeroStableStart = now;
    return;
  }

  const float grams = peso_g_now;
  if (fabs(grams) > AUTOZERO_MAX_G) {
    autoZeroHavePrev = false;
    autoZeroStableStart = now;
    return;
  }

  if (!autoZeroHavePrev) {
    autoZeroPrev = grams;
    autoZeroHavePrev = true;
    autoZeroStableStart = now;
    return;
  }

  if (fabs(grams - autoZeroPrev) <= AUTOZERO_STABLE_DELTA_G) {
    if (now - autoZeroStableStart >= AUTOZERO_STABLE_MS) {
      if (autoZeroAdjustOffset()) {
        lastAutoZero = now;
        peso_g_now = 0.0f;
      }
      autoZeroHavePrev = false;
    }
  } else {
    autoZeroPrev = grams;
    autoZeroStableStart = now;
  }
}

// =====================================================
//                   LECTURA Y TARA
// =====================================================
bool initHX711(unsigned long timeoutMs) {
  Serial.println("HX711: esperando...");
  const unsigned long started = millis();
  while (!scale.is_ready()) {
    delay(1);
    if (millis() - started > timeoutMs) {
      Serial.println("ERROR HX711: revisa el cableado");
      return false;
    }
  }

  scale.tare(20);
  scale.set_scale(CAL);
  Serial.println("HX711 listo");
  return true;
}

void updatePesoNonBlocking() {
  if (!scale.is_ready()) return;

  // Promedia dos conversiones sin esperar bloqueado por la siguiente. Así el
  // loop puede seguir generando los pasos del motor entre lecturas del HX711.
  static float sampleSum = 0.0f;
  static uint8_t sampleCount = 0;
  sampleSum += scale.get_units(1);
  sampleCount++;
  if (sampleCount >= 2) {
    peso_g_now = prettyZero(sampleSum / sampleCount);
    sampleSum = 0.0f;
    sampleCount = 0;
  }
}

bool tarePrecisa(uint8_t samples = 40, float stableDelta = 0.20f, unsigned long stableMs = 350) {
  Serial.println("Tara solicitada desde la app. No tocar la celda...");

  unsigned long started = millis();
  while (!scale.is_ready()) {
    delay(0);
    if (millis() - started > 1200) return false;
  }

  float previous = 0.0f;
  bool havePrevious = false;
  unsigned long stableStart = 0;
  const unsigned long stabilityTimeout = millis();
  while (true) {
    if (scale.is_ready()) {
      const float grams = scale.get_units(1);
      if (!havePrevious) {
        previous = grams;
        havePrevious = true;
        stableStart = millis();
      } else if (fabs(grams - previous) <= stableDelta) {
        if (millis() - stableStart >= stableMs) break;
      } else {
        stableStart = millis();
        previous = grams;
      }
    }
    delay(0);
    if (millis() - stabilityTimeout > 2500) break;
  }

  long values[60];
  if (samples > 60) samples = 60;
  for (uint8_t i = 0; i < samples; i++) {
    const unsigned long waitStarted = millis();
    while (!scale.is_ready()) {
      delay(0);
      if (millis() - waitStarted > 1000) return false;
    }
    values[i] = scale.read();
    delay(0);
  }

  for (uint8_t i = 0; i < samples - 1; i++) {
    for (uint8_t j = i + 1; j < samples; j++) {
      if (values[j] < values[i]) {
        const long temp = values[i];
        values[i] = values[j];
        values[j] = temp;
      }
    }
  }

  int trim = (int)(samples * 0.2f);
  if (trim < 1) trim = 1;
  int start = trim;
  int end = samples - trim;
  if (end <= start) {
    start = 0;
    end = samples;
  }

  long long sum = 0;
  int count = 0;
  for (int i = start; i < end; i++) {
    sum += values[i];
    count++;
  }
  scale.set_offset(count > 0 ? (long)(sum / count) : values[samples / 2]);
  return true;
}

void performTare() {
  stopStepper();
  const bool success = tarePrecisa();
  if (!success) scale.tare(20);

  peso_g_now = 0.0f;
  peso_g_last = 0.0f;
  peakTracker = 0.0f;
  stableSince = millis();
  lastNowForStable = 0.0f;
  lastAutoZero = millis();
  autoZeroHavePrev = false;
  Serial.println(success ? "Tara OK" : "Tara completada con método de respaldo");
}

// =====================================================
//               ZONAS, HUMEDAD Y ALARMAS
// =====================================================
char zoneFromWeight(float grams) {
  if (grams > 95.0f) return 'R';
  if (grams >= 80.0f) return 'G';
  if (grams >= 40.0f) return 'Y';
  return 'B';
}

bool computeAlarm() {
  return humidityDetected || zoneFromWeight(peso_g_now) == 'R';
}

void updateHumidity() {
  const bool raw = digitalRead(HUMIDITY_PIN);
  humidityDetected = HUMIDITY_ACTIVE_LOW ? !raw : raw;
}

void setColor(const CRGB& color) {
  fill_solid(leds, NUM_LEDS, color);
  FastLED.show();
}

void updateLedIfNeeded() {
  static char lastZone = '?';
  static bool lastWasZero = false;
  const bool isZero = peso_g_now == 0.0f;

  if (isZero) {
    if (!lastWasZero) {
      setColor(CRGB::Black);
      lastWasZero = true;
      lastZone = '?';
    }
    return;
  }

  lastWasZero = false;
  const char zone = zoneFromWeight(peso_g_now);
  if (zone == lastZone) return;
  lastZone = zone;

  switch (zone) {
    case 'Y': setColor(CRGB::Yellow); break;
    case 'G': setColor(CRGB::Green); break;
    case 'R': setColor(CRGB::Red); break;
    default: setColor(CRGB::Blue); break;
  }
}

void updateBuzzer(unsigned long now) {
  alarmOn = computeAlarm();
  static unsigned long lastBeep = 0;
  static bool beepState = false;

  if (alarmOn && now - lastBeep > 200) {
    lastBeep = now;
    beepState = !beepState;
    digitalWrite(BUZZER_PIN, beepState ? HIGH : LOW);
  } else if (!alarmOn) {
    beepState = false;
    digitalWrite(BUZZER_PIN, LOW);
  }
}

// =====================================================
//                  PROTOCOLO BLE
// =====================================================
// Estado: S,peso,ultimo,zona,alarma,humedad,direccion,posicion,puedeIzq,puedeDer,centrando
// Comandos aceptados: TARE, MOTOR,-1|0|1 y CENTER
void updateBleStateValue(bool notifyClient) {
  if (stateCharacteristic == nullptr) return;

  char state[128];
  snprintf(
    state,
    sizeof(state),
    "S,%.2f,%.2f,%c,%d,%d,%d,%ld,%d,%d,%d",
    peso_g_now,
    peso_g_last,
    zoneFromWeight(peso_g_now),
    computeAlarm() ? 1 : 0,
    humidityDetected ? 1 : 0,
    stepperRequestedDirection,
    stepperPositionHalfSteps,
    stepperCanMove(-1) ? 1 : 0,
    stepperCanMove(1) ? 1 : 0,
    stepperCentering ? 1 : 0
  );

  stateCharacteristic->setValue(state);
  if (notifyClient && bleConnected) stateCharacteristic->notify();
}

class ScaleServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* server) override {
    bleConnected = true;
    Serial.println("App conectada por Bluetooth");
  }

  void onDisconnect(BLEServer* server) override {
    bleConnected = false;
    stopStepper();
    Serial.println("App desconectada; motor detenido");
    BLEDevice::startAdvertising();
  }
};

class CommandCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* characteristic) override {
    String command = characteristic->getValue().c_str();
    command.trim();
    command.toUpperCase();

    if (command == "TARE") {
      tareRequested = true;
      return;
    }

    if (command == "CENTER") {
      startStepperCentering();
      return;
    }

    if (command.startsWith("MOTOR,")) {
      const int direction = normalizeStepperDirection(command.substring(6).toInt());
      setBleStepperDirection(direction, millis());
    }
  }
};

void setupBluetooth() {
  BLEDevice::init(BLE_DEVICE_NAME);
  BLEServer* server = BLEDevice::createServer();
  server->setCallbacks(new ScaleServerCallbacks());

  BLEService* service = server->createService(BLE_SERVICE_UUID);
  stateCharacteristic = service->createCharacteristic(
    BLE_STATE_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  stateCharacteristic->addDescriptor(new BLE2902());

  BLECharacteristic* commandCharacteristic = service->createCharacteristic(
    BLE_COMMAND_UUID,
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
  );
  commandCharacteristic->setCallbacks(new CommandCallbacks());

  updateBleStateValue(false);
  service->start();

  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(BLE_SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->setMinPreferred(0x12);
  BLEDevice::startAdvertising();

  Serial.print("Bluetooth listo: ");
  Serial.println(BLE_DEVICE_NAME);
}

// =====================================================
//                         SETUP
// =====================================================
void setup() {
  Serial.begin(115200);

  pinMode(BUZZER_PIN, OUTPUT);
  digitalWrite(BUZZER_PIN, LOW);
  pinMode(HUMIDITY_PIN, INPUT_PULLUP);

  pinMode(STEPPER_IN1, OUTPUT);
  pinMode(STEPPER_IN2, OUTPUT);
  pinMode(STEPPER_IN3, OUTPUT);
  pinMode(STEPPER_IN4, OUTPUT);
  releaseStepper();

  FastLED.addLeds<WS2812B, LED_PIN, GRB>(leds, NUM_LEDS);
  FastLED.setBrightness(100);
  setColor(CRGB::Black);

  scale.begin(HX_DOUT, HX_SCK);
  if (!initHX711(4000)) {
    while (true) {
      digitalWrite(BUZZER_PIN, LOW);
      delay(10);
    }
  }

  updatePesoNonBlocking();
  peso_g_last = peso_g_now;
  updateHumidity();
  updateLedIfNeeded();
  setupBluetooth();

  const unsigned long now = millis();
  lastAutoZero = now;
  tPeso = tSerial = tBle = tLed = tHumidity = now;
}

// =====================================================
//                          LOOP
// =====================================================
void loop() {
  const unsigned long now = millis();

  updateStepperMotor(now);

  if (now - tHumidity >= PERIOD_HUMIDITY_MS) {
    tHumidity = now;
    updateHumidity();
  }

  if (tareRequested) {
    tareRequested = false;
    performTare();
  }

  if (now - tPeso >= PERIOD_PESO_MS) {
    tPeso = now;
    updatePesoNonBlocking();
    updateLastCapture(now);
    updateAutoZero(now);
  }

  if (now - tLed >= PERIOD_LED_MS) {
    tLed = now;
    updateLedIfNeeded();
  }

  updateBuzzer(now);

  if (now - tBle >= PERIOD_BLE_MS) {
    tBle = now;
    updateBleStateValue(true);
  }

  if (now - tSerial >= PERIOD_SERIAL_MS) {
    tSerial = now;
    Serial.printf(
      "Peso: %.2f g | Último: %.2f g | Zona: %c | Humedad: %s | Motor: %d (%ld)\n",
      peso_g_now,
      peso_g_last,
      zoneFromWeight(peso_g_now),
      humidityDetected ? "SI" : "NO",
      stepperRequestedDirection,
      stepperPositionHalfSteps
    );
  }

  delay(0);
}
