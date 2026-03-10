package wobble.module;

import java.nio.FloatBuffer;

import wobble.core.Wobble;
import wobble.core.Module;

public class Controller extends Module {
    private FloatBuffer gateBuffer;
    private FloatBuffer cvBuffer;

    private boolean gateOn;
    private float cvValue;

    // public enum Output {
    //     GATE,
    //     CV,
    // }

    public final static int GATE = 0;
    public final static int CV = 1;

    public Controller() {
        this.gateBuffer = FloatBuffer.allocate(Wobble.INSTANCE.getChunkSize());
        this.cvBuffer = FloatBuffer.allocate(Wobble.INSTANCE.getChunkSize());
    }

    public void gateOn() {
        this.gateOn = true;
    }

    public void gateOn(float cvValue) {
        this.gateOn = true;
        this.cvValue = cvValue;
    }

    public void gateOff() {
        this.gateOn = false;
    }

    @Override
    public void compute() {
        for (int i = 0; i < gateBuffer.capacity(); i++) {
            gateBuffer.put(i, gateOn ? 1.0f : 0.0f);
            cvBuffer.put(i, cvValue);
        }
    }

    @Override
    public FloatBuffer read(int id) {
        switch (id) {
            case GATE:
                return gateBuffer.asReadOnlyBuffer();
            case CV:
                return cvBuffer.asReadOnlyBuffer();
            default:
                throw new IllegalArgumentException("Invalid output id");
        }
    }
}
