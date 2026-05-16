/*
 * ESP32 ESP-NOW Telemetry Bridge
 *
 * Ground node: USB Serial <-> ESP-NOW <-> Air node <-> FC UART (Serial2)
 *
 * Console commands (ground USB serial only, line-based):
 *   ::help
 *   ::stats
 *   ::reset
 */

#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>

#if !defined(ARDUINO_ARCH_ESP32)
  #error "This sketch requires an ESP32 board package. In Arduino IDE, select Tools > Board > ESP32 Dev Module."
#endif

#define DEM_VERSION "0.3.0"

// ============================================================================
// SKETCH CONFIGURATION
// ============================================================================
#define NODE_ROLE_GROUND 1
#define NODE_ROLE_AIR    2

#ifndef NODE_ROLE
  #define NODE_ROLE NODE_ROLE_GROUND
#endif

#if NODE_ROLE == NODE_ROLE_GROUND
  #define GROUND_NODE
#elif NODE_ROLE == NODE_ROLE_AIR
  #define AIR_NODE
#else
  #error "Set NODE_ROLE to NODE_ROLE_GROUND or NODE_ROLE_AIR"
#endif

#define DEBUG_ENABLED 1
#define ENABLE_CONSOLE_COMMANDS 1
#define ESPNOW_CHANNEL 1
#define HEARTBEAT_INTERVAL_MS 1000
#define STATS_INTERVAL_MS 10000
#define FC_BAUDRATE 115200
#define FC_RX_PIN 16
#define FC_TX_PIN 17
#define DEM_PROTO_VERSION 1
#define MAX_PAYLOAD_SIZE 180
#define MAX_TX_PACKETS_PER_LOOP 4

#if DEBUG_ENABLED
  #define DEBUG_PRINT(fmt, ...) do { \
    char buf[256]; \
    snprintf(buf, sizeof(buf), fmt, ##__VA_ARGS__); \
    Serial.print(buf); \
  } while(0)
#else
  #define DEBUG_PRINT(fmt, ...) do {} while(0)
#endif

enum PacketType : uint8_t {
  PKT_HEARTBEAT = 1,
  PKT_SERIAL_DATA = 2,
};

struct __attribute__((packed)) DemPacketHeader {
  uint8_t proto_ver;
  uint8_t type;
  uint8_t src_role;
  uint8_t payload_len;
  uint32_t seq;
  uint32_t uptime_ms;
};

struct SeqTracker {
  bool initialized;
  uint32_t last_seq;
};

struct LinkStats {
  uint32_t tx_enqueued;
  uint32_t tx_send_ok;
  uint32_t tx_send_failed;
  uint32_t tx_enqueue_failed;
  uint32_t rx_packets;
  uint32_t rx_bytes;
  uint32_t rx_invalid;
  uint32_t rx_duplicates;
  uint32_t rx_out_of_order;
  uint32_t rx_missing;
};

LinkStats stats = {0};
uint8_t broadcast_addr[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
uint32_t tx_seq = 0;
SeqTracker ground_seq = {false, 0};
SeqTracker air_seq = {false, 0};

#ifdef GROUND_NODE
String cmd_buffer;
#endif

const char* role_name() {
#ifdef GROUND_NODE
  return "GROUND";
#else
  return "AIR";
#endif
}

uint8_t local_role_id() {
#ifdef GROUND_NODE
  return NODE_ROLE_GROUND;
#else
  return NODE_ROLE_AIR;
#endif
}

HardwareSerial& uplink_serial() {
#ifdef GROUND_NODE
  return Serial;
#else
  return Serial2;
#endif
}

HardwareSerial& downlink_serial() {
#ifdef GROUND_NODE
  return Serial;
#else
  return Serial2;
#endif
}

void print_mac(const uint8_t* mac) {
  DEBUG_PRINT("%02X:%02X:%02X:%02X:%02X:%02X",
              mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
}

void reset_stats() {
  memset(&stats, 0, sizeof(stats));
  ground_seq.initialized = false;
  air_seq.initialized = false;
}

SeqTracker& tracker_for_role(uint8_t role) {
  if (role == NODE_ROLE_GROUND) {
    return ground_seq;
  }
  return air_seq;
}

void update_sequence_metrics(uint8_t src_role, uint32_t seq) {
  SeqTracker& tr = tracker_for_role(src_role);
  if (!tr.initialized) {
    tr.initialized = true;
    tr.last_seq = seq;
    return;
  }

  if (seq == tr.last_seq) {
    stats.rx_duplicates++;
    return;
  }

  const uint32_t delta = seq - tr.last_seq;
  if (delta == 1) {
    tr.last_seq = seq;
    return;
  }

  // Forward jump means one or more packets were missed.
  if (delta < 0x80000000UL) {
    stats.rx_missing += (delta - 1);
    tr.last_seq = seq;
    return;
  }

  // Very large backward jump usually indicates out-of-order delivery.
  stats.rx_out_of_order++;
}

void print_stats(bool force = false) {
  static uint32_t last_stats_ms = 0;
  const uint32_t now = millis();
  if (!force && (now - last_stats_ms) < STATS_INTERVAL_MS) {
    return;
  }
  last_stats_ms = now;

  DEBUG_PRINT("\n=== DEM STATS ===\n");
  DEBUG_PRINT("role: %s\n", role_name());
  DEBUG_PRINT("tx_enqueued: %lu\n", (unsigned long)stats.tx_enqueued);
  DEBUG_PRINT("tx_send_ok: %lu\n", (unsigned long)stats.tx_send_ok);
  DEBUG_PRINT("tx_send_failed: %lu\n", (unsigned long)stats.tx_send_failed);
  DEBUG_PRINT("tx_enqueue_failed: %lu\n", (unsigned long)stats.tx_enqueue_failed);
  DEBUG_PRINT("rx_packets: %lu\n", (unsigned long)stats.rx_packets);
  DEBUG_PRINT("rx_bytes: %lu\n", (unsigned long)stats.rx_bytes);
  DEBUG_PRINT("rx_invalid: %lu\n", (unsigned long)stats.rx_invalid);
  DEBUG_PRINT("rx_duplicates: %lu\n", (unsigned long)stats.rx_duplicates);
  DEBUG_PRINT("rx_out_of_order: %lu\n", (unsigned long)stats.rx_out_of_order);
  DEBUG_PRINT("rx_missing: %lu\n", (unsigned long)stats.rx_missing);
  DEBUG_PRINT("=================\n\n");
}

void on_send_done(const uint8_t* mac_addr, esp_now_send_status_t status) {
  if (status == ESP_NOW_SEND_SUCCESS) {
    stats.tx_send_ok++;
  } else {
    stats.tx_send_failed++;
  }
  (void)mac_addr;
}

bool handle_received_packet(const uint8_t* data, int len) {
  if (len < (int)sizeof(DemPacketHeader)) {
    stats.rx_invalid++;
    return false;
  }

  DemPacketHeader hdr;
  memcpy(&hdr, data, sizeof(hdr));

  if (hdr.proto_ver != DEM_PROTO_VERSION) {
    stats.rx_invalid++;
    return false;
  }

  if (hdr.payload_len > MAX_PAYLOAD_SIZE) {
    stats.rx_invalid++;
    return false;
  }

  const int expected_len = (int)sizeof(DemPacketHeader) + hdr.payload_len;
  if (len < expected_len) {
    stats.rx_invalid++;
    return false;
  }

  if (hdr.src_role == local_role_id()) {
    return false;
  }

  update_sequence_metrics(hdr.src_role, hdr.seq);
  stats.rx_packets++;
  stats.rx_bytes += hdr.payload_len;

  const uint8_t* payload = data + sizeof(DemPacketHeader);

  if (hdr.type == PKT_SERIAL_DATA && hdr.payload_len > 0) {
    downlink_serial().write(payload, hdr.payload_len);
  }

  if (DEBUG_ENABLED && hdr.type == PKT_HEARTBEAT) {
    DEBUG_PRINT("RX HB from %c seq=%lu\n",
      hdr.src_role == NODE_ROLE_GROUND ? 'G' : 'A', (unsigned long)hdr.seq);
  }

  return true;
}

void on_receive(const esp_now_recv_info_t* info, const uint8_t* data, int len) {
  const bool ok = handle_received_packet(data, len);
  if (!ok) {
    return;
  }

  if (DEBUG_ENABLED && len >= (int)sizeof(DemPacketHeader)) {
    DemPacketHeader hdr;
    memcpy(&hdr, data, sizeof(hdr));
    DEBUG_PRINT("RX type=%u len=%u from ", hdr.type, hdr.payload_len);
    print_mac(info->src_addr);
    DEBUG_PRINT("\n");
  }
}

bool send_packet(PacketType type, const uint8_t* payload, uint8_t payload_len) {
  if (payload_len > MAX_PAYLOAD_SIZE) {
    return false;
  }

  uint8_t tx_buf[sizeof(DemPacketHeader) + MAX_PAYLOAD_SIZE];
  DemPacketHeader hdr;
  hdr.proto_ver = DEM_PROTO_VERSION;
  hdr.type = (uint8_t)type;
  hdr.src_role = local_role_id();
  hdr.payload_len = payload_len;
  hdr.seq = tx_seq++;
  hdr.uptime_ms = millis();

  memcpy(tx_buf, &hdr, sizeof(hdr));
  if (payload_len > 0) {
    memcpy(tx_buf + sizeof(hdr), payload, payload_len);
  }

  const esp_err_t err = esp_now_send(broadcast_addr, tx_buf,
                                     sizeof(DemPacketHeader) + payload_len);
  if (err == ESP_OK) {
    stats.tx_enqueued++;
    return true;
  }

  stats.tx_enqueue_failed++;
  return false;
}

void setup_espnow() {
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  esp_wifi_set_channel(ESPNOW_CHANNEL, WIFI_SECOND_CHAN_NONE);

  if (esp_now_init() != ESP_OK) {
    DEBUG_PRINT("ESP-NOW initialization FAILED\n");
    while (1) {
      delay(1000);
    }
  }

  esp_now_register_send_cb(on_send_done);
  esp_now_register_recv_cb(on_receive);

  esp_now_peer_info_t peer_info = {};
  memcpy(peer_info.peer_addr, broadcast_addr, 6);
  peer_info.channel = ESPNOW_CHANNEL;
  peer_info.encrypt = false;

  if (!esp_now_is_peer_exist(broadcast_addr)) {
    if (esp_now_add_peer(&peer_info) != ESP_OK) {
      DEBUG_PRINT("Failed to add broadcast peer\n");
      while (1) {
        delay(1000);
      }
    }
  }

  DEBUG_PRINT("ESP-NOW initialized on channel %d\n", ESPNOW_CHANNEL);
}

void send_heartbeat_if_due() {
  static uint32_t last_heartbeat = 0;
  const uint32_t now = millis();
  if ((now - last_heartbeat) < HEARTBEAT_INTERVAL_MS) {
    return;
  }
  last_heartbeat = now;
  send_packet(PKT_HEARTBEAT, nullptr, 0);
}

void bridge_uplink_serial() {
  uint8_t payload[MAX_PAYLOAD_SIZE];
  uint8_t sent_packets = 0;

  while (uplink_serial().available() > 0 && sent_packets < MAX_TX_PACKETS_PER_LOOP) {
    uint8_t count = 0;
    while (uplink_serial().available() > 0 && count < MAX_PAYLOAD_SIZE) {
      const int v = uplink_serial().read();
      if (v < 0) {
        break;
      }
      payload[count++] = (uint8_t)v;
    }

    if (count == 0) {
      break;
    }

    send_packet(PKT_SERIAL_DATA, payload, count);
    sent_packets++;
  }
}

#ifdef GROUND_NODE
void process_console_line(const String& line) {
  if (!line.startsWith("::")) {
    return;
  }

  if (line == "::help") {
    DEBUG_PRINT("Commands: ::help ::stats ::reset\n");
  } else if (line == "::stats") {
    print_stats(true);
  } else if (line == "::reset") {
    reset_stats();
    DEBUG_PRINT("Stats reset\n");
  } else {
    DEBUG_PRINT("Unknown command: %s\n", line.c_str());
  }
}

void handle_console_commands() {
#if ENABLE_CONSOLE_COMMANDS
  while (Serial.available() > 0) {
    const int c = Serial.read();
    if (c < 0) {
      return;
    }

    if (c == '\r') {
      continue;
    }

    if (c == '\n') {
      if (cmd_buffer.length() > 0) {
        process_console_line(cmd_buffer);
        cmd_buffer = "";
      }
      continue;
    }

    if (cmd_buffer.length() < 80) {
      cmd_buffer += (char)c;
    }
  }
#endif
}
#else
void handle_console_commands() {}
#endif

void setup() {
  Serial.begin(115200);
  delay(150);

#ifdef AIR_NODE
  Serial2.begin(FC_BAUDRATE, SERIAL_8N1, FC_RX_PIN, FC_TX_PIN);
#endif

  DEBUG_PRINT("\n\n=== ESP32 ESP-NOW Telemetry Bridge ===\n");
  DEBUG_PRINT("Version: %s\n", DEM_VERSION);
  DEBUG_PRINT("MODE: %s NODE\n", role_name());

  setup_espnow();

  DEBUG_PRINT("Local STA MAC: %s\n", WiFi.macAddress().c_str());
  DEBUG_PRINT("Bridge initialized. Ready.\n\n");
}

void loop() {
  handle_console_commands();
  bridge_uplink_serial();
  send_heartbeat_if_due();
  print_stats();
  delay(2);
}