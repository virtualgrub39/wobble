package wobble.module;

import java.nio.FloatBuffer;
import java.util.LinkedList;
import java.util.List;

import wobble.core.Port;
import wobble.core.Wobble;
import wobble.core.Module;

public class Mixer extends Module {
    private class Input implements Comparable<Input> {
        public Port port;
        public float gain;

        public Input(Port port, float gain) {
            this.port = port;
            this.gain = gain;
        }

        @Override
        public int compareTo(Input o) {
            return this.port == o.port ? 0 : 1;
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
            if (input.port == port) {
                input.gain = gain;
                return;
            }
        }
    }

    public void remove(Port port) {
        inputs.remove(new Input(port, 0));
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
            FloatBuffer inputBuffer = input.port.read();
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
