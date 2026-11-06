// Sistema antirrobo - Arduino UNO R4 WiFi + Firebase (Realtime Database por HTTPS)
// Sensor de vibración en pin 2, LED en pin 8 (con resistencia de 220 ohm a GND).

#include <WiFiS3.h>

// ---- Datos privados (NO subir este archivo a GitHub con estos datos) ----
#define WIFI_SSID "LAPTOP12"
#define WIFI_CLAVE "boris123"
#define API_KEY "AIzaSyBYC08XRFOr4jhJe-jCqcO_ahod8Hn1ppo"
#define DISP_CORREO "dispositivo@antirrobo.cl"
#define DISP_CLAVE "123456"

const char* DB_HOST = "sistemaantirrobo-default-rtdb.firebaseio.com";
const char* AUTH_HOST = "identitytoolkit.googleapis.com";

const int SENSOR_VIBRACION = 2;  // sensor de vibración SW-420
const int LED_ALARMA = 8;        // LED indicador de alarma
const int NIVEL_VIBRACION = HIGH;  // si tu sensor detecta con LOW, cámbialo a LOW

const unsigned long TIEMPO_LED_MS = 3000;
const unsigned long CONSULTA_ARMADO_MS = 5000;
const unsigned long ENTRE_ALERTAS_MS = 5000;
const unsigned long RENOVAR_TOKEN_MS = 3000000UL;  // ~50 min

WiFiSSLClient client;
String idToken = "";
unsigned long tokenMs = 0;
bool armado = true;
unsigned long ultimaConsulta = 0;
unsigned long ultimaAlerta = 0;
unsigned long ledHasta = 0;

// Petición HTTPS. Devuelve el código HTTP (o -1) y deja la respuesta en "cuerpo".
int peticion(const char* host, const String& metodo, const String& ruta,
             const String& enviar, String& cuerpo) {
  cuerpo = "";
  if (!client.connect(host, 443)) return -1;

  client.print(metodo + " " + ruta + " HTTP/1.0\r\n");
  client.print(String("Host: ") + host + "\r\n");
  client.print("Content-Type: application/json\r\n");
  client.print("Content-Length: " + String(enviar.length()) + "\r\n");
  client.print("Connection: close\r\n\r\n");
  client.print(enviar);

  String resp = "";
  unsigned long inicio = millis();
  while ((client.connected() || client.available()) && millis() - inicio < 8000) {
    while (client.available()) resp += (char)client.read();
  }
  client.stop();

  int codigo = -1;
  int sp = resp.indexOf(' ');
  if (sp > 0) codigo = resp.substring(sp + 1, sp + 4).toInt();
  int fin = resp.indexOf("\r\n\r\n");
  if (fin >= 0) cuerpo = resp.substring(fin + 4);
  return codigo;
}

void conectarWiFi() {
  if (WiFi.status() == WL_NO_MODULE) {
    Serial.println("No se encontró el módulo WiFi");
    while (true) {}
  }
  while (WiFi.status() != WL_CONNECTED) {
    Serial.print("Conectando a WiFi... ");
    WiFi.begin(WIFI_SSID, WIFI_CLAVE);
    delay(5000);
  }
  Serial.println("WiFi conectado");
}

// Inicia sesión con la cuenta del dispositivo y guarda el token
bool iniciarSesion() {
  String cuerpo;
  String datos = String("{\"email\":\"") + DISP_CORREO +
                 "\",\"password\":\"" + DISP_CLAVE +
                 "\",\"returnSecureToken\":true}";
  int codigo = peticion(AUTH_HOST, "POST",
                        String("/v1/accounts:signInWithPassword?key=") + API_KEY,
                        datos, cuerpo);
  if (codigo != 200) {
    Serial.print("Error al iniciar sesión, código: ");
    Serial.println(codigo);
    return false;
  }
  int i = cuerpo.indexOf("\"idToken\"");
  if (i < 0) return false;
  int a = cuerpo.indexOf('"', i + 9);
  int b = cuerpo.indexOf('"', a + 1);
  if (a < 0 || b < 0) return false;
  idToken = cuerpo.substring(a + 1, b);
  tokenMs = millis();
  Serial.println("Sesión iniciada en Firebase");
  return true;
}

bool asegurarSesion() {
  if (idToken == "" || millis() - tokenMs > RENOVAR_TOKEN_MS) return iniciarSesion();
  return true;
}

void consultarArmado() {
  if (!asegurarSesion()) return;
  String r;
  int codigo = peticion(DB_HOST, "GET", "/sistema/armado.json?auth=" + idToken, "", r);
  if (codigo == 200) {
    r.trim();
    armado = (r != "false");  // si no existe todavía, queda armado
  } else if (codigo == 401 || codigo == 403) {
    idToken = "";  // forzar nuevo inicio de sesión
  }
}

bool enviarAlerta() {
  if (!asegurarSesion()) return false;
  String r;
  int c1 = peticion(DB_HOST, "PUT", "/sistema/hora.json?auth=" + idToken,
                    "{\".sv\":\"timestamp\"}", r);
  int c2 = peticion(DB_HOST, "PUT", "/sistema/alerta.json?auth=" + idToken, "true", r);
  Serial.print("Alerta enviada, códigos: ");
  Serial.print(c1);
  Serial.print(" / ");
  Serial.println(c2);
  if (c1 == 401 || c1 == 403 || c2 == 401 || c2 == 403) idToken = "";
  return c1 == 200 && c2 == 200;
}

void setup() {
  Serial.begin(9600);
  pinMode(SENSOR_VIBRACION, INPUT);
  pinMode(LED_ALARMA, OUTPUT);
  digitalWrite(LED_ALARMA, LOW);
  conectarWiFi();
  iniciarSesion();
}

void loop() {
  if (WiFi.status() != WL_CONNECTED) conectarWiFi();

  unsigned long ahora = millis();

  if (ahora - ultimaConsulta > CONSULTA_ARMADO_MS) {
    ultimaConsulta = ahora;
    consultarArmado();
  }

  if (armado && digitalRead(SENSOR_VIBRACION) == NIVEL_VIBRACION &&
      ahora - ultimaAlerta > ENTRE_ALERTAS_MS) {
    ultimaAlerta = ahora;
    Serial.println("Vibración detectada");
    digitalWrite(LED_ALARMA, HIGH);
    enviarAlerta();                       // tarda un par de segundos
    ledHasta = millis() + TIEMPO_LED_MS;  // luego el LED parpadea 3 s
  }

  // Alarma visual: LED intermitente (200 ms) durante 3 segundos
  if (ledHasta != 0) {
    if (millis() > ledHasta) {
      digitalWrite(LED_ALARMA, LOW);
      ledHasta = 0;
    } else {
      digitalWrite(LED_ALARMA, (millis() / 200) % 2 == 0 ? HIGH : LOW);
    }
  }
}