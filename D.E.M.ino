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
#include <esp_arduino_version.h>
#include <esp_camera.h>

#if !defined(ARDUINO_ARCH_ESP32)
  #error "This sketch requires an ESP32 board package. In Arduino IDE, select Tools > Board > ESP32 Dev Module."
#endif

#define DEM_VERSION "0.3.39"

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

#define DEBUG_ENABLED 0

#define ENABLE_CONSOLE_COMMANDS 0
#define ESPNOW_CHANNEL 1
#define HEARTBEAT_INTERVAL_MS 1000
#define STATS_INTERVAL_MS 10000
#define FC_BAUDRATE 115200
#define DEM_PROTO_VERSION 1
#define MAX_PAYLOAD_SIZE 180
#define MAX_TX_PACKETS_PER_LOOP 4

#define AIR_HW_ESP32_DEV 1
#define AIR_HW_ESP32_CAM 2

#ifndef AIR_HW_PROFILE
  // Auto-select ESP32-CAM profile when common board macros are present.
  #if defined(ARDUINO_ESP32_CAM) || defined(ARDUINO_AI_THINKER_ESP32_CAM) || defined(ARDUINO_ESP32CAM)
    #define AIR_HW_PROFILE AIR_HW_ESP32_CAM
  #else
    #define AIR_HW_PROFILE AIR_HW_ESP32_DEV
  #endif
#endif

// Keep camera disabled by default to protect telemetry timing.
#ifndef ENABLE_AIR_CAMERA
  #define ENABLE_AIR_CAMERA 0
#endif

#define AIR_CAMERA_CAPTURE_INTERVAL_MS 1500
#define AIR_STATUS_LINK_TIMEOUT_MS 1500
#define AIR_STATUS_BLINK_PERIOD_MS 1000
#define AIR_STATUS_BLINK_ON_MS 40
#define DEM_MAVLINK_MSG_ID_COMMAND_LONG 76
#define DEM_MAVLINK_MSG_ID_COMMAND_ACK 77
#define DEM_MAV_CMD_LED_CONTROL 31000
#define DEM_MAV_CMD_DO_SET_RELAY 181
#define DEM_LED_MODE_AUTO 0
#define DEM_LED_MODE_OFF 1
#define DEM_LED_MODE_ON 2
#define DEM_MAV_RESULT_ACCEPTED 0
#define DEM_MAV_RESULT_DENIED 2
#define DEM_MAVLINK_COMMAND_ACK_CRC_EXTRA 143

#ifdef AIR_NODE
  #if AIR_HW_PROFILE == AIR_HW_ESP32_DEV
    #define FC_RX_PIN 16
    #define FC_TX_PIN 17
  #elif AIR_HW_PROFILE == AIR_HW_ESP32_CAM
    // ESP32-CAM profile uses pins typically exposed on AI-Thinker modules.
    // Avoid SD-card usage on these pins while running FC UART.
    #define FC_RX_PIN 13
    #define FC_TX_PIN 14
  #else
    #error "Unsupported AIR_HW_PROFILE"
  #endif
#else
  #define FC_RX_PIN 16
  #define FC_TX_PIN 17
#endif
#ifdef GROUND_NODE
#define OFFLINE_MAVLINK_HEARTBEAT 0
#define OFFLINE_HEARTBEAT_GRACE_MS 2000
#endif

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

uint16_t mavlink_crc_v1(const uint8_t* buffer, uint16_t length, uint8_t crc_extra);
bool send_packet(PacketType type, const uint8_t* payload, uint8_t payload_len);

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
uint32_t last_air_rx_ms = 0;
uint8_t mavlink_seq = 0;

#ifdef GROUND_NODE
String cmd_buffer;
#endif

#ifdef AIR_NODE
uint32_t last_fc_uart_rx_ms = 0;
#endif

#if defined(AIR_NODE) && (AIR_HW_PROFILE == AIR_HW_ESP32_CAM)
static const uint8_t AIR_STATUS_LED_PIN = 4;
uint8_t air_led_mode = DEM_LED_MODE_AUTO;

struct MavlinkFilterState {
  bool in_frame;
  uint16_t expected_len;
  uint16_t index;
  uint8_t frame[300];
};

struct BridgeAckState {
  bool pending;
  uint16_t command;
  uint8_t result;
  uint8_t seq;
  uint8_t sysid;
  uint8_t compid;
};

MavlinkFilterState gcs_to_fc_filter = {false, 0, 0, {0}};
BridgeAckState bridge_ack = {false, 0, DEM_MAV_RESULT_ACCEPTED, 0, 1, 1};

uint16_t mavlink_frame_expected_len(const uint8_t* frame, uint16_t index) {
  if (index < 2) {
    return 0;
  }

  const uint8_t stx = frame[0];
  const uint16_t payload_len = frame[1];

  if (stx == 0xFE) {
    return (uint16_t)(8 + payload_len);
  }

  if (stx == 0xFD) {
    if (index < 4) {
      return 0;
    }
    const bool signed_frame = (frame[2] & 0x01) != 0;
    return (uint16_t)(12 + payload_len + (signed_frame ? 13 : 0));
  }

  return 0;
}

bool set_air_led_mode_from_param(float param1) {
  const int mode = (int)param1;
  if (mode < DEM_LED_MODE_AUTO || mode > DEM_LED_MODE_ON) {
    return false;
  }
  air_led_mode = (uint8_t)mode;
  return true;
}

void queue_bridge_command_ack(uint16_t command, uint8_t result, uint8_t sysid, uint8_t compid) {
  bridge_ack.pending = true;
  bridge_ack.command = command;
  bridge_ack.result = result;
  bridge_ack.sysid = sysid;
  bridge_ack.compid = compid;
}

void service_bridge_command_ack() {
  if (!bridge_ack.pending) {
    return;
  }

  uint8_t ack[8 + 3];
  ack[0] = 0xFE;
  ack[1] = 3;
  ack[2] = bridge_ack.seq++;
  ack[3] = bridge_ack.sysid;
  ack[4] = bridge_ack.compid;
  ack[5] = DEM_MAVLINK_MSG_ID_COMMAND_ACK;
  ack[6] = (uint8_t)(bridge_ack.command & 0xFF);
  ack[7] = (uint8_t)((bridge_ack.command >> 8) & 0xFF);
  ack[8] = bridge_ack.result;

  const uint16_t crc = mavlink_crc_v1(&ack[1], 8, DEM_MAVLINK_COMMAND_ACK_CRC_EXTRA);
  ack[9] = (uint8_t)(crc & 0xFF);
  ack[10] = (uint8_t)((crc >> 8) & 0xFF);

  if (send_packet(PKT_SERIAL_DATA, ack, (uint8_t)sizeof(ack))) {
    bridge_ack.pending = false;
  }
}

bool handle_bridge_control_frame(const uint8_t* frame, uint16_t frame_len) {
  if (frame_len < 8) {
    return false;
  }

  const uint8_t stx = frame[0];
  uint8_t msgid = 0;
  const uint8_t* payload = nullptr;
  uint8_t payload_len = frame[1];

  if (stx == 0xFE) {
    if (frame_len < 8) {
      return false;
    }
    msgid = frame[5];
    payload = frame + 6;
  } else if (stx == 0xFD) {
    if (frame_len < 12) {
      return false;
    }
    msgid = frame[7];
    payload = frame + 10;
  } else {
    return false;
  }

  if (msgid != DEM_MAVLINK_MSG_ID_COMMAND_LONG || payload_len < 33) {
    return false;
  }

  const uint16_t command = (uint16_t)payload[28] | ((uint16_t)payload[29] << 8);

  // Custom bridge command: param1 is led mode (0 auto, 1 off, 2 on).
  if (command == DEM_MAV_CMD_LED_CONTROL) {
    float param1 = 0.0f;
    memcpy(&param1, payload, sizeof(float));
    const bool ok = set_air_led_mode_from_param(param1);
    queue_bridge_command_ack(
      command,
      ok ? DEM_MAV_RESULT_ACCEPTED : DEM_MAV_RESULT_DENIED,
      payload[30],
      payload[31]
    );
    return true;
  }

  // Mission Planner "Actions" compatible path:
  // MAV_CMD_DO_SET_RELAY with relay number 9 controls bridge LED mode.
  // param2: 0=off, 1=on, 2=auto. Other relay numbers pass through to FC.
  if (command == DEM_MAV_CMD_DO_SET_RELAY) {
    float relay_num_f = 0.0f;
    float relay_value = 0.0f;
    memcpy(&relay_num_f, payload, sizeof(float));
    memcpy(&relay_value, payload + 4, sizeof(float));

    const int relay_num = (int)relay_num_f;
    if (relay_num != 9) {
      return false;
    }

    if (relay_value >= 1.5f) {
      set_air_led_mode_from_param((float)DEM_LED_MODE_AUTO);
    } else if (relay_value >= 0.5f) {
      set_air_led_mode_from_param((float)DEM_LED_MODE_ON);
    } else {
      set_air_led_mode_from_param((float)DEM_LED_MODE_OFF);
    }
    queue_bridge_command_ack(command, DEM_MAV_RESULT_ACCEPTED, payload[30], payload[31]);
    return true;
  }

  return false;
}

uint8_t filter_ground_to_fc_payload(const uint8_t* input, uint8_t input_len, uint8_t* output) {
  uint8_t out_len = 0;

  for (uint8_t i = 0; i < input_len; i++) {
    const uint8_t b = input[i];

    if (!gcs_to_fc_filter.in_frame) {
      if (b == 0xFE || b == 0xFD) {
        gcs_to_fc_filter.in_frame = true;
        gcs_to_fc_filter.expected_len = 0;
        gcs_to_fc_filter.index = 0;
        gcs_to_fc_filter.frame[gcs_to_fc_filter.index++] = b;
      } else {
        output[out_len++] = b;
      }
      continue;
    }

    if (gcs_to_fc_filter.index >= sizeof(gcs_to_fc_filter.frame)) {
      for (uint16_t j = 0; j < gcs_to_fc_filter.index; j++) {
        output[out_len++] = gcs_to_fc_filter.frame[j];
      }
      gcs_to_fc_filter.in_frame = false;
      gcs_to_fc_filter.expected_len = 0;
      gcs_to_fc_filter.index = 0;
      output[out_len++] = b;
      continue;
    }

    gcs_to_fc_filter.frame[gcs_to_fc_filter.index++] = b;

    if (gcs_to_fc_filter.expected_len == 0) {
      gcs_to_fc_filter.expected_len = mavlink_frame_expected_len(
        gcs_to_fc_filter.frame, gcs_to_fc_filter.index
      );
      if (gcs_to_fc_filter.expected_len > sizeof(gcs_to_fc_filter.frame)) {
        for (uint16_t j = 0; j < gcs_to_fc_filter.index; j++) {
          output[out_len++] = gcs_to_fc_filter.frame[j];
        }
        gcs_to_fc_filter.in_frame = false;
        gcs_to_fc_filter.expected_len = 0;
        gcs_to_fc_filter.index = 0;
      }
    }

    if (gcs_to_fc_filter.expected_len > 0 && gcs_to_fc_filter.index == gcs_to_fc_filter.expected_len) {
      const bool consumed = handle_bridge_control_frame(
        gcs_to_fc_filter.frame, gcs_to_fc_filter.expected_len
      );

      if (!consumed) {
        for (uint16_t j = 0; j < gcs_to_fc_filter.expected_len; j++) {
          output[out_len++] = gcs_to_fc_filter.frame[j];
        }
      }

      gcs_to_fc_filter.in_frame = false;
      gcs_to_fc_filter.expected_len = 0;
      gcs_to_fc_filter.index = 0;
    }
  }

  return out_len;
}

void setup_air_status_led() {
  pinMode(AIR_STATUS_LED_PIN, OUTPUT);
  digitalWrite(AIR_STATUS_LED_PIN, LOW);
}

void service_air_status_led() {
  const uint32_t now = millis();
  bool led_on = false;

  if (air_led_mode == DEM_LED_MODE_ON) {
    led_on = true;
  } else if (air_led_mode == DEM_LED_MODE_AUTO) {
    const bool link_active = (now - last_fc_uart_rx_ms) <= AIR_STATUS_LINK_TIMEOUT_MS;
    if (link_active) {
      const uint32_t phase = now % AIR_STATUS_BLINK_PERIOD_MS;
      led_on = phase < AIR_STATUS_BLINK_ON_MS;
    }
  }

  digitalWrite(AIR_STATUS_LED_PIN, led_on ? HIGH : LOW);
}
#else
void setup_air_status_led() {}
void service_air_status_led() {}
void service_bridge_command_ack() {}
#endif

#if defined(AIR_NODE) && (AIR_HW_PROFILE == AIR_HW_ESP32_CAM) && ENABLE_AIR_CAMERA
static bool camera_initialized = false;

bool setup_air_camera() {
  camera_config_t config = {};
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = 5;
  config.pin_d1 = 18;
  config.pin_d2 = 19;
  config.pin_d3 = 21;
  config.pin_d4 = 36;
  config.pin_d5 = 39;
  config.pin_d6 = 34;
  config.pin_d7 = 35;
  config.pin_xclk = 0;
  config.pin_pclk = 22;
  config.pin_vsync = 25;
  config.pin_href = 23;
  config.pin_sscb_sda = 26;
  config.pin_sscb_scl = 27;
  config.pin_pwdn = 32;
  config.pin_reset = -1;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.frame_size = FRAMESIZE_QVGA;
  config.jpeg_quality = 20;
  config.fb_count = 1;
  config.grab_mode = CAMERA_GRAB_LATEST;

  const esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    DEBUG_PRINT("Camera init failed: %d\n", err);
    return false;
  }

  camera_initialized = true;
  DEBUG_PRINT("Camera init OK\n");
  return true;
}

void service_air_camera_if_due() {
  static uint32_t last_capture_ms = 0;
  if (!camera_initialized) {
    return;
  }

  const uint32_t now = millis();
  if ((now - last_capture_ms) < AIR_CAMERA_CAPTURE_INTERVAL_MS) {
    return;
  }
  last_capture_ms = now;

  camera_fb_t* fb = esp_camera_fb_get();
  if (!fb) {
    DEBUG_PRINT("Camera capture failed\n");
    return;
  }

  DEBUG_PRINT("Camera frame bytes=%u\n", (unsigned int)fb->len);
  esp_camera_fb_return(fb);
}
#else
bool setup_air_camera() {
  return false;
}

void service_air_camera_if_due() {}
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

#ifdef GROUND_NODE
  if (hdr.src_role == NODE_ROLE_AIR) {
    last_air_rx_ms = millis();
  }
#endif

  update_sequence_metrics(hdr.src_role, hdr.seq);
  stats.rx_packets++;
  stats.rx_bytes += hdr.payload_len;

  const uint8_t* payload = data + sizeof(DemPacketHeader);

  if (hdr.type == PKT_SERIAL_DATA && hdr.payload_len > 0) {
#if defined(AIR_NODE) && (AIR_HW_PROFILE == AIR_HW_ESP32_CAM)
    uint8_t filtered_payload[MAX_PAYLOAD_SIZE];
    const uint8_t filtered_len = filter_ground_to_fc_payload(
      payload, hdr.payload_len, filtered_payload
    );
    if (filtered_len > 0) {
      downlink_serial().write(filtered_payload, filtered_len);
    }
#else
    downlink_serial().write(payload, hdr.payload_len);
#endif
  }

  if (DEBUG_ENABLED && hdr.type == PKT_HEARTBEAT) {
    DEBUG_PRINT("RX HB from %c seq=%lu\n",
      hdr.src_role == NODE_ROLE_GROUND ? 'G' : 'A', (unsigned long)hdr.seq);
  }

  return true;
}

// ESP32 core 3.x uses esp_now_recv_info_t; 2.x uses MAC pointer callback.
#if ESP_ARDUINO_VERSION_MAJOR >= 3
void on_receive(const esp_now_recv_info_t* info, const uint8_t* data, int len) {
#else
void on_receive(const uint8_t* mac_addr, const uint8_t* data, int len) {
#endif
  const bool ok = handle_received_packet(data, len);
  if (!ok) {
    return;
  }

  if (DEBUG_ENABLED && len >= (int)sizeof(DemPacketHeader)) {
    DemPacketHeader hdr;
    memcpy(&hdr, data, sizeof(hdr));
    DEBUG_PRINT("RX type=%u len=%u from ", hdr.type, hdr.payload_len);
#if ESP_ARDUINO_VERSION_MAJOR >= 3
    print_mac(info->src_addr);
#else
    print_mac(mac_addr);
#endif
    DEBUG_PRINT("\n");
  }
}

bool send_packet(PacketType type, const uint8_t* payload, uint8_t payload_len) {
  if (payload_len > MAX_PAYLOAD_SIZE) {
    DEBUG_PRINT("Payload size exceeds maximum limit\n");
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
    DEBUG_PRINT("Packet enqueued: type=%u, len=%u\n", type, payload_len);
    return true;
  }

  stats.tx_enqueue_failed++;
  DEBUG_PRINT("Failed to enqueue packet: type=%u, len=%u, err=%d\n", type, payload_len, err);
  return false;
}

void setup_espnow() {
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  esp_wifi_set_channel(ESPNOW_CHANNEL, WIFI_SECOND_CHAN_NONE);

  if (esp_now_init() != ESP_OK) {
#if DEBUG_ENABLED
    Serial.println("[ERROR] ESP-NOW initialization FAILED");
#endif
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
#if DEBUG_ENABLED
      Serial.println("[ERROR] Failed to add broadcast peer");
#endif
      while (1) {
        delay(1000);
      }
    }
  }

  DEBUG_PRINT("ESP-NOW initialized on channel %d\n", ESPNOW_CHANNEL);
}

uint16_t x25_crc_accumulate(uint8_t data, uint16_t crc) {
  uint8_t tmp = data ^ (uint8_t)(crc & 0xFF);
  tmp ^= (tmp << 4);
  return (crc >> 8) ^ ((uint16_t)tmp << 8) ^ ((uint16_t)tmp << 3) ^ ((uint16_t)tmp >> 4);
}

uint16_t mavlink_crc_v1(const uint8_t* buffer, uint16_t length, uint8_t crc_extra) {
  uint16_t crc = 0xFFFF;
  for (uint16_t i = 0; i < length; i++) {
    crc = x25_crc_accumulate(buffer[i], crc);
  }
  crc = x25_crc_accumulate(crc_extra, crc);
  return crc;
}

#ifdef GROUND_NODE
void send_offline_mavlink_heartbeat() {
#if OFFLINE_MAVLINK_HEARTBEAT
  // MAVLink v1 HEARTBEAT message (msg id 0, payload len 9, crc extra 50)
  // Payload order: custom_mode, type, autopilot, base_mode, system_status, mavlink_version
  uint8_t packet[17];
  packet[0] = 0xFE; // v1 STX
  packet[1] = 9;    // payload length
  packet[2] = mavlink_seq++;
  packet[3] = 1;    // sysid
  packet[4] = 1;    // compid
  packet[5] = 0;    // msgid HEARTBEAT

  const uint32_t custom_mode = 0;
  packet[6]  = (uint8_t)(custom_mode & 0xFF);
  packet[7]  = (uint8_t)((custom_mode >> 8) & 0xFF);
  packet[8]  = (uint8_t)((custom_mode >> 16) & 0xFF);
  packet[9]  = (uint8_t)((custom_mode >> 24) & 0xFF);
  packet[10] = 2;    // MAV_TYPE_QUADROTOR
  packet[11] = 12;   // MAV_AUTOPILOT_PX4
  packet[12] = 0x01; // MAV_MODE_FLAG_CUSTOM_MODE_ENABLED
  packet[13] = 4;    // MAV_STATE_ACTIVE
  packet[14] = 3;   // mavlink v1 indicator in payload

  const uint16_t crc = mavlink_crc_v1(&packet[1], 14, 50);
  packet[15] = (uint8_t)(crc & 0xFF);
  packet[16] = (uint8_t)((crc >> 8) & 0xFF);

  Serial.write(packet, sizeof(packet));
#endif
}
#endif

#ifdef GROUND_NODE
void send_heartbeat_if_due() {
  static uint32_t last_heartbeat = 0;
  const uint32_t now = millis();
  if ((now - last_heartbeat) < HEARTBEAT_INTERVAL_MS) {
    return;
  }
  last_heartbeat = now;
  send_packet(PKT_HEARTBEAT, nullptr, 0);
  if ((now - last_air_rx_ms) >= OFFLINE_HEARTBEAT_GRACE_MS) {
    send_offline_mavlink_heartbeat();
  }
}
#else
void send_heartbeat_if_due() {
  static uint32_t last_heartbeat = 0;
  const uint32_t now = millis();
  if ((now - last_heartbeat) < HEARTBEAT_INTERVAL_MS) {
    return;
  }
  last_heartbeat = now;
  send_packet(PKT_HEARTBEAT, nullptr, 0);
}
#endif

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
#ifdef AIR_NODE
      last_fc_uart_rx_ms = millis();
#endif
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
#if DEBUG_ENABLED
  Serial.println("=== DEM GROUND NODE STARTUP ===");
#endif

#ifdef AIR_NODE
  Serial2.begin(FC_BAUDRATE, SERIAL_8N1, FC_RX_PIN, FC_TX_PIN);
  setup_air_status_led();
  setup_air_camera();
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
#ifdef AIR_NODE
  service_air_status_led();
  service_bridge_command_ack();
  service_air_camera_if_due();
#endif
  print_stats();
  delay(2);
}