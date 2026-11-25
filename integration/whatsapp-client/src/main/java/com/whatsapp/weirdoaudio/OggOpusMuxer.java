package com.whatsapp.weirdoaudio;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Ogg Opus muxer using the 4 KiB page batching observed in WhatsApp PTT files. */
public final class OggOpusMuxer implements Closeable {
    private static final int MAX_SEGMENTS = 255;
    private static final int MAX_PACKET_BYTES = 65024;
    private static final int TARGET_PAGE_BODY_BYTES = 4096;
    private static final int CRC_POLY = 0x04C11DB7;
    private static final int[] CRC_TABLE = buildCrcTable();

    private final FileOutputStream output;
    private final int preSkip;
    private final int serial;
    private final List<Integer> audioLacing = new ArrayList<>();
    private final ByteArrayOutputStream audioBody =
            new ByteArrayOutputStream(TARGET_PAGE_BODY_BYTES + 2048);

    private int sequence;
    private long totalSamples;
    private long audioPageGranule;
    private long lastWrittenGranule;
    private long pages;
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
        this.audioPageGranule = preSkipAt48k;
        this.lastWrittenGranule = preSkipAt48k;
        this.output = new FileOutputStream(file, false);

        writeSinglePacketPage(0x02, 0L, makeOpusHead(inputSampleRate, channels, preSkipAt48k));
        writeSinglePacketPage(0x00, 0L, makeOpusTags(vendor == null ? "weirdo" : vendor));
    }

    public synchronized void writePacket(byte[] packet, int durationSamplesAt48k)
            throws IOException {
        ensureOpen();
        if (packet == null || packet.length == 0) {
            throw new IllegalArgumentException("Empty Opus packet");
        }
        if (packet.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("Packet too large for one Ogg page");
        }
        if (durationSamplesAt48k <= 0) {
            throw new IllegalArgumentException("Opus packet duration must be positive");
        }

        int parsedDuration = getPacketDurationSamplesAt48k(packet);
        if (durationSamplesAt48k != parsedDuration) {
            throw new IllegalArgumentException(
                    "Duration does not match Opus TOC: expected " + parsedDuration
                            + " samples at 48 kHz, received " + durationSamplesAt48k);
        }

        int requiredSegments = packet.length / 255 + 1;
        if (requiredSegments > MAX_SEGMENTS) {
            throw new IllegalArgumentException("Packet requires too many Ogg lacing segments");
        }

        // A page that has reached 4 KiB is flushed only when the next packet
        // arrives, leaving the last page available for exact granule trimming.
        if (!audioLacing.isEmpty() &&
                (audioBody.size() >= TARGET_PAGE_BODY_BYTES ||
                        audioLacing.size() + requiredSegments > MAX_SEGMENTS)) {
            flushAudioPage(0x00, audioPageGranule);
        }

        int remaining = packet.length;
        for (int i = 0; i < requiredSegments; i++) {
            int lace = Math.min(255, remaining);
            audioLacing.add(lace);
            remaining -= lace;
        }
        audioBody.write(packet, 0, packet.length);

        if (Long.MAX_VALUE - totalSamples < parsedDuration) {
            throw new IllegalStateException("Ogg granule sample counter overflow");
        }
        totalSamples += parsedDuration;
        audioPageGranule = preSkip + totalSamples;
    }

    /** Derive Opus packet duration from its TOC on the 48 kHz granule clock. */
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
            samplesPerFrame = (config & 0x01) == 0 ? 480 : 960;
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
            // In the TOC byte's network bit numbering, v/p occupy the two
            // leading bits; the M frame count is the low six numeric bits.
            frameCount = packet[1] & 0x3f;
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

    public synchronized long getWrittenPageCount() { return pages; }

    public synchronized long getGranulePosition() {
        return preSkip + totalSamples;
    }

    public synchronized void finish() throws IOException {
        finish(preSkip + totalSamples, true);
    }

    public synchronized void finish(long finalGranulePosition) throws IOException {
        finish(finalGranulePosition, true);
    }

    /**
     * markEos=false matches the final-page flag observed in the supplied
     * WhatsApp PTT sample. The default overload remains standards-oriented.
     */
    public synchronized void finish(long finalGranulePosition, boolean markEos)
            throws IOException {
        if (finished) return;

        if (!audioLacing.isEmpty()) {
            long minimum = Math.max(lastWrittenGranule, preSkip);
            if (finalGranulePosition < minimum ||
                    finalGranulePosition > audioPageGranule) {
                throw new IllegalArgumentException(
                        "Final granule position must be between " + minimum
                                + " and " + audioPageGranule + ", received "
                                + finalGranulePosition);
            }
            flushAudioPage(markEos ? 0x04 : 0x00, finalGranulePosition);
        } else {
            long expected = preSkip + totalSamples;
            if (finalGranulePosition != expected) {
                throw new IllegalArgumentException(
                        "Cannot trim a stream without a pending final audio page");
            }
            if (markEos) writeEmptyEosPage(expected);
        }

        output.flush();
        output.getFD().sync();
        finished = true;
        output.close();
    }

    public synchronized void abort() throws IOException {
        if (finished) return;
        audioLacing.clear();
        audioBody.reset();
        finished = true;
        output.close();
    }

    @Override
    public synchronized void close() throws IOException { finish(); }

    private void ensureOpen() throws IOException {
        if (finished) throw new IOException("Ogg Opus stream already finished");
    }

    private void flushAudioPage(int flags, long granule) throws IOException {
        int[] lacing = new int[audioLacing.size()];
        for (int i = 0; i < audioLacing.size(); i++) lacing[i] = audioLacing.get(i);
        writePage(flags, granule, lacing, audioBody.toByteArray());
        lastWrittenGranule = granule;
        audioLacing.clear();
        audioBody.reset();
    }

    private void writeSinglePacketPage(int flags, long granule, byte[] packet)
            throws IOException {
        int segments = packet.length / 255 + 1;
        if (segments > MAX_SEGMENTS) throw new IOException("Too many Ogg lacing segments");
        int[] lacing = new int[segments];
        int remaining = packet.length;
        for (int i = 0; i < segments; i++) {
            lacing[i] = Math.min(255, remaining);
            remaining -= lacing[i];
        }
        writePage(flags, granule, lacing, packet);
    }

    private void writePage(int flags, long granule, int[] lacing, byte[] body)
            throws IOException {
        if (lacing.length > MAX_SEGMENTS) throw new IOException("Too many Ogg lacing segments");
        int bodyLength = 0;
        for (int lace : lacing) bodyLength += lace;
        if (bodyLength != body.length) throw new IOException("Ogg lacing/body size mismatch");

        ByteArrayOutputStream b = new ByteArrayOutputStream(27 + lacing.length + body.length);
        putAscii(b, "OggS");
        b.write(0);
        b.write(flags);
        u64(b, granule);
        u32(b, serial);
        u32(b, sequence++);
        u32(b, 0);
        b.write(lacing.length);
        for (int lace : lacing) b.write(lace);
        b.write(body, 0, body.length);

        byte[] page = b.toByteArray();
        putU32(page, 22, crc(page));
        output.write(page);
        pages++;
    }

    private void writeEmptyEosPage(long granule) throws IOException {
        writePage(0x04, granule, new int[0], new byte[0]);
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
