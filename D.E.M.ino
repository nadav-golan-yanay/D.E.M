/*
 * ESP32 MAVLink Telemetry Bridge via nRF24L01+
 *
 * Ground Node: USB Serial <-> nRF24 <-> Air Node <-> FC UART
 *
 * Arduino IDE usage:
 * 1. Open this sketch from the D.E.M folder.
 * 2. Install the ESP32 board package and the RF24 library.
 * 3. Set NODE_ROLE to NODE_ROLE_GROUND or NODE_ROLE_AIR before uploading.
 *
 * Packet Format (32 bytes fixed):
 *   [0]     Magic byte (0xA5)
 *   [1]     Sequence number
 *   [2]     Payload length (0-27)
 *   [3]     Reserved
 *   [4-30]  Payload (up to 27 bytes of MAVLink data)
 *   [31]    Padding
 */

#include <Arduino.h>
#include <SPI.h>
#include <RF24.h>

#if !defined(ARDUINO_ARCH_ESP32)
  #error "This sketch requires an ESP32 board package. In Arduino IDE, select Tools > Board > ESP32 Dev Module."
#endif

#define DEM_VERSION "0.1.1"

// ============================================================================
// SKETCH CONFIGURATION
// ============================================================================
// Change this before uploading each device from Arduino IDE.
#define NODE_ROLE_GROUND 1
#define NODE_ROLE_AIR    2
#define NODE_ROLE        NODE_ROLE_GROUND

#if NODE_ROLE == NODE_ROLE_GROUND
  #define GROUND_NODE
#elif NODE_ROLE == NODE_ROLE_AIR
  #define AIR_NODE
#else
  #error "Set NODE_ROLE to NODE_ROLE_GROUND or NODE_ROLE_AIR"
#endif

// Debug enable: set to 1 for debug output, 0 for production
#define DEBUG_ENABLED 1

#if DEBUG_ENABLED
  #define DEBUG_PRINT(fmt, ...) do { \
    char buf[256]; \
    snprintf(buf, sizeof(buf), fmt, ##__VA_ARGS__); \
    Serial.print(buf); \
  } while(0)
#else
  #define DEBUG_PRINT(fmt, ...) do {} while(0)
#endif

// ============================================================================
// HARDWARE CONFIGURATION
// ============================================================================
#define RF24_CE_PIN      4
#define RF24_CSN_PIN     5
#define RF24_SCK_PIN     18
#define RF24_MOSI_PIN    23
#define RF24_MISO_PIN    19

#define FC_RX_PIN        16
#define FC_TX_PIN        17

#define RF24_DATA_RATE   RF24_250KBPS
#define RF24_PA_LEVEL    RF24_PA_MAX
#define RF24_CHANNEL     76
#define RF24_CRC_LENGTH  RF24_CRC_16

#define PACKET_SIZE      32
#define MAGIC_BYTE       0xA5
#define MAX_PAYLOAD_SIZE 27
#define PAYLOAD_OFFSET   4
#define HEARTBEAT_INTERVAL_MS 1000

const uint8_t air_addr[5] = {'A', 'I', 'R', '0', '1'};
const uint8_t gnd_addr[5] = {'G', 'N', 'D', '0', '1'};

RF24 radio(RF24_CE_PIN, RF24_CSN_PIN);
SPIClass* spi = nullptr;

struct Stats {
  uint32_t tx_packets;
  uint32_t rx_packets;
  uint32_t tx_failed;
  uint32_t bad_packets;
  uint32_t duplicate_packets;
};

Stats stats = {0, 0, 0, 0, 0};
uint8_t last_rx_seq = 0xFF;

struct {
  uint8_t data[PACKET_SIZE];
} tx_packet, rx_packet;

void setup_spi() {
  spi = new SPIClass(HSPI);
  spi->begin(RF24_SCK_PIN, RF24_MISO_PIN, RF24_MOSI_PIN, RF24_CSN_PIN);
}

void setup_rf24() {
  if (!radio.begin(spi)) {
    DEBUG_PRINT("RF24 initialization FAILED\n");
    while (1) {
    }
  }

  radio.setDataRate(RF24_DATA_RATE);
  radio.setPALevel(RF24_PA_LEVEL);
  radio.setChannel(RF24_CHANNEL);
  radio.setCRCLength(RF24_CRC_LENGTH);
  radio.enableAckPayload();
  radio.enableDynamicPayloads();
  radio.setRetries(15, 15);
  radio.setPayloadSize(PACKET_SIZE);

  DEBUG_PRINT("RF24 initialized\n");
}

void setup_serial() {
#ifdef GROUND_NODE
  Serial.begin(115200);
  delay(100);
  DEBUG_PRINT("Ground node serial initialized (USB 115200)\n");
#endif

#ifdef AIR_NODE
  Serial2.begin(115200, SERIAL_8N1, FC_RX_PIN, FC_TX_PIN);
  delay(100);
  DEBUG_PRINT("Air node serial2 initialized (115200, GPIO16/17)\n");
#endif
}

void build_packet(uint8_t seq, const uint8_t* payload, uint8_t len) {
  if (len > MAX_PAYLOAD_SIZE) {
    len = MAX_PAYLOAD_SIZE;
  }

  tx_packet.data[0] = MAGIC_BYTE;
  tx_packet.data[1] = seq;
  tx_packet.data[2] = len;
  tx_packet.data[3] = 0;

  if (len > 0) {
    memcpy(&tx_packet.data[PAYLOAD_OFFSET], payload, len);
  }

  if (len < MAX_PAYLOAD_SIZE) {
    memset(&tx_packet.data[PAYLOAD_OFFSET + len], 0,
           PACKET_SIZE - PAYLOAD_OFFSET - len);
  }
}

bool validate_and_extract_packet(uint8_t* out_payload, uint8_t* out_len) {
  if (rx_packet.data[0] != MAGIC_BYTE) {
    stats.bad_packets++;
    return false;
  }

  const uint8_t seq = rx_packet.data[1];
  const uint8_t len = rx_packet.data[2];

  if (len > MAX_PAYLOAD_SIZE) {
    stats.bad_packets++;
    return false;
  }

  if (seq == last_rx_seq) {
    stats.duplicate_packets++;
    return false;
  }

  last_rx_seq = seq;

  if (len > 0) {
    memcpy(out_payload, &rx_packet.data[PAYLOAD_OFFSET], len);
  }
  *out_len = len;

  return true;
}

HardwareSerial& get_local_serial() {
#ifdef GROUND_NODE
  return Serial;
#else
  return Serial2;
#endif
}

uint32_t serial_available() {
  return get_local_serial().available();
}

int serial_read() {
  return get_local_serial().read();
}

void serial_write(const uint8_t* data, uint16_t len) {
  get_local_serial().write(data, len);
}

bool try_build_heartbeat(uint8_t* payload, uint8_t* payload_len) {
  static uint32_t last_heartbeat_ms = 0;
  const uint32_t now = millis();

  if ((now - last_heartbeat_ms) < HEARTBEAT_INTERVAL_MS) {
    return false;
  }

  last_heartbeat_ms = now;
  payload[0] = 'H';
  payload[1] = 'B';
#ifdef GROUND_NODE
  payload[2] = 'G';
#else
  payload[2] = 'A';
#endif
  *payload_len = 3;
  return true;
}

void send_rf24_packet() {
  uint8_t payload[MAX_PAYLOAD_SIZE];
  uint8_t payload_len = 0;

  while (serial_available() && payload_len < MAX_PAYLOAD_SIZE) {
    payload[payload_len++] = serial_read();
  }

  if (payload_len == 0 && !try_build_heartbeat(payload, &payload_len)) {
    return;
  }

  static uint8_t tx_seq = 0;
  build_packet(tx_seq, payload, payload_len);
  tx_seq++;

  radio.stopListening();

#ifdef GROUND_NODE
  if (!radio.write(&tx_packet.data, PACKET_SIZE)) {
    stats.tx_failed++;
    DEBUG_PRINT("TX FAILED (Ground->Air)\n");
  } else {
    stats.tx_packets++;
    DEBUG_PRINT("TX OK: %u bytes (Ground->Air, seq=%u)\n", payload_len, tx_seq - 1);
  }
#endif

#ifdef AIR_NODE
  if (!radio.write(&tx_packet.data, PACKET_SIZE)) {
    stats.tx_failed++;
    DEBUG_PRINT("TX FAILED (Air->Ground)\n");
  } else {
    stats.tx_packets++;
    DEBUG_PRINT("TX OK: %u bytes (Air->Ground, seq=%u)\n", payload_len, tx_seq - 1);
  }
#endif

  radio.startListening();
}

void receive_rf24_packet() {
  radio.startListening();

  if (!radio.available()) {
    return;
  }

  radio.read(&rx_packet.data, PACKET_SIZE);

  uint8_t payload[MAX_PAYLOAD_SIZE];
  uint8_t payload_len = 0;

  if (validate_and_extract_packet(payload, &payload_len)) {
    if (payload_len > 0) {
      serial_write(payload, payload_len);
    }
    stats.rx_packets++;

#ifdef GROUND_NODE
    DEBUG_PRINT("RX OK: %u bytes (Air->Ground)\n", payload_len);
#endif
#ifdef AIR_NODE
    DEBUG_PRINT("RX OK: %u bytes (Ground->Air)\n", payload_len);
#endif
  }
}

void setup() {
  if (DEBUG_ENABLED) {
    Serial.begin(115200);
    delay(100);
  }
  Serial.println("Start");

  DEBUG_PRINT("\n\n=== ESP32 MAVLink Bridge ===\n");
  DEBUG_PRINT("Version: %s\n", DEM_VERSION);
#ifdef GROUND_NODE
  DEBUG_PRINT("MODE: GROUND NODE\n");
#endif
#ifdef AIR_NODE
  DEBUG_PRINT("MODE: AIR NODE\n");
#endif

  setup_spi();
  setup_rf24();
  setup_serial();

#ifdef GROUND_NODE
  radio.openReadingPipe(1, gnd_addr);
  radio.stopListening();
  DEBUG_PRINT("Ground: RX on GND01, TX to AIR01\n");
#endif

#ifdef AIR_NODE
  radio.openReadingPipe(1, air_addr);
  radio.stopListening();
  DEBUG_PRINT("Air: RX on AIR01, TX to GND01\n");
#endif

  DEBUG_PRINT("Bridge initialized. Ready.\n\n");
}

void loop() {
  send_rf24_packet();
  receive_rf24_packet();

  static uint32_t last_stats = 0;
  const uint32_t now = millis();
  if (DEBUG_ENABLED && (now - last_stats) > 10000) {
    last_stats = now;
    DEBUG_PRINT("\n=== STATS ===\n");
    DEBUG_PRINT("TX packets: %lu\n", stats.tx_packets);
    DEBUG_PRINT("RX packets: %lu\n", stats.rx_packets);
    DEBUG_PRINT("TX failed: %lu\n", stats.tx_failed);
    DEBUG_PRINT("Bad packets: %lu\n", stats.bad_packets);
    DEBUG_PRINT("Duplicate packets: %lu\n", stats.duplicate_packets);
    DEBUG_PRINT("==============\n\n");
  }

  delayMicroseconds(100);
}