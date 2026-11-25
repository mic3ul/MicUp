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
 * capture audio or encode PCM. Each Opus packet is written on its own Ogg page.
 */
public final class OggOpusMuxer implements Closeable {
    private static final int MAX_SEGMENTS = 255;
    private static final int MAX_PACKET_BYTES = 65024;
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
    private long lastWrittenGranule;
    private long pages;
    private Pending pending;
    private boolean finished;

    public OggOpusMuxer(File file, int inputSampleRate, int channels,
                        int preSkipAt48k, String vendor) throws IOException {
        if (file == null) throw new IllegalArgumentException("file == null");
        if (inputSampleRate <= 0) throw new IllegalArgumentException("Invalid sample rate");
        if (channels < 1 || channels > 2) {
            throw new IllegalArgumentException("Opus channels must be 1 or 2");
        }
        if (preSkipAt48k < 0 || preSkipAt48k > 65535) {
            throw new IllegalArgumentException("Invalid pre-skip");
        }

        this.preSkip = preSkipAt48k;
        this.serial = new SecureRandom().nextInt();
        this.output = new FileOutputStream(file, false);
        writePage(0x02, 0L, makeOpusHead(inputSampleRate, channels, preSkipAt48k));
        writePage(0x00, 0L, makeOpusTags(vendor == null ? "weirdo" : vendor));
    }

    /**
     * Writes an encoded Opus packet. The duration argument is expressed on
     * Ogg's 48 kHz granule clock and must agree with the packet TOC.
     */
    public synchronized void writePacket(byte[] packet, int durationSamplesAt48k)
            throws IOException {
        ensureOpen();
        if (packet == null || packet.length == 0) {
            throw new IllegalArgumentException("Empty Opus packet");
        }
        if (packet.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("Packet too large for one Ogg page");
        }

        int packetDuration = getPacketDurationSamplesAt48k(packet);
        if (durationSamplesAt48k != packetDuration) {
            throw new IllegalArgumentException(
                    "Duration does not match Opus TOC: expected " + packetDuration
                            + " samples at 48 kHz, received " + durationSamplesAt48k);
        }

        if (pending != null) {
            writePage(0x00, pending.granule, pending.packet);
            lastWrittenGranule = pending.granule;
        }

        totalSamples += packetDuration;
        pending = new Pending(packet.clone(), preSkip + totalSamples);
    }

    /**
     * Derives packet duration from the Opus TOC. This prevents incorrect
     * granule positions caused by passing an arbitrary duration to writePacket.
     */
    public static int getPacketDurationSamplesAt48k(byte[] packet) {
        if (packet == null || packet.length == 0) {
            throw new IllegalArgumentException("Empty Opus packet");
        }

        int toc = packet[0] & 0xff;
        int config = toc >>> 3;
        int frameCode = toc & 0x03;
        int samplesPerFrame;

        if (config < 12) {
            int[] silkDurations = {480, 960, 1920, 2880};
            samplesPerFrame = silkDurations[config & 0x03];
        } else if (config < 16) {
            samplesPerFrame = (config & 0x02) == 0 ? 480 : 960;
        } else {
            int[] celtDurations = {120, 240, 480, 960};
            samplesPerFrame = celtDurations[config & 0x03];
        }

        final int frameCount;
        if (frameCode == 0) {
            frameCount = 1;
        } else if (frameCode == 1 || frameCode == 2) {
            frameCount = 2;
        } else {
            if (packet.length < 2) {
                throw new IllegalArgumentException("Opus code-3 packet has no frame-count byte");
            }
            // RFC 6716: M occupies bits 2..7; the low bits are VBR/padding flags.
            frameCount = (packet[1] >>> 2) & 0x3f;
            if (frameCount < 1 || frameCount > 48) {
                throw new IllegalArgumentException("Invalid Opus frame count: " + frameCount);
            }
        }

        int duration = samplesPerFrame * frameCount;
        if (duration > 5760) {
            throw new IllegalArgumentException("Opus packet duration exceeds 120 ms");
        }
        return duration;
    }

    public synchronized long getWrittenPageCount() {
        return pages;
    }

    public synchronized long getGranulePosition() {
        return preSkip + totalSamples;
    }

    public synchronized void finish() throws IOException {
        finish(preSkip + totalSamples);
    }

    /**
     * Finalizes the stream at the exact valid sample position on the 48 kHz
     * granule clock. Pass a lower value than getGranulePosition() when the
     * final encoded packet was padded to a complete frame.
     */
    public synchronized void finish(long finalGranulePosition) throws IOException {
        if (finished) return;

        if (pending != null) {
            long minimum = Math.max(lastWrittenGranule, preSkip);
            if (finalGranulePosition < minimum || finalGranulePosition > pending.granule) {
                throw new IllegalArgumentException(
                        "Final granule position must be between " + minimum
                                + " and " + pending.granule + ", received "
                                + finalGranulePosition);
            }
            writePage(0x04, finalGranulePosition, pending.packet);
            pending = null;
        } else {
            long expected = preSkip + totalSamples;
            if (finalGranulePosition != expected) {
                throw new IllegalArgumentException(
                        "Cannot trim a stream without a pending final packet");
            }
            writeEmptyEosPage(expected);
        }

        output.flush();
        output.getFD().sync();
        finished = true;
        output.close();
    }

    /** Closes an incomplete stream without writing an EOS page. */
    public synchronized void abort() throws IOException {
        if (finished) return;
        pending = null;
        finished = true;
        output.close();
    }

    @Override
    public synchronized void close() throws IOException {
        finish();
    }

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
