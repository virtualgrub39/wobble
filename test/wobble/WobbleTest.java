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
        Mixer mixer = new Mixer();

        mixer.add(base.output(), 0.7f);
        mixer.add(noise.output(), 0.3f);

        moduleWrite(1000, mixer.output(), "mixer.raw");
    }
}