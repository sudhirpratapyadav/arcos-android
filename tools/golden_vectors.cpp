// Emits ARCOS frames using ARIA's own packet sender, so the Java encoder in this
// library can be diffed against the reference C++ implementation rather than
// against someone's reading of it.
//
// Build and run: see tools/golden_vectors.sh
#include <cstdio>
#include <string>
#include <vector>
#include "Aria/Aria.h"
#include "Aria/ArRobotPacketSender.h"
#include "Aria/ArDeviceConnection.h"
#include "Aria/ArCommands.h"

// A connection that goes nowhere and just records what was written.
class CaptureConn : public ArDeviceConnection {
public:
  std::vector<unsigned char> last;
  int read(const char *, unsigned int, unsigned int) override { return 0; }
  int write(const char *data, unsigned int size) override {
    last.assign((const unsigned char *)data, (const unsigned char *)data + size);
    return (int)size;
  }
  int getStatus() override { return STATUS_OPEN; }
  bool openSimple() override { return true; }
  const char *getOpenMessage(int) override { return ""; }
  ArTime getTimeRead(int) override { return ArTime(); }
  bool isTimeStamping() override { return false; }
};

static CaptureConn conn;
static ArRobotPacketSender sender(&conn);

static void emit(const char *label) {
  printf("%s ", label);
  for (size_t i = 0; i < conn.last.size(); i++) {
    printf("%02X", conn.last[i]);
    if (i + 1 < conn.last.size()) printf(" ");
  }
  printf("\n");
  conn.last.clear();
}

static void com(const char *label, unsigned char c) {
  sender.com(c);
  emit(label);
}

static void comInt(const char *label, unsigned char c, short v) {
  sender.comInt(c, v);
  emit(label);
}

static void com2(const char *label, unsigned char c, char hi, char lo) {
  sender.com2Bytes(c, hi, lo);
  emit(label);
}

static void comStr(const char *label, unsigned char c, const char *s, int len) {
  sender.comStrN(c, s, len);
  emit(label);
}

int main() {
  com("SYNC0", 0);
  com("SYNC1", 1);
  com("SYNC2", 2);
  com("PULSE", ArCommands::PULSE);
  com("ESTOP", ArCommands::ESTOP);

  comInt("OPEN_1", ArCommands::OPEN, 1);
  comInt("CLOSE_1", ArCommands::CLOSE, 1);
  comInt("ENABLE_1", ArCommands::ENABLE, 1);
  comInt("ENABLE_0", ArCommands::ENABLE, 0);
  comInt("SONAR_1", ArCommands::SONAR, 1);
  comInt("SONAR_0", ArCommands::SONAR, 0);
  comInt("SETO_0", ArCommands::SETO, 0);
  comInt("STOP_1", ArCommands::STOP, 1);
  comInt("HOSTBAUD_2", ArCommands::HOSTBAUD, 2);

  comInt("VEL_0", ArCommands::VEL, 0);
  comInt("VEL_300", ArCommands::VEL, 300);
  comInt("VEL_NEG300", ArCommands::VEL, -300);
  comInt("VEL_1", ArCommands::VEL, 1);
  comInt("VEL_NEG1", ArCommands::VEL, -1);
  comInt("VEL_255", ArCommands::VEL, 255);
  comInt("VEL_256", ArCommands::VEL, 256);
  comInt("VEL_32767", ArCommands::VEL, 32767);
  comInt("VEL_NEG32767", ArCommands::VEL, -32767);

  comInt("RVEL_45", ArCommands::RVEL, 45);
  comInt("RVEL_NEG45", ArCommands::RVEL, -45);
  comInt("SETV_500", ArCommands::SETV, 500);
  comInt("SETRV_100", ArCommands::SETRV, 100);
  comInt("SETA_300", ArCommands::SETA, 300);
  comInt("SETA_NEG300", ArCommands::SETA, -300);
  comInt("SETRA_100", ArCommands::SETRA, 100);
  comInt("MOVE_1000", ArCommands::MOVE, 1000);
  comInt("MOVE_NEG1000", ArCommands::MOVE, -1000);
  comInt("HEAD_90", ArCommands::HEAD, 90);
  comInt("DHEAD_NEG90", ArCommands::DHEAD, -90);

  com2("VEL2_10_10", ArCommands::VEL2, 10, 10);
  com2("VEL2_NEG10_10", ArCommands::VEL2, -10, 10);
  com2("VEL2_10_NEG10", ArCommands::VEL2, 10, -10);
  com2("VEL2_127_NEG128", ArCommands::VEL2, 127, -128);
  com2("VEL2_0_0", ArCommands::VEL2, 0, 0);

  const char beep[] = {5, 20};
  comStr("SAY_BEEP", ArCommands::SAY, beep, 2);
  const char beep4[] = {5, 20, 5, 30};
  comStr("SAY_BEEP4", ArCommands::SAY, beep4, 4);

  return 0;
}
