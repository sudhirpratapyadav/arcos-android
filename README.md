# arcos-android

Drive a Pioneer 3-DX from an Android phone. No ROS, no ARIA, no Linux PC.

`ArcosRobot` speaks ARCOS — the serial protocol built into the microcontroller of
every Pioneer, PeopleBot and AmigoBot — directly from Java, over a USB-serial
cable, a TCP socket, or a built-in simulator.

```java
ArcosRobot robot = new ArcosRobot(new UsbSerialTransport(context));
robot.addListener(new ArcosListener() {
    @Override public void onState(RobotState s) {
        Log.i("robot", s.x + "mm  " + s.theta + "°  " + s.batteryVoltage + "V");
    }
});
robot.connect();
robot.enableMotors(true);
robot.drive(300, 20);        // 300 mm/s forward, 20 deg/s left
```

## Why this exists

The normal way to drive a P3-DX is ROSARIA, which wraps ARIA, which opens
`/dev/ttyUSB0`. None of that transfers to a phone:

- Stock Android kernels ship **without the usbserial drivers** (`ftdi_sio`,
  `cp210x`, `ch341`). There is no `/dev/ttyUSB0` to open, and root does not
  create one — on a Redmi Note 8 Pro, `/sys/bus/usb-serial` does not exist at all.
- So ARIA cannot be cross-compiled into working shape either: its serial layer
  has no device to talk to.

Everything has to go through Android's USB host API instead. ARCOS itself turns
out to be small — framing, a checksum, about a dozen commands and one status
packet — so this library implements it directly.

## Install

Gradle, via JitPack:

```gradle
repositories { maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.sudhirpratapyadav:arcos-android:0.1.0' }
```

Or build the artifacts yourself — this needs only a JDK and the SDK build tools,
no Gradle:

```bash
./build.sh              # arcos.jar, arcos.aar, arcos-demo.apk, and the tests
./build.sh test         # tests only; no SDK required
SDK=/path/to/sdk ./build.sh
```

`SDK` must point at a directory holding `aapt2`, `android.jar`, `zipalign` and
`lib/{d8,apksigner}.jar`.

## Hardware

```
phone USB-C ── OTG adapter ── USB-serial adapter ── DB9 ── P3-DX HOST port
```

- The P3-DX host port is **RS-232**, not RS-485, and comes up at **9600 baud**.
  The library raises the link to 38400 after connecting, because at 9600 a status
  packet carrying sonar nearly saturates the line.
- The robot's DB9 is wired as DCE, so a straight-through cable is normally
  correct. If the handshake stays silent, try a null-modem adapter.
- Any adapter usb-serial-for-android supports works: FTDI, CP210x, CH34x,
  Prolific, CDC-ACM.
- **The phone will not charge over OTG.** Use a powered OTG hub for long runs.

USB access needs the user's consent before the port will open:

```java
UsbSerialDriver driver = UsbSerialTransport.firstDriver(context);
UsbPermission.request(context, driver.getDevice(), granted -> {
    if (granted) robot.connect();
});
```

## Transports

| Transport | Use |
|---|---|
| `UsbSerialTransport` | the real cable |
| `TcpTransport` | MobileSim on port 8101, or a serial-to-Ethernet bridge |
| `SimTransport` | a simulated robot inside your app — no hardware at all |

`SimTransport` answers the handshake, honours the watchdog, integrates a
differential-drive model in a room with an obstacle, and streams status packets
at 10 Hz with odometry, wheel velocities, battery and ray-cast sonar. The demo
app and the whole test suite run against it.

## Safety

Two watchdogs, and you should understand both:

- **The robot's.** ARCOS cuts the motors if it hears nothing for 2 seconds.
- **The library's.** Because this library sends a keepalive every cycle, the
  robot's watchdog can no longer protect you from a frozen app — and Android
  freezes backgrounded apps routinely. So if no control method is called for
  `setCommandTimeout(ms)` (**2000 ms by default**), the setpoints are zeroed.
  Pass `0` for ARIA's behaviour, where a setpoint holds until changed.

`eStop()` bypasses the deceleration limit. The motors come up disabled and the
robot disables them again after any emergency stop, so `enableMotors(true)` has
to be called again afterwards.

## Demo app

`./build.sh` produces `build/arcos-demo.apk`: a thumb-stick, a live sonar radar
with an odometry trail, telemetry, a motor toggle and an E-STOP. It connects over
USB, TCP or the simulator, so it is worth installing before the robot arrives.

## Correctness

The protocol is verified against ARIA rather than against a reading of it.
`tools/golden_vectors.cpp` links the real AriaCoda and dumps the bytes its packet
sender puts on the wire for 42 commands — including the awkward ones, where
negative arguments travel as a magnitude under a different type tag, and VEL2's
two wheel bytes get narrowed to a signed short. The test suite diffs this
library's output against those bytes.

```
./build.sh test
```

runs that, plus framer tests (truncated frames, split frames, line noise, buffer
overflow) and an end-to-end session against the simulator covering the handshake,
motion, both watchdogs and the emergency stop.

Regenerate the vectors with `tools/golden_vectors.sh` — it clones and builds
AriaCoda for you.

## Debugging with the USB port occupied

Once the robot cable is in the phone, there is no adb cable. Two things cover it.

**adb over Wi-Fi.** `tools/setup-wifi-adb.sh` switches adb to TCP while the phone
is still on USB, so the port is then free. It uses the classic `adb tcpip` route
rather than Android 11's pairing flow, which needs mDNS that campus networks tend
to block. The setting does not survive a reboot, so this is a once-per-boot chore.

**An HTTP server in the app.** More useful than adb, because it answers the
question adb cannot: what bytes actually crossed the wire.

```bash
curl phone:8080/api/state            # telemetry as JSON
curl phone:8080/api/raw              # hex of the last ~400 serial exchanges
curl phone:8080/api/log              # handshake steps, baud switches, dropped frames
curl "phone:8080/api/drive?v=200&w=0"
curl phone:8080/api/estop
```

`LoggingTransport` wraps any transport to capture that hex, and is cheap enough to
leave on permanently. Everything is a GET so plain curl drives it — fine for a
debug tool on a trusted network, wrong for anything exposed.

On MIUI specifically: turn off "MIUI optimization" in developer options, or every
install prompts for confirmation regardless of what "always allow" claims.

## Verified on a real robot

Run against a Pioneer 3-DX (`IITRaj_3823`, subtype `p3dx-sh`, firmware 3.0) over a
CH340 USB-serial adapter at 9600 baud. Confirmed working: the sync handshake,
OPEN/PULSE, motor enable, `VEL` and `RVEL`, wheel-velocity telemetry, raw encoder
packets, x/y odometry, gyro heading, all eight sonar transducers, and battery.

Both control paths are confirmed on hardware: the demo app's on-screen joystick
driving the robot from the phone, and the same robot driven remotely over HTTP
from a laptop while the phone held the USB cable.

Commanding `RVEL 30` produced wheel velocities of -87 and +85 mm/s. Those imply
0.511 rad/s across a 334 mm wheelbase — against the P3-DX's actual 330 mm, and
the commanded 30 deg/s. Independently, heading tracked at 29.3 deg/s.

Three bugs surfaced that no amount of testing against the simulator would have
found, because the simulator was built from the same assumptions as the library:

- **`p3dx-sh` is the subtype real robots report**, not `p3dx` — and its distance
  factor is 1.0, not 0.485. Falling back to `p3dx` reports every distance at half
  its true value. Measured on the robot as 1.007 mm/count before ARIA's own
  `p3dx-sh.p` was consulted, which says 1.0.
- **x and y are a wrapping 15-bit counter, not an absolute position.** Reversing
  a millimetre past the origin sends `0x7FFF`, which the old code read as +15892
  mm. They have to be accumulated as deltas.
- **A client that dies mid-handshake wedges the controller.** ARCOS answers SYNC2
  then waits for OPEN, and ignores further sync attempts — silent at every baud,
  looking exactly like a dead robot. Only a CLOSE broadcast recovers it.

A fourth was found by the tests afterwards: `resetOdometry()` re-origined
immediately, but SETO takes a cycle or two to land, and the counter's jump to
zero looked like one large backwards delta.

## Protocol notes

For anyone extending this. Frames are:

```
FA FB <count> <id> <payload...> <ckHi> <ckLo>
```

`count` is the number of bytes after it, so payload + id + 2. Sixteen-bit values
inside the payload are **little-endian**; the checksum in the trailer is
**big-endian**. The checksum sums the payload as big-endian 16-bit pairs and XORs
in a lone trailing byte.

Connecting is `SYNC0`, `SYNC1`, `SYNC2` — each echoes back, and the `SYNC2` reply
carries the robot's name, type and subtype — then `OPEN`. After that the robot
streams a status packet (`0x32`) at about 10 Hz on its own.

Command ids worth knowing: `ENABLE=4`, `VEL=11` (mm/s), `RVEL=21` (deg/s),
`VEL2=32` (per-wheel), `SONAR=28`, `STOP=29`, `SETO=7`, `ESTOP=55`.

P3-DX conversion factors: 0.485 mm per distance unit, 2π/4096 radians per angle
unit, VEL2 divisor 20. `RobotParams` carries these per model and picks them from
the subtype the robot reports.

## License

MIT — see [LICENSE](LICENSE).

The library is a clean-room implementation of ARCOS written from the protocol
description, so it carries none of AriaCoda's GPL-2.0 terms. See
[NOTICE.md](NOTICE.md) for the details: usb-serial-for-android (MIT) is vendored
in `libs/`, and the one file that links AriaCoda is a test-vector generator that
ships in neither the `.aar` nor the `.jar`.
