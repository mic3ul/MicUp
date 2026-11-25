package com.whatsapp.weirdoaudio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class PcmFrameAccumulatorTest {
    @Test
    public void joinsIrregularChunksAndPadsOnlyTheFinalFrame() throws Exception {
        PcmFrameAccumulator accumulator = new PcmFrameAccumulator();
        List<short[]> encodedFrames = new ArrayList<>();

        short[] first = new short[500];
        short[] second = new short[700];
        for (int i = 0; i < first.length; i++) first[i] = (short) (i + 1);
        for (int i = 0; i < second.length; i++) second[i] = (short) (i + 501);

        accumulator.append(first, first.length, frame -> encodedFrames.add(frame.clone()));
        assertEquals(500, accumulator.getPendingSamples());
        accumulator.append(second, second.length, frame -> encodedFrames.add(frame.clone()));

        assertEquals(1, encodedFrames.size());
        assertEquals(240, accumulator.getPendingSamples());
        assertEquals(1200L, accumulator.getCapturedSamples());

        accumulator.flushPadded(frame -> encodedFrames.add(frame.clone()));
        assertEquals(2, encodedFrames.size());
        assertEquals(0, accumulator.getPendingSamples());

        short[] firstFrame = encodedFrames.get(0);
        for (int i = 0; i < 960; i++) {
            short expected = (short) (i + 1);
            assertEquals(expected, firstFrame[i]);
        }

        short[] secondFrame = encodedFrames.get(1);
        for (int i = 0; i < 240; i++) {
            assertEquals((short) (i + 961), secondFrame[i]);
        }
        for (int i = 240; i < 960; i++) {
            assertEquals(0, secondFrame[i]);
        }
    }

    @Test
    public void emptyAndExactlyAlignedStreamsDoNotCreateExtraFrames() throws Exception {
        PcmFrameAccumulator accumulator = new PcmFrameAccumulator();
        List<short[]> encodedFrames = new ArrayList<>();
        accumulator.flushPadded(frame -> encodedFrames.add(frame.clone()));
        assertEquals(0, encodedFrames.size());

        short[] exact = new short[1920];
        accumulator.append(exact, exact.length, frame -> encodedFrames.add(frame.clone()));
        accumulator.flushPadded(frame -> encodedFrames.add(frame.clone()));
        assertEquals(2, encodedFrames.size());
        assertEquals(1920L, accumulator.getCapturedSamples());
    }

    @Test
    public void rejectsFrameCountOutsideInputArray() {
        PcmFrameAccumulator accumulator = new PcmFrameAccumulator();
        assertThrows(IllegalArgumentException.class,
                () -> accumulator.append(new short[4], 5, frame -> {}));
        assertThrows(IllegalArgumentException.class,
                () -> accumulator.append(null, 0, frame -> {}));
    }
}
