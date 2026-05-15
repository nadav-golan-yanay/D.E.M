/*
 * ESP32 Built-In Radio Link Test (ESP-NOW)
 *
 * This sketch validates direct ESP32-to-ESP32 communication over the built-in
 * 2.4GHz radio (no nRF24 module required).
 */

#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>

#if !defined(ARDUINO_ARCH_ESP32)
  #error "This sketch requires an ESP32 board package. In Arduino IDE, select Tools > Board > ESP32 Dev Module."
#endif

#define DEM_VERSION "0.2.0"

// ============================================================================
// SKETCH CONFIGURATION
// ============================================================================
#define NODE_ROLE_GROUND 1
#define NODE_ROLE_AIR    2
#ifndef NODE_ROLE
  #define NODE_ROLE      NODE_ROLE_GROUND
#endif

#if NODE_ROLE == NODE_ROLE_GROUND
  #define GROUND_NODE
#elif NODE_ROLE == NODE_ROLE_AIR
  #define AIR_NODE
#else
  #error "Set NODE_ROLE to NODE_ROLE_GROUND or NODE_ROLE_AIR"
#endif

#define DEBUG_ENABLED 1
#define ESPNOW_CHANNEL 1
#define HEARTBEAT_INTERVAL_MS 1000

#if DEBUG_ENABLED
  #define DEBUG_PRINT(fmt, ...) do { \
    char buf[256]; \
    snprintf(buf, sizeof(buf), fmt, ##__VA_ARGS__); \
    Serial.print(buf); \
  } while(0)
#else
  #define DEBUG_PRINT(fmt, ...) do {} while(0)
#endif

struct LinkStats {
  uint32_t tx_ok;
  uint32_t tx_failed;
  uint32_t rx_packets;
};

struct HeartbeatPacket {
  uint32_t seq;
  uint32_t uptime_ms;
  uint8_t role;
  char tag[2];
};

LinkStats stats = {0, 0, 0};
uint8_t broadcast_addr[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

const char* role_name() {
#ifdef GROUND_NODE
  return "GROUND";
#else
  return "AIR";
#endif
}

void print_mac(const uint8_t* mac) {
  DEBUG_PRINT("%02X:%02X:%02X:%02X:%02X:%02X",
              mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
}

void on_send_done(const uint8_t* mac_addr, esp_now_send_status_t status) {
  if (status == ESP_NOW_SEND_SUCCESS) {
    stats.tx_ok++;
  } else {
    stats.tx_failed++;
  }
  (void)mac_addr;
}

void on_receive(const esp_now_recv_info_t* info, const uint8_t* data, int len) {
  stats.rx_packets++;

  DEBUG_PRINT("RX %d bytes from ", len);
  print_mac(info->src_addr);

  if (len >= (int)sizeof(HeartbeatPacket)) {
    HeartbeatPacket pkt;
    memcpy(&pkt, data, sizeof(pkt));
    DEBUG_PRINT(" role=%c seq=%lu uptime=%lu\n", pkt.role,
                (unsigned long)pkt.seq, (unsigned long)pkt.uptime_ms);
  } else {
    DEBUG_PRINT(" (non-heartbeat payload)\n");
  }
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

void send_heartbeat() {
  static uint32_t last_heartbeat = 0;
  static uint32_t seq = 0;

  const uint32_t now = millis();
  if ((now - last_heartbeat) < HEARTBEAT_INTERVAL_MS) {
    return;
  }
  last_heartbeat = now;

  HeartbeatPacket pkt = {};
  pkt.seq = seq++;
  pkt.uptime_ms = now;
#ifdef GROUND_NODE
  pkt.role = 'G';
#else
  pkt.role = 'A';
#endif
  pkt.tag[0] = 'H';
  pkt.tag[1] = 'B';

  const esp_err_t err = esp_now_send(broadcast_addr,
                                     reinterpret_cast<uint8_t*>(&pkt),
                                     sizeof(pkt));
  if (err != ESP_OK) {
    stats.tx_failed++;
    DEBUG_PRINT("TX enqueue failed: %d\n", (int)err);
  }
}

void print_stats() {
  static uint32_t last_stats = 0;
  const uint32_t now = millis();
  if ((now - last_stats) < 10000) {
    return;
  }
  last_stats = now;

  DEBUG_PRINT("\n=== ESP-NOW STATS ===\n");
  DEBUG_PRINT("TX ok: %lu\n", (unsigned long)stats.tx_ok);
  DEBUG_PRINT("TX failed: %lu\n", (unsigned long)stats.tx_failed);
  DEBUG_PRINT("RX packets: %lu\n", (unsigned long)stats.rx_packets);
  DEBUG_PRINT("=====================\n\n");
}

void setup() {
  Serial.begin(115200);
  delay(150);

  DEBUG_PRINT("\n\n=== ESP32 Built-In Radio Link Test ===\n");
  DEBUG_PRINT("Version: %s\n", DEM_VERSION);
  DEBUG_PRINT("MODE: %s NODE\n", role_name());

  setup_espnow();

  DEBUG_PRINT("Local STA MAC: ");
  DEBUG_PRINT("%s\n", WiFi.macAddress().c_str());
  DEBUG_PRINT("Bridge initialized. Ready.\n\n");
}

void loop() {
  send_heartbeat();
  print_stats();
  delay(5);
}