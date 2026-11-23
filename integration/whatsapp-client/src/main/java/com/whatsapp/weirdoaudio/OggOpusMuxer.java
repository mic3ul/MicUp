package com.whatsapp.weirdoaudio;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Minimal Ogg Opus muxer. It accepts already encoded Opus packets; it does not
 * capture audio or encode PCM. One packet is stored per Ogg page.
 */
public final class OggOpusMuxer implements Closeable {
    private static final int MAX_SEGMENTS = 255;
    private static final int CRC_POLY = 0x04C11DB7;
    private static final int[] CRC_TABLE = buildCrcTable();

    private static final class Pending {
        final byte[] packet;
        final long granule;
        Pending(byte[] packet, long granule) {
            this.packet = packet;
            this.granule = granule;
        }
    }

    private final FileOutputStream output;
    private final int preSkip;
    private final int serial;
    private int sequence;
    private long totalSamples;
    private long pages;
    private Pending pending;
    private boolean finished;

    public OggOpusMuxer(File file, int inputSampleRate, int channels,
                        int preSkipAt48k, String vendor) throws IOException {
        if (file == null) throw new IllegalArgumentException("file == null");
        if (inputSampleRate <= 0) throw new IllegalArgumentException("Invalid sample rate");
        if (channels < 1 || channels > 2) throw new IllegalArgumentException("Opus channels must be 1 or 2");
        if (preSkipAt48k < 0 || preSkipAt48k > 65535) throw new IllegalArgumentException("Invalid pre-skip");
        this.preSkip = preSkipAt48k;
        this.serial = new SecureRandom().nextInt();
        this.output = new FileOutputStream(file, false);
        writePage(0x02, 0L, makeOpusHead(inputSampleRate, channels, preSkipAt48k));
        writePage(0x00, 0L, makeOpusTags(vendor == null ? "weirdo" : vendor));
    }

    /** durationSamplesAt48k is in the Ogg Opus 48 kHz granule clock (960 = 20 ms). */
    public synchronized void writePacket(byte[] packet, int durationSamplesAt48k) throws IOException {
        ensureOpen();
        if (packet == null || packet.length == 0) throw new IllegalArgumentException("Empty Opus packet");
        if (durationSamplesAt48k <= 0) throw new IllegalArgumentException("Invalid packet duration");
        if (packet.length > 65024) throw new IllegalArgumentException("Packet too large for one Ogg page");
        if (pending != null) writePage(0x00, pending.granule, pending.packet);
        totalSamples += durationSamplesAt48k;
        pending = new Pending(packet.clone(), preSkip + totalSamples);
    }

    public synchronized long getWrittenPageCount() { return pages; }
    public synchronized long getGranulePosition() { return preSkip + totalSamples; }

    public synchronized void finish() throws IOException {
        if (finished) return;
        if (pending != null) {
            writePage(0x04, pending.granule, pending.packet);
            pending = null;
        } else {
            writeEmptyEosPage(preSkip + totalSamples);
        }
        output.flush();
        output.getFD().sync();
        finished = true;
        output.close();
    }

    @Override public void close() throws IOException { finish(); }

    private void ensureOpen() throws IOException {
        if (finished) throw new IOException("Ogg Opus stream already finished");
    }

    private static byte[] makeOpusHead(int rate, int channels, int preSkip) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(19);
        putAscii(b, "OpusHead");
        b.write(1);
        b.write(channels);
        u16(b, preSkip);
        u32(b, rate);
        u16(b, 0);
        b.write(0);
        return b.toByteArray();
    }

    private static byte[] makeOpusTags(String vendor) {
        byte[] v = vendor.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream b = new ByteArrayOutputStream(16 + v.length);
        putAscii(b, "OpusTags");
        u32(b, v.length);
        b.write(v, 0, v.length);
        u32(b, 0);
        return b.toByteArray();
    }

    private void writePage(int type, long granule, byte[] packet) throws IOException {
        int segments = packet.length / 255 + 1;
        if (segments > MAX_SEGMENTS) throw new IOException("Too many Ogg lacing segments");

        ByteArrayOutputStream b = new ByteArrayOutputStream(27 + segments + packet.length);
        putAscii(b, "OggS");
        b.write(0);
        b.write(type);
        u64(b, granule);
        u32(b, serial);
        u32(b, sequence++);
        u32(b, 0);
        b.write(segments);

        int remaining = packet.length;
        for (int i = 0; i < segments; i++) {
            int lace = Math.min(255, remaining);
            b.write(lace);
            remaining -= lace;
        }
        b.write(packet, 0, packet.length);

        byte[] page = b.toByteArray();
        putU32(page, 22, crc(page));
        output.write(page);
        pages++;
    }

    private void writeEmptyEosPage(long granule) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(27);
        putAscii(b, "OggS");
        b.write(0);
        b.write(0x04);
        u64(b, granule);
        u32(b, serial);
        u32(b, sequence++);
        u32(b, 0);
        b.write(0);
        byte[] page = b.toByteArray();
        putU32(page, 22, crc(page));
        output.write(page);
        pages++;
    }

    private static void putAscii(ByteArrayOutputStream out, String text) {
        byte[] b = text.getBytes(StandardCharsets.US_ASCII);
        out.write(b, 0, b.length);
    }

    private static void u16(ByteArrayOutputStream out, int n) {
        out.write(n & 255);
        out.write((n >>> 8) & 255);
    }

    private static void u32(ByteArrayOutputStream out, long n) {
        out.write((int) n & 255);
        out.write((int) (n >>> 8) & 255);
        out.write((int) (n >>> 16) & 255);
        out.write((int) (n >>> 24) & 255);
    }

    private static void u64(ByteArrayOutputStream out, long n) {
        u32(out, n);
        u32(out, n >>> 32);
    }

    private static void putU32(byte[] b, int p, int n) {
        b[p] = (byte) n;
        b[p + 1] = (byte) (n >>> 8);
        b[p + 2] = (byte) (n >>> 16);
        b[p + 3] = (byte) (n >>> 24);
    }

    private static int[] buildCrcTable() {
        int[] t = new int[256];
        for (int i = 0; i < 256; i++) {
            int v = i << 24;
            for (int bit = 0; bit < 8; bit++) {
                v = (v & 0x80000000) != 0 ? (v << 1) ^ CRC_POLY : v << 1;
            }
            t[i] = v;
        }
        return t;
    }

    private static int crc(byte[] page) {
        int value = 0;
        for (byte b : page) {
            int index = ((value >>> 24) ^ (b & 255)) & 255;
            value = (value << 8) ^ CRC_TABLE[index];
        }
        return value;
    }
}
