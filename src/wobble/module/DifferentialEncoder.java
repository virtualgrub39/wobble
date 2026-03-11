package wobble.module;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

import wobble.core.Port;
import wobble.core.Wobble;

public class DifferentialEncoder extends Mixer {
    public enum Format { FLOAT32, INT16, INT8 }

    private final Format format;
    private final int chunkSize;
    private float[] prev;
    private boolean enabled = true; // disable to have it pass through the converted PCM values without delta encoding

    private FloatBuffer floatOut;
    private ShortBuffer shortOut;
    private ByteBuffer byteOut;

    public DifferentialEncoder(Format format) {
        this.format = format;
        this.chunkSize = Wobble.INSTANCE.getChunkSize();
        this.prev = new float[chunkSize];
        Arrays.fill(this.prev, 0f);

        switch (format) {
            case FLOAT32:
                floatOut = FloatBuffer.allocate(chunkSize);
                break;
            case INT16:
                byteOut = ByteBuffer.allocate(chunkSize * Short.BYTES).order(ByteOrder.nativeOrder());
                shortOut = byteOut.asShortBuffer();
                break;
            case INT8:
                byteOut = ByteBuffer.allocate(chunkSize);
                break;
        }
    }

    public void outputDelta(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void compute() {
        super.compute();

        FloatBuffer mixed = (FloatBuffer) super.read(0);

        switch (format) {
            case FLOAT32:
                if (floatOut == null || floatOut.capacity() != chunkSize) {
                    floatOut = FloatBuffer.allocate(chunkSize);
                }
                for (int i = 0; i < chunkSize; i++) {
                    float curr = mixed.get(i);
                    float out = enabled ? (curr - prev[i]) : curr;
                    floatOut.put(i, out);
                    prev[i] = curr;
                }
                break;

            case INT16:
                if (byteOut == null || shortOut == null || shortOut.capacity() != chunkSize) {
                    byteOut = ByteBuffer.allocate(chunkSize * Short.BYTES).order(ByteOrder.nativeOrder());
                    shortOut = byteOut.asShortBuffer();
                }
                for (int i = 0; i < chunkSize; i++) {
                    float curr = mixed.get(i);
                    float valFloat = enabled ? (curr - prev[i]) : curr;
                    int scaled = Math.round(valFloat * 32767f);
                    if (scaled > Short.MAX_VALUE) scaled = Short.MAX_VALUE;
                    if (scaled < Short.MIN_VALUE) scaled = Short.MIN_VALUE;
                    shortOut.put(i, (short) scaled);
                    prev[i] = curr;
                }
                break;

            case INT8:
                if (byteOut == null || byteOut.capacity() != chunkSize) {
                    byteOut = ByteBuffer.allocate(chunkSize);
                }
                for (int i = 0; i < chunkSize; i++) {
                    float curr = mixed.get(i);
                    float valFloat = enabled ? (curr - prev[i]) : curr;
                    int scaled = Math.round(valFloat * 127f);
                    if (scaled > Byte.MAX_VALUE) scaled = Byte.MAX_VALUE;
                    if (scaled < Byte.MIN_VALUE) scaled = Byte.MIN_VALUE;
                    byteOut.put(i, (byte) scaled);
                    prev[i] = curr;
                }
                break;
        }
    }

    @Override
    public Buffer read(int _id) {
        switch (format) {
            case FLOAT32:
                return floatOut.asReadOnlyBuffer();
            case INT16:
                return shortOut.asReadOnlyBuffer();
            case INT8:
                return byteOut.asReadOnlyBuffer();
            default:
                return null;
        }
    }
}
