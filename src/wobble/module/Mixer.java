package wobble.module;

import java.nio.FloatBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import wobble.core.Port;
import wobble.core.Wobble;
import wobble.core.Module;

public class Mixer extends Module {
    private class Input {
        public Port port;
        public float gain;

        public Input(Port port, float gain) {
            this.port = port;
            this.gain = gain;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof Input))
                return false;
            Input other = (Input) o;
            return Objects.equals(this.port, other.port);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(port);
        }
    }

    private List<Input> inputs = new LinkedList<Input>();

    private FloatBuffer sampleBuffer;
    private float[] zeros;

    public void add(Port port, float gain) {
        inputs.add(new Input(port, gain));
    }

    public void setGain(Port port, float gain) {
        for (Input input : inputs) {
            if (Objects.equals(input.port, port)) {
                input.gain = gain;
                return;
            }
        }
    }

    public void remove(Port port) {
        inputs.removeIf(input -> Objects.equals(input.port, port));
    }

    public Mixer() {
        sampleBuffer = FloatBuffer.allocate(Wobble.INSTANCE.getChunkSize());
        zeros = new float[Wobble.INSTANCE.getChunkSize()];
    }

    public void compute() {
        sampleBuffer.clear();
        sampleBuffer.put(zeros);
        sampleBuffer.flip();

        for (Input input : inputs) {
            FloatBuffer inputBuffer = (FloatBuffer)input.port.read();
            for (int i = 0; i < sampleBuffer.capacity(); i++) {
                sampleBuffer.put(i, sampleBuffer.get(i) + inputBuffer.get(i) * input.gain);
            }
        }
    }

    @Override
    public FloatBuffer read(int _id) {
        return sampleBuffer.asReadOnlyBuffer();
    }
}
