package com.bjtu.railtransit.vehicle.protocol704;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

// local-v1, not validated with real hardware
class Protocol704HmiInputParserTest {

    @Test
    void parseTractionCut_bit0set_car0cut() {
        byte[] frame = buildTractionCutFrame((byte) 0x01);
        HmiTractionCutRequest req = Protocol704HmiInputParser.parseTractionCut(frame);
        assertNotNull(req);
        assertTrue(req.getCarCutMask()[0]);
        for (int i = 1; i < 6; i++) {
            assertFalse(req.getCarCutMask()[i]);
        }
    }

    @Test
    void parseTractionCut_invalidHeader_returnsNull() {
        byte[] frame = buildTractionCutFrame((byte) 0x01);
        frame[0] = 0x00;
        assertNull(Protocol704HmiInputParser.parseTractionCut(frame));
    }

    @Test
    void parseTractionCut_allCarsCut() {
        byte[] frame = buildTractionCutFrame((byte) 0x3F);
        HmiTractionCutRequest req = Protocol704HmiInputParser.parseTractionCut(frame);
        assertNotNull(req);
        boolean[] mask = req.getCarCutMask();
        for (int i = 0; i < 6; i++) {
            assertTrue(mask[i], "car " + i + " should be cut");
        }
    }

    @Test
    void parseTractionCut_shortFrame_returnsNull() {
        byte[] shortFrame = new byte[25];
        assertNull(Protocol704HmiInputParser.parseTractionCut(shortFrame));
        assertNull(Protocol704HmiInputParser.parseTractionCut(null));
    }

    @Test
    void parseTractionCut_wrongTotalLen_returnsNull() {
        byte[] frame = buildTractionCutFrame((byte) 0x01);
        ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).putShort(4, (short) 25);
        assertNull(Protocol704HmiInputParser.parseTractionCut(frame));
    }

    @Test
    void parseTractionCut_mixedMask() {
        byte[] frame = buildTractionCutFrame((byte) 0x15);
        HmiTractionCutRequest req = Protocol704HmiInputParser.parseTractionCut(frame);
        assertNotNull(req);
        boolean[] mask = req.getCarCutMask();
        assertTrue(mask[0]);
        assertFalse(mask[1]);
        assertTrue(mask[2]);
        assertFalse(mask[3]);
        assertTrue(mask[4]);
        assertFalse(mask[5]);
    }

    private byte[] buildTractionCutFrame(byte nPullCtrl) {
        byte[] frame = new byte[Protocol704HmiInputParser.FRAME_LENGTH];
        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0, 0xAA55AA55);
        bb.putShort(4, (short) Protocol704HmiInputParser.FRAME_LENGTH);
        bb.putShort(6, (short) 2);
        bb.putLong(8, System.currentTimeMillis());
        frame[24] = nPullCtrl;
        frame[25] = 0x00;
        return frame;
    }
}
