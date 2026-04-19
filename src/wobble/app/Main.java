package wobble.app;

import wobble.core.Wobble;
import wobble.core.Port;
import wobble.module.*;

import java.nio.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import javax.sound.sampled.*;

class CallbackAudioEngine {

    private SourceDataLine line;
    private Thread audioThread;
    private volatile boolean running;

    public void start(float sampleRate, int channels, Port input)
            throws LineUnavailableException {

        int framesPerCallback = Wobble.INSTANCE.getChunkSize();
        int bytesPerFrame = 2 * channels;

        AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

        line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format, framesPerCallback * bytesPerFrame * 4);
        line.start();

        byte[] pcm = new byte[framesPerCallback * bytesPerFrame];
        ShortBuffer shortView = ByteBuffer.wrap(pcm)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer();

        running = true;
        audioThread = new Thread(() -> {
            while (running) {
                Wobble.INSTANCE.tick();
                ShortBuffer buffer = (ShortBuffer) input.read();
                buffer.rewind();
                shortView.clear();
                shortView.put(buffer);
                line.write(pcm, 0, pcm.length);
            }
        }, "audio-callback-thread");

        audioThread.setPriority(Thread.MAX_PRIORITY);
        audioThread.setDaemon(true);
        audioThread.start();
    }

    public void stop() {
        running = false;
        if (line != null) { line.drain(); line.stop(); line.close(); }
    }
}

public class Main {

    public static void main(String[] args) {
        Wobble.INSTANCE.setSampleRate(44100);
        Wobble.INSTANCE.setChunkSize(512);

        Controller controller = new Controller();

        Oscillator osc = new Oscillator(Oscillator.Shape.SQUARE, 440);

        Envelope env = new Envelope(
            1f, 
            0.10f,  
            0.70f,  
            0.30f,  
            controller.output(Controller.GATE)
        );

        Amplifier vca = new Amplifier(osc.output());
        vca.control(env.output());

        DifferentialEncoder encoder = new DifferentialEncoder(DifferentialEncoder.Format.INT16);
        encoder.add(vca.output(), 1.0f);
        encoder.outputDelta(false);

        CallbackAudioEngine engine = new CallbackAudioEngine();

        try {
            engine.start(44100f, 1, encoder.output());
        } catch (LineUnavailableException e) {
            e.printStackTrace();
            return;
        }

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Piano Key");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(120, 320);
            frame.setResizable(false);

            JButton key = new JButton("A4");
            key.setFont(new Font("SansSerif", Font.BOLD, 18));
            key.setBackground(Color.WHITE);
            key.setFocusPainted(false);

            key.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e)  { controller.gateOn();  key.setBackground(Color.LIGHT_GRAY); }
                @Override public void mouseReleased(MouseEvent e) { controller.gateOff(); key.setBackground(Color.WHITE); }
            });

            frame.add(key);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
