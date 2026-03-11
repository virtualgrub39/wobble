package wobble.module;

import java.nio.FloatBuffer;

import wobble.core.Port;
import wobble.core.Module;

public class Amplifier extends Module {
    private float staticGain = 1.0f;
    private Port cv = null;

    private Port input;

    private FloatBuffer sampleBuffer;

    public Amplifier(Port input) {
        this.input = input;
        sampleBuffer = FloatBuffer.allocate(input.read().capacity());
    }

    public void setStaticGain(float gain) {
        this.staticGain = gain;
    }

    public void control(Port cv) {
        this.cv = cv;
    }

    public void stopControl() {
        this.cv = null;
    }

    @Override
    public void compute() {
        FloatBuffer inputValues = (FloatBuffer)input.read();
        FloatBuffer cvValues = cv != null ? (FloatBuffer)cv.read() : null;

        for (int i = 0; i < inputValues.capacity(); i++) {
            float outputValue =inputValues.get(i) * staticGain;
            if (cvValues != null) {
                outputValue *= cvValues.get(i);
            }

            outputValue =  Math.clamp(outputValue, -1.0f, 1.0f);
            
            sampleBuffer.put(i, outputValue);
        }
    }

    @Override
    public FloatBuffer read(int _id) {
        return sampleBuffer.asReadOnlyBuffer();
    }

}
