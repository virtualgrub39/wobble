package wobble.module;

import java.nio.FloatBuffer;

import wobble.core.Port;
import wobble.core.Module;
import wobble.core.Wobble;

public class Oscillator extends Module {
    private float frequency;
    private float duty = 0.5f;
    private float phase = 0.0f;

    private FloatBuffer sampleBuffer;

    private Port cv;
    private float cvOctaves;

    public enum Shape {
        SINE, SQUARE, TRIANGLE, NOISE
    }

    private Shape shape;

    private void wrapPhase() {
        while (phase >= 1.0f)
            phase -= 1.0f;
        while (phase < 0.0f)
            phase += 1.0f;
    }

    private void incrementPhase(float cv) {
        float modFrequency = frequency * (float) Math.pow(2, cv * cvOctaves); // 1V/oct
        phase += modFrequency / Wobble.INSTANCE.getSampleRate();
        wrapPhase();
    }

    public Oscillator(Shape shape, float frequency) {
        this.frequency = frequency;
        this.shape = shape;

        sampleBuffer = FloatBuffer.allocate(Wobble.INSTANCE.getChunkSize());
    }

    public void modulate(Port cv, float octaves) {
        this.cv = cv;
        this.cvOctaves = octaves;
    }

    public void stopModulating() {
        this.cv = null;
    }

    public void setFrequency(float frequency) {
        this.frequency = frequency;
    }

    public void setDuty(float duty) {
        this.duty = duty;
    }

    private static float hash(float seed) {
        float x = (float) Math.sin(seed * 12.9898f) * 43758.5453f;
        return x - (float) Math.floor(x);
    }

    public static float computeSample(Shape shape, float duty, float phase) {
        switch (shape) {
            case SINE:
                return (float) Math.sin(phase * 2 * Math.PI);
            case SQUARE:
                return phase < duty ? 1.0f : -1.0f;
            case TRIANGLE: {
                final float a = 2.0f / (1.0f - duty);
                final float eps = Math.ulp(1.0f);

                if (duty <= eps)
                    return -2.0f * phase + 1.0f;
                if (duty >= 1.0f - eps)
                    return 2.0f * phase - 1.0f;
                return (phase < duty) ? 2.0f * phase / duty - 1.0f : -a * phase + a - 1.0f;
            }
            case NOISE: {
                final float octaves = 1.0f + duty * 3.0f;
                float amplitude = 1.0f;
                float frequency = 1.0f;
                float sample = 0.0f;
                float maxAmplitude = 0.0f;

                for (int i = 0; i < (int) octaves; i++) {
                    sample += amplitude * hash(phase * frequency) * 2.0f - amplitude;
                    maxAmplitude += amplitude;

                    frequency *= 2.0f;
                    amplitude *= 0.5f;
                }

                return sample / maxAmplitude;
            }
            default: throw new IllegalStateException("Unexpected shape: " + shape);
        }
    }

    @Override
    public void compute()
    {
        FloatBuffer cvValues = null;
        if (cv != null) cvValues = cv.read();

        for (int i = 0; i < Wobble.INSTANCE.getChunkSize(); i++) {
            float cvValue = (cvValues != null) ? cvValues.get(i) : 0.0f;
            incrementPhase(cvValue);
            sampleBuffer.put(i, computeSample(shape, duty, phase));
        }
    }

    @Override
    public FloatBuffer read(int _id)
    {
        return sampleBuffer.asReadOnlyBuffer();
    }
}
