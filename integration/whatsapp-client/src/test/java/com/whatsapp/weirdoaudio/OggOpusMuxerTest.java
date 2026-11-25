package com.whatsapp.weirdoaudio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class OggOpusMuxerTest {
    private static final class Page {
        final int flags;
        final long granule;
        final long sequence;
        final int[] lacing;
        final byte[] body;

        Page(int flags, long granule, long sequence, int[] lacing, byte[] body) {
            this.flags = flags;
            this.granule = granule;
            this.sequence = sequence;
            this.lacing = lacing;
            this.body = body;
        }
    }

    @Test
    public void writesHeadersGranulesAndFinalEosPage() throws Exception {
        File file = File.createTempFile("weirdo-ogg-", ".opus");
        try {
            OggOpusMuxer muxer = new OggOpusMuxer(file, 48000, 1, 312, "weirdo-test");
            muxer.writePacket(new byte[] {(byte) 0xF8, 0x00}, 960);
            muxer.writePacket(new byte[] {(byte) 0xF8, 0x01}, 960);
            assertEquals(1272L, muxer.getGranulePosition());
            muxer.finish();

            List<Page> pages = readPages(Files.readAllBytes(file.toPath()));
            assertEquals(4, pages.size());

            assertEquals(0x02, pages.get(0).flags); // BOS
            assertEquals(0x00, pages.get(1).flags);
            assertEquals(0x00, pages.get(2).flags);
            assertEquals(0x04, pages.get(3).flags); // EOS on final audio page

            assertEquals(0L, pages.get(0).granule);
            assertEquals(0L, pages.get(1).granule);
            assertEquals(1272L, pages.get(2).granule);
            assertEquals(2232L, pages.get(3).granule);

            for (int i = 0; i < pages.size(); i++) {
                assertEquals(i, pages.get(i).sequence);
            }
            assertEquals("OpusHead", new String(pages.get(0).body, 0, 8, "US-ASCII"));
            assertEquals("OpusTags", new String(pages.get(1).body, 0, 8, "US-ASCII"));
            assertEquals(19, pages.get(0).body.length);
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test
    public void exact255BytePacketGetsRequiredZeroLacingTerminator() throws Exception {
        File file = File.createTempFile("weirdo-ogg-lacing-", ".opus");
        try {
            byte[] packet = new byte[255];
            packet[0] = (byte) 0xF8; // CELT 20 ms, one frame
            OggOpusMuxer muxer = new OggOpusMuxer(file, 48000, 1, 312, "test");
            muxer.writePacket(packet, 960);
            muxer.finish();

            List<Page> pages = readPages(Files.readAllBytes(file.toPath()));
            assertEquals(3, pages.size());
            Page audio = pages.get(2);
            assertEquals(2, audio.lacing.length);
            assertEquals(255, audio.lacing[0]);
            assertEquals(0, audio.lacing[1]);
            assertEquals(0x04, audio.flags);
            assertEquals(255, audio.body.length);
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test
    public void derivesSupportedDurationsFromOpusToc() {
        assertEquals(480, OggOpusMuxer.getPacketDurationSamplesAt48k(
                new byte[] {0x00, 0x00})); // SILK 10 ms
        assertEquals(960, OggOpusMuxer.getPacketDurationSamplesAt48k(
                new byte[] {(byte) 0xF8, 0x00})); // CELT 20 ms
        assertEquals(5760, OggOpusMuxer.getPacketDurationSamplesAt48k(
                new byte[] {(byte) 0xFB, 0x18})); // six 20 ms frames
        assertThrows(IllegalArgumentException.class,
                () -> OggOpusMuxer.getPacketDurationSamplesAt48k(
                        new byte[] {(byte) 0xFB, 0x00})); // zero frames
        assertThrows(IllegalArgumentException.class,
                () -> OggOpusMuxer.getPacketDurationSamplesAt48k(
                        new byte[] {(byte) 0xFB, (byte) 0xC4})); // 49 frames
    }

    @Test
    public void rejectsCallerDurationThatDisagreesWithPacketToc() throws Exception {
        File file = File.createTempFile("weirdo-ogg-duration-", ".opus");
        try {
            OggOpusMuxer muxer = new OggOpusMuxer(file, 48000, 1, 312, "test");
            assertThrows(IllegalArgumentException.class,
                    () -> muxer.writePacket(new byte[] {(byte) 0xF8, 0x00}, 480));
            muxer.writePacket(new byte[] {(byte) 0xF8, 0x00}, 960);
            muxer.finish();

            List<Page> pages = readPages(Files.readAllBytes(file.toPath()));
            assertEquals(3, pages.size());
            assertEquals(0x04, pages.get(2).flags);
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    private static List<Page> readPages(byte[] data) {
        List<Page> pages = new ArrayList<>();
        int offset = 0;

        while (offset < data.length) {
            assertTrue("incomplete Ogg page header", data.length - offset >= 27);
            assertEquals('O', data[offset]);
            assertEquals('g', data[offset + 1]);
            assertEquals('g', data[offset + 2]);
            assertEquals('S', data[offset + 3]);
            assertEquals(0, data[offset + 4]);

            int segmentCount = data[offset + 26] & 0xff;
            int headerLength = 27 + segmentCount;
            assertTrue("incomplete lacing table", data.length - offset >= headerLength);

            int bodyLength = 0;
            int[] lacing = new int[segmentCount];
            for (int i = 0; i < segmentCount; i++) {
                lacing[i] = data[offset + 27 + i] & 0xff;
                bodyLength += lacing[i];
            }

            int pageLength = headerLength + bodyLength;
            assertTrue("incomplete Ogg page body", data.length - offset >= pageLength);
            byte[] pageBytes = new byte[pageLength];
            System.arraycopy(data, offset, pageBytes, 0, pageLength);
            assertEquals(readU32(data, offset + 22), oggCrc(pageBytes));

            byte[] body = new byte[bodyLength];
            System.arraycopy(data, offset + headerLength, body, 0, bodyLength);
            pages.add(new Page(
                    data[offset + 5] & 0xff,
                    readU64(data, offset + 6),
                    readU32(data, offset + 18),
                    lacing,
                    body));

            offset += pageLength;
        }

        assertEquals("all bytes must belong to complete Ogg pages", data.length, offset);
        assertFalse("stream must contain at least the headers", pages.isEmpty());
        return pages;
    }

    private static long readU32(byte[] b, int p) {
        return (b[p] & 0xffL)
                | ((b[p + 1] & 0xffL) << 8)
                | ((b[p + 2] & 0xffL) << 16)
                | ((b[p + 3] & 0xffL) << 24);
    }

    private static long readU64(byte[] b, int p) {
        return readU32(b, p) | (readU32(b, p + 4) << 32);
    }

    private static int oggCrc(byte[] page) {
        int crc = 0;
        for (int i = 0; i < page.length; i++) {
            int value = (i >= 22 && i < 26) ? 0 : (page[i] & 0xff);
            int index = ((crc >>> 24) ^ value) & 0xff;
            crc = (crc << 8) ^ crcTable()[index];
        }
        return crc;
    }

    private static int[] crcTable() {
        int[] table = new int[256];
        for (int i = 0; i < table.length; i++) {
            int value = i << 24;
            for (int bit = 0; bit < 8; bit++) {
                value = (value & 0x80000000) != 0
                        ? (value << 1) ^ 0x04C11DB7
                        : value << 1;
            }
            table[i] = value;
        }
        return table;
    }
}
