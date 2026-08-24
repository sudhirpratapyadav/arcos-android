package com.arcos.transport;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.arcos.Transport;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.List;

/**
 * The real cable: phone USB-C, an OTG adapter, a USB-serial adapter, then RS-232
 * into the robot's HOST port.
 *
 * <p>Android has no {@code /dev/ttyUSB0} — stock kernels ship without the
 * usbserial drivers, so even a rooted phone cannot open one. Everything goes
 * through the USB host API instead, with
 * <a href="https://github.com/mik3y/usb-serial-for-android">usb-serial-for-android</a>
 * supplying the chip-specific parts for FTDI, CP210x, CH34x, Prolific and CDC-ACM.
 *
 * <p>Two things trip people up:
 *
 * <ul>
 *   <li><b>Permission.</b> Android will not open a USB device until the user grants
 *       access to it. Call {@link UsbPermission#request} first, or attach an
 *       intent filter so plugging the adapter in prompts automatically.
 *   <li><b>Which end is which.</b> A P3-DX presents a DB9 wired as DCE, so a
 *       straight-through cable is normally right. If the handshake stays silent
 *       with the adapter's lights showing traffic, try a null-modem adapter.
 * </ul>
 */
public final class UsbSerialTransport implements Transport {

    /** ARCOS powers up at 9600 on the host port. */
    public static final int DEFAULT_BAUD = 9600;
    /** Writes are small; this is generous. */
    private static final int WRITE_TIMEOUT_MS = 1000;

    private final Context context;
    private final UsbSerialDriver driver;
    private volatile int baud;

    private UsbDeviceConnection connection;
    private UsbSerialPort port;
    private volatile boolean open;

    /**
     * The first USB-serial adapter attached, at 9600 baud.
     *
     * @throws IOException if nothing recognisable is plugged in
     */
    public UsbSerialTransport(Context context) throws IOException {
        this(context, firstDriver(context), DEFAULT_BAUD);
    }

    /** A specific adapter at a specific rate. */
    public UsbSerialTransport(Context context, UsbSerialDriver driver, int baud) {
        if (driver == null) {
            throw new IllegalArgumentException("driver is null");
        }
        this.context = context.getApplicationContext();
        this.driver = driver;
        this.baud = baud;
    }

    /** Every USB-serial adapter currently attached. */
    public static List<UsbSerialDriver> available(Context context) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        return UsbSerialProber.getDefaultProber().findAllDrivers(manager);
    }

    /** The first attached adapter, or null when there is none. */
    public static UsbSerialDriver firstDriver(Context context) throws IOException {
        List<UsbSerialDriver> drivers = available(context);
        if (drivers.isEmpty()) {
            throw new IOException("no USB-serial adapter found — check the OTG cable");
        }
        return drivers.get(0);
    }

    /** The USB device behind this transport, for permission requests and UI. */
    public UsbDevice device() {
        return driver.getDevice();
    }

    @Override public void open() throws IOException {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        UsbDevice dev = driver.getDevice();
        if (!manager.hasPermission(dev)) {
            throw new IOException("no USB permission for " + describe(dev)
                    + " — call UsbPermission.request() first");
        }
        connection = manager.openDevice(dev);
        if (connection == null) {
            throw new IOException("could not open " + describe(dev));
        }
        List<UsbSerialPort> ports = driver.getPorts();
        if (ports.isEmpty()) {
            throw new IOException("adapter exposes no serial ports");
        }
        port = ports.get(0);
        port.open(connection);
        // ARCOS is 8N1 with no flow control on every Pioneer.
        port.setParameters(baud, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE);
        // Some adapters hold the robot in reset until these are asserted.
        try {
            port.setDTR(true);
            port.setRTS(true);
        } catch (Exception ignored) {
            // Not every chip exposes the control lines; the link works without them.
        }
        open = true;
    }

    @Override public int read(byte[] dst, int off, int len, int timeoutMs) throws IOException {
        UsbSerialPort p = port;
        if (!open || p == null) {
            throw new IOException("port is closed");
        }
        // A zero timeout means "block forever" to some drivers, which would wedge
        // the cycle thread.
        int timeout = Math.max(1, timeoutMs);
        if (off == 0) {
            return Math.max(0, p.read(dst, len, timeout));
        }
        byte[] tmp = new byte[len];
        int n = Math.max(0, p.read(tmp, len, timeout));
        System.arraycopy(tmp, 0, dst, off, n);
        return n;
    }

    @Override public void write(byte[] data) throws IOException {
        UsbSerialPort p = port;
        if (!open || p == null) {
            throw new IOException("port is closed");
        }
        p.write(data, WRITE_TIMEOUT_MS);
    }

    @Override public boolean isOpen() {
        return open;
    }

    @Override public String name() {
        UsbDevice dev = driver.getDevice();
        return describe(dev) + " @" + baud;
    }

    @Override public void setBaudRate(int newBaud) throws IOException {
        UsbSerialPort p = port;
        if (p == null) {
            this.baud = newBaud;
            return;
        }
        p.setParameters(newBaud, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE);
        this.baud = newBaud;
    }

    @Override public boolean supportsBaudRate() {
        return true;
    }

    @Override public int baudRate() {
        return baud;
    }

    @Override public void flushInput() throws IOException {
        UsbSerialPort p = port;
        if (!open || p == null) {
            return;
        }
        byte[] sink = new byte[256];
        // Drain until a read comes back empty, so a baud change does not leave
        // half a frame of garbage in the buffer.
        while (p.read(sink, sink.length, 1) > 0) {
            // discard
        }
    }

    @Override public void close() {
        open = false;
        UsbSerialPort p = port;
        port = null;
        if (p != null) {
            try {
                p.close();
            } catch (IOException ignored) {
                // Closing a device that has already been unplugged; nothing to do.
            }
        }
        UsbDeviceConnection c = connection;
        connection = null;
        if (c != null) {
            c.close();
        }
    }

    private static String describe(UsbDevice dev) {
        String product = null;
        try {
            product = dev.getProductName();
        } catch (Throwable ignored) {
            // getProductName needs API 21+ and can be null on some adapters.
        }
        if (product != null && !product.isEmpty()) {
            return product;
        }
        return String.format("USB %04X:%04X", dev.getVendorId(), dev.getProductId());
    }
}
