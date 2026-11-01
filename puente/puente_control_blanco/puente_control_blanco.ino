/*
  AM Medinas 360 · Puente del control blanco (radiofrecuencia 433 MHz) para la base Audiotek 49216
  ---------------------------------------------------------------------------------------------
  El control blanco de la base manda señales de radio (433 MHz) que un celular no puede emitir.
  Este puente (ESP32 + receptor + transmisor de 433 MHz) APRENDE cada botón del control blanco y
  lo REPITE cuando la app de la cabina se lo pide por Bluetooth.

  Botones (mismo orden que el control):
    1 ON/OFF   2 LED   3 Reverse   4 Auto   5 UP (más velocidad)   6 RGB Exchange   7 Speed Down   8 RGB Brightness

  Comandos que manda la app (texto por Bluetooth, servicio FFE0 / característica FFE1):
    "S1".."S8"  repetir el botón 1..8
    "L1".."L8"  aprender el botón 1..8 (apunta el control blanco al puente y presiona ese botón)
    "P"         prueba de conexión
  Respuestas (notificación en FFE1): "OK S1", "OK L1 <código>", "SIN L1", "TIEMPO L1", "VACIO S1", "PONG".

  Placa: ESP32 (cualquier DevKit). Núcleo "esp32" de Espressif para Arduino IDE (2.x o 3.x).
  Librería: "rc-switch" de sui77 (Gestor de librerías). El Bluetooth viene incluido en el núcleo ESP32.

  Conexiones:
    Receptor 433 MHz (RXB6 o similar): VCC -> 5V (VIN) · GND -> GND · DATA -> GPIO 27
    Transmisor 433 MHz (FS1000A o similar): VCC -> 5V (VIN) · GND -> GND · DATA -> GPIO 26
    Antena: un cable de 17.3 cm soldado en el pin ANT del transmisor y otro en el receptor.
*/
#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Preferences.h>
#include <RCSwitch.h>

#define PIN_RX 27
#define PIN_TX 26
#define LED_PLACA 2
#define NOMBRE "AMM360 Puente"
#define SERVICIO "0000ffe0-0000-1000-8000-00805f9b34fb"
#define CARACT   "0000ffe1-0000-1000-8000-00805f9b34fb"

RCSwitch radio;
Preferences memoria;
BLECharacteristic *car = nullptr;
volatile bool conectado = false;
String pendiente = "";            // comando recibido, se atiende en loop()

void avisar(const String &t) {
  Serial.println(t);
  if (car && conectado) { car->setValue(t.c_str()); car->notify(); }
}

class Servidor : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override { conectado = true; digitalWrite(LED_PLACA, HIGH); }
  void onDisconnect(BLEServer *s) override {
    conectado = false; digitalWrite(LED_PLACA, LOW);
    BLEDevice::startAdvertising();                       // vuelve a estar visible para la app
  }
};

class Escritura : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String v = String(c->getValue().c_str());
    v.trim();
    if (v.length()) pendiente = v;
  }
};

String clave(int n, const char *campo) { return "b" + String(n) + campo; }

bool repetir(int n) {
  memoria.begin("amm360", true);
  unsigned long codigo = memoria.getULong(clave(n, "c").c_str(), 0);
  int bits = memoria.getInt(clave(n, "b").c_str(), 24);
  int proto = memoria.getInt(clave(n, "p").c_str(), 1);
  int pulso = memoria.getInt(clave(n, "d").c_str(), 0);
  memoria.end();
  if (!codigo) return false;
  radio.disableReceive();
  radio.setProtocol(proto);
  if (pulso > 0) radio.setPulseLength(pulso);
  radio.setRepeatTransmit(12);
  radio.send(codigo, bits);
  radio.enableReceive(digitalPinToInterrupt(PIN_RX));
  return true;
}

void aprender(int n) {
  avisar("ESPERANDO L" + String(n));
  radio.resetAvailable();
  unsigned long fin = millis() + 10000;
  while (millis() < fin) {
    if (radio.available()) {
      unsigned long codigo = radio.getReceivedValue();
      if (codigo == 0) { radio.resetAvailable(); continue; }
      memoria.begin("amm360", false);
      memoria.putULong(clave(n, "c").c_str(), codigo);
      memoria.putInt(clave(n, "b").c_str(), radio.getReceivedBitlength());
      memoria.putInt(clave(n, "p").c_str(), radio.getReceivedProtocol());
      memoria.putInt(clave(n, "d").c_str(), radio.getReceivedDelay());
      memoria.end();
      radio.resetAvailable();
      for (int i = 0; i < 3; i++) { digitalWrite(LED_PLACA, LOW); delay(80); digitalWrite(LED_PLACA, HIGH); delay(80); }
      avisar("OK L" + String(n) + " " + String(codigo));
      return;
    }
    delay(5);
  }
  avisar("TIEMPO L" + String(n));
}

void setup() {
  Serial.begin(115200);
  pinMode(LED_PLACA, OUTPUT);
  radio.enableReceive(digitalPinToInterrupt(PIN_RX));
  radio.enableTransmit(PIN_TX);

  BLEDevice::init(NOMBRE);
  BLEServer *srv = BLEDevice::createServer();
  srv->setCallbacks(new Servidor());
  BLEService *svc = srv->createService(SERVICIO);
  car = svc->createCharacteristic(CARACT,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE |
        BLECharacteristic::PROPERTY_WRITE_NR | BLECharacteristic::PROPERTY_NOTIFY);
  car->addDescriptor(new BLE2902());
  car->setCallbacks(new Escritura());
  car->setValue("LISTO");
  svc->start();
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICIO);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("AMM360 Puente listo");
}

void loop() {
  // Escucha el control blanco en todo momento (útil para probar desde el Monitor Serie)
  if (pendiente.length() == 0 && radio.available()) {
    Serial.printf("Control: codigo %lu bits %d protocolo %d pulso %d\n", radio.getReceivedValue(),
                  radio.getReceivedBitlength(), radio.getReceivedProtocol(), radio.getReceivedDelay());
    radio.resetAvailable();
  }
  if (pendiente.length() == 0) { delay(5); return; }
  String cmd = pendiente; pendiente = "";
  char tipo = toupper(cmd.charAt(0));
  int n = cmd.substring(1).toInt();
  if (tipo == 'P') { avisar("PONG"); return; }
  if (n < 1 || n > 8) { avisar("ERROR " + cmd); return; }
  if (tipo == 'S') avisar((repetir(n) ? "OK S" : "VACIO S") + String(n));
  else if (tipo == 'L') aprender(n);
  else avisar("ERROR " + cmd);
}
