#!/bin/bash
# Switches adb to Wi-Fi so the phone's USB port is free for the robot.
#
# Uses the classic `adb tcpip` route rather than Android 11's "Wireless debugging"
# pairing flow: no pairing code, no mDNS (which campus networks tend to block),
# and MIUI does not bury it behind a sub-screen.
#
# Needs the phone on USB just for this. The setting survives until the phone
# reboots or changes network, so this is a once-per-boot chore.
#
#   ./setup-wifi-adb.sh
set -e
ADB="${ADB:-$HOME/platform-tools/adb}"
PORT="${PORT:-5555}"

echo "== waiting for the phone on USB (plug it in now)"
timeout 60 "$ADB" wait-for-device || { echo "no phone appeared within 60s"; exit 1; }

MODEL=$("$ADB" shell getprop ro.product.model | tr -d '\r')
echo "   found: $MODEL"

IP=$("$ADB" shell ip -f inet addr show wlan0 2>/dev/null \
     | grep -oE 'inet [0-9.]+' | awk '{print $2}' | head -1)
[ -n "$IP" ] || { echo "phone has no wlan0 address - is Wi-Fi on?"; exit 1; }
echo "   phone Wi-Fi address: $IP"

PC=$(ip -f inet addr show 2>/dev/null | grep -oE 'inet [0-9.]+' \
     | awk '{print $2}' | grep -v '^127\.' | head -1)
echo "   this PC:             $PC"

echo "== enabling adb over TCP on port $PORT"
"$ADB" tcpip "$PORT"
sleep 2

echo "== connecting over Wi-Fi"
"$ADB" connect "$IP:$PORT"
sleep 1

if "$ADB" devices | grep -q "$IP:$PORT"; then
  echo
  echo "Connected: $IP:$PORT"
  echo "Unplug the USB cable and the phone stays reachable."
  echo
  echo "  $ADB -s $IP:$PORT install -r <apk>"
  echo "  $ADB -s $IP:$PORT logcat"
  echo
  echo "$IP" > .phone-ip
  echo "(address saved to .phone-ip)"
else
  echo
  echo "Could not reach $IP:$PORT over Wi-Fi."
  echo "Most likely the network isolates clients from each other, which is normal"
  echo "on campus Wi-Fi. Fall back to a phone hotspot with this PC joined to it."
  exit 1
fi
