package wobble;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import wobble.core.*;
import wobble.module.*;

public class WobbleTest {
    static void moduleWrite(float timeMs, Port port, String path) throws IOException {
        Wobble wobble = Wobble.INSTANCE;

        int want = wobble.timeToSamples(timeMs / 1000f);
        int got = 0;

        List<Float> result = new ArrayList<>();

        while (got < want) {
            wobble.tick();

            FloatBuffer block = port.read();
            block.rewind();

            while (block.hasRemaining())
                result.add(block.get());

            got += block.capacity();
        }

        ByteBuffer byteBuffer = ByteBuffer.allocate(want * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < want; i++)
            byteBuffer.putFloat(result.get(i));

        try (FileOutputStream fos = new FileOutputStream(path)) {
            fos.write(byteBuffer.array());
        }
    }

    @Test
    public void testOscillator() throws IOException {
        Oscillator hf = new Oscillator(Oscillator.Shape.SINE, 440);
        Oscillator mod = new Oscillator(Oscillator.Shape.TRIANGLE, 2);

        mod.setDuty(0.1f);
        hf.modulate(mod.output(), 4);

        moduleWrite(1000, hf.output(), "oscillator.raw");
    }

    @Test
    public void testAmplifier() throws IOException {
        Oscillator osc = new Oscillator(Oscillator.Shape.SINE, 440);
        Oscillator mod = new Oscillator(Oscillator.Shape.TRIANGLE, 2);
        Amplifier amp = new Amplifier(osc.output());

        osc.modulate(mod.output(), 2);
        amp.setStaticGain(0.7f);
        amp.control(mod.output());

        moduleWrite(1000, amp.output(), "amplifier.raw");
    }

    @Test
    public void testMixer() throws IOException {
        Oscillator base = new Oscillator(Oscillator.Shape.SINE, 440);
        Oscillator noise = new Oscillator(Oscillator.Shape.NOISE, 1000);
        Oscillator mod = new Oscillator(Oscillator.Shape.TRIANGLE, 2);

        Mixer mixer = new Mixer();

        mixer.add(base.output(), 0.7f);
        mixer.add(noise.output(), 0.3f);

        moduleWrite(1000, mixer.output(), "mixer.noise.raw");

        mixer.remove(noise.output());
        mixer.setGain(base.output(), 0.5f);
        mixer.add(mod.output(), 0.5f);

        moduleWrite(1000, mixer.output(), "mixer.mod.raw");
    }

    @Test
    public void testSVF() throws IOException {
        Oscillator osc = new Oscillator(Oscillator.Shape.SINE, 440);
        Oscillator noise = new Oscillator(Oscillator.Shape.NOISE, 1000);

        Mixer mixer = new Mixer();
        mixer.add(osc.output(), 0.7f);
        mixer.add(noise.output(), 0.3f);

        StateVariableFilter svf = new StateVariableFilter(mixer.output());
        svf.lowPass(1000, 0.707f);

        moduleWrite(1000, svf.output(), "svf.lowpass.raw");
    }

    @Test
    public void testEnvelope() throws IOException {
        Oscillator base = new Oscillator(Oscillator.Shape.SINE, 440);
        Oscillator gate = new Oscillator(Oscillator.Shape.SQUARE, 4);
        Envelope env = new Envelope(0.05f, 0.1f, 0.7f, 0.1f, gate.output());
        Amplifier vca = new Amplifier(base.output());

        vca.control(env.output());

        moduleWrite(1000, vca.output(), "envelope.raw");
    }

    private float noteToVolt(float note) {
        return (float) Math.pow(2, (note - 69) / 12.0f);
    }

    @Test
    public void testInstrument() throws IOException {
        final int chunkSize = 1024;
        Wobble.INSTANCE.setChunkSize(chunkSize);

        Controller controller = new Controller();

        Oscillator osc1 = new Oscillator(Oscillator.Shape.TRIANGLE, 125);
        osc1.setDuty(0);
        osc1.modulate(controller.output(Controller.CV), 4);

        Oscillator osc2 = new Oscillator(Oscillator.Shape.TRIANGLE, 125);
        osc2.setDuty(0);
        osc2.setDetuneCents(-24);
        osc2.modulate(controller.output(Controller.CV), 4);

        Mixer vcfIn = new Mixer();
        vcfIn.add(osc1.output(), 0.5f);
        vcfIn.add(osc2.output(), 0.5f);

        StateVariableFilter filter = new StateVariableFilter(vcfIn.output());
        filter.lowPass(1000, 0.707f);
        filter.modulate(controller.output(Controller.CV), 3, 0);

        Envelope env = new Envelope(0.02f, 0.01f, 1.0f, 0.02f, controller.output(Controller.GATE));

        Amplifier vca = new Amplifier(filter.output());
        vca.control(env.output());

        List<Float> samples = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            float got = 0;
            float want = Wobble.INSTANCE.timeToSamples(0.5f);
            
            controller.gateOn(noteToVolt(60 + i * 2));

            while (got < want) {
                Wobble.INSTANCE.tick();

                FloatBuffer block = vca.output().read();
                block.rewind();

                if (block.capacity() > want - got)
                    block.limit((int)(want - got));

                while (block.hasRemaining())
                    samples.add(block.get());

                got += block.capacity();
            }

            controller.gateOff();
            got = 0;
            want = Wobble.INSTANCE.timeToSamples(0.1f);

            while (got < want) {
                Wobble.INSTANCE.tick();

                FloatBuffer block = vca.output().read();
                block.rewind();

                if (block.capacity() > want - got)
                    block.limit((int)(want - got));

                while (block.hasRemaining())
                    samples.add(block.get());

                got += block.capacity();
            }
        }

        ByteBuffer byteBuffer = ByteBuffer.allocate(samples.size() * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < samples.size(); i++)
            byteBuffer.putFloat(samples.get(i));

        try (FileOutputStream fos = new FileOutputStream("instrument.raw")) {
            fos.write(byteBuffer.array());
        }
    }
}