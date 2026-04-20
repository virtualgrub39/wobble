package wobble.app;

import wobble.core.Port;
import wobble.core.Wobble;
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

class PianoPanel extends JPanel {
    private static final float UI_SCALE = 2.0f;

    private static int sx(float v) {
        return Math.round(v * UI_SCALE);
    }

    private static final int WK_W   = sx(60);
    private static final int WK_H   = sx(200);
    private static final int BK_W   = sx(36);
    private static final int BK_H   = sx(128);
    private static final int TOTAL_W = WK_W * 7;

    // Left-edge x of each black key: C#  D#  F#  G#  A#
    private static final int[] BK_X = {
        sx(38), sx(98), sx(218), sx(278), sx(338)
    };

    // White keys: C D E F G A B
    private static final float[] WK_CV = {
        0f, 2f / 12f, 4f / 12f, 5f / 12f, 7f / 12f, 9f / 12f, 11f / 12f
    };

    // Black keys: C# D# F# G# A#
    private static final float[] BK_CV = {
        1f / 12f, 3f / 12f, 6f / 12f, 8f / 12f, 10f / 12f
    };

    private static final String[] WK_LABEL = { "C", "D", "E", "F", "G", "A", "B" };

    // Keyboard mapping
    private static final int[] WHITE_KEYS = {
        KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_D, KeyEvent.VK_F,
        KeyEvent.VK_G, KeyEvent.VK_H, KeyEvent.VK_J
    };

    private static final int[] BLACK_KEYS = {
        KeyEvent.VK_W, KeyEvent.VK_E, KeyEvent.VK_T, KeyEvent.VK_Y, KeyEvent.VK_U
    };

    private int pressedWhite = -1;
    private int pressedBlack = -1;
    private int activeKeyCode = KeyEvent.VK_UNDEFINED;

    private final Controller controller;

    private static final Color COL_WHITE_KEY   = new Color(255, 255, 252);
    private static final Color COL_WHITE_PRESS = new Color(190, 215, 255);
    private static final Color COL_KEY_BORDER  = new Color(50,  50,  50);
    private static final Color COL_BK_TOP      = new Color(38,  38,  38);
    private static final Color COL_BK_BTM      = new Color(18,  18,  18);
    private static final Color COL_BK_P_TOP    = new Color(70,  85, 115);
    private static final Color COL_BK_P_BTM    = new Color(90, 110, 160);
    private static final Color COL_LABEL       = new Color(140, 140, 140);
    private static final Color COL_SURROUND    = new Color(28,  28,  28);

    public PianoPanel(Controller controller) {
        this.controller = controller;

        setPreferredSize(new Dimension(TOTAL_W + 2, WK_H + 20));
        setBackground(COL_SURROUND);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);

        installKeyBindings();

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                int bk = hitBlack(e.getX() - 1, e.getY() - 10);
                if (bk >= 0) {
                    playBlack(bk, KeyEvent.VK_UNDEFINED);
                } else {
                    int wk = hitWhite(e.getX() - 1, e.getY() - 10);
                    if (wk >= 0) {
                        playWhite(wk, KeyEvent.VK_UNDEFINED);
                    }
                }
            }

            @Override public void mouseReleased(MouseEvent e) {
                stopCurrent();
            }
        };
        addMouseListener(ma);
    }

    private void installKeyBindings() {
        InputMap im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getActionMap();

        for (int i = 0; i < WHITE_KEYS.length; i++) {
            final int idx = i;
            final int key = WHITE_KEYS[i];

            im.put(KeyStroke.getKeyStroke(key, 0, false), "white-" + idx + "-press");
            im.put(KeyStroke.getKeyStroke(key, 0, true),  "white-" + idx + "-release");

            am.put("white-" + idx + "-press", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) {
                    playWhite(idx, key);
                }
            });

            am.put("white-" + idx + "-release", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) {
                    stopIfActive(key);
                }
            });
        }

        for (int i = 0; i < BLACK_KEYS.length; i++) {
            final int idx = i;
            final int key = BLACK_KEYS[i];

            im.put(KeyStroke.getKeyStroke(key, 0, false), "black-" + idx + "-press");
            im.put(KeyStroke.getKeyStroke(key, 0, true),  "black-" + idx + "-release");

            am.put("black-" + idx + "-press", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) {
                    playBlack(idx, key);
                }
            });

            am.put("black-" + idx + "-release", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) {
                    stopIfActive(key);
                }
            });
        }
    }

    private void playWhite(int idx, int keyCode) {
        activeKeyCode = keyCode;
        pressedWhite = idx;
        pressedBlack = -1;
        controller.gateOn(WK_CV[idx]);
        repaint();
    }

    private void playBlack(int idx, int keyCode) {
        activeKeyCode = keyCode;
        pressedBlack = idx;
        pressedWhite = -1;
        controller.gateOn(BK_CV[idx]);
        repaint();
    }

    private void stopIfActive(int keyCode) {
        if (activeKeyCode == keyCode) {
            stopCurrent();
        }
    }

    private void stopCurrent() {
        activeKeyCode = KeyEvent.VK_UNDEFINED;
        pressedWhite = -1;
        pressedBlack = -1;
        controller.gateOff();
        repaint();
    }

    private int hitBlack(int x, int y) {
        if (y < 0 || y >= BK_H) return -1;
        for (int i = 0; i < BK_X.length; i++) {
            if (x >= BK_X[i] && x < BK_X[i] + BK_W) return i;
        }
        return -1;
    }

    private int hitWhite(int x, int y) {
        if (y < 0 || y >= WK_H) return -1;
        int idx = x / WK_W;
        return (idx >= 0 && idx < 7) ? idx : -1;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g2.translate(1, 10);

        for (int i = 0; i < 7; i++) {
            int x = i * WK_W;
            boolean pressed = (pressedWhite == i);

            g2.setColor(new Color(170, 170, 165));
            g2.fillRoundRect(x + 1, 4, WK_W - 2, WK_H - 4, 6, 6);

            Color fTop = pressed ? COL_WHITE_PRESS : COL_WHITE_KEY;
            Color fBtm = pressed ? COL_WHITE_PRESS.darker() : new Color(230, 230, 223);
            g2.setPaint(new GradientPaint(x, 0, fTop, x, WK_H - 6, fBtm));
            g2.fillRoundRect(x + 1, 0, WK_W - 2, WK_H - 6, 6, 6);

            g2.setColor(COL_KEY_BORDER);
            g2.setStroke(new BasicStroke(1.0f));
            g2.drawRoundRect(x + 1, 0, WK_W - 2, WK_H - 6, 6, 6);

            g2.setFont(new Font("SansSerif", Font.PLAIN, 11));
            g2.setColor(COL_LABEL);
            FontMetrics fm = g2.getFontMetrics();
            int lx = x + (WK_W - fm.stringWidth(WK_LABEL[i])) / 2;
            g2.drawString(WK_LABEL[i], lx, WK_H - 14);
        }

        for (int i = 0; i < BK_X.length; i++) {
            boolean pressed = (pressedBlack == i);
            int x = BK_X[i];

            g2.setColor(new Color(10, 10, 10));
            g2.fillRoundRect(x - 1, 1, BK_W + 2, BK_H + 2, 5, 5);

            Color fTop = pressed ? COL_BK_P_TOP : COL_BK_TOP;
            Color fBtm = pressed ? COL_BK_P_BTM : COL_BK_BTM;
            g2.setPaint(new GradientPaint(x, 0, fTop, x + BK_W, BK_H, fBtm));
            g2.fillRoundRect(x, 0, BK_W, BK_H, 5, 5);

            g2.setColor(new Color(255, 255, 255, pressed ? 8 : 22));
            g2.fillRoundRect(x + 4, 4, BK_W - 8, 16, 3, 3);

            g2.setColor(new Color(8, 8, 8));
            g2.setStroke(new BasicStroke(1.0f));
            g2.drawRoundRect(x, 0, BK_W, BK_H, 5, 5);
        }

        g2.translate(-1, -10);
    }
}

public class Main {

    public static void main(String[] args) {
        Wobble.INSTANCE.setSampleRate(44100);
        Wobble.INSTANCE.setChunkSize(256);

        Controller controller = new Controller();

        Oscillator osc = new Oscillator(Oscillator.Shape.TRIANGLE, 261.63f);
        osc.setDuty(.3f);
        osc.modulate(controller.output(Controller.CV), 1.0f); // 1 octave modulation

        // StateVariableFilter filter = new StateVariableFilter(osc.output());
        // filter.lowPass(2*261.63f, 1e3f);
        // filter.modulate(controller.output(Controller.CV), 1.0f, 0f); // 1 octave frequency, don't modulate Q

        Envelope env = new Envelope(
            0.15f,  // attack
            0.04f,  // decay
            0.70f,  // sustain
            0.30f,  // release
            controller.output(Controller.GATE)
        );

        Amplifier vca = new Amplifier(osc.output());
        vca.control(env.output());

        DifferentialEncoder encoder = new DifferentialEncoder(DifferentialEncoder.Format.INT16);
        encoder.add(vca.output(), 1.0f);
        encoder.outputDelta(false);

        CallbackAudioEngine engine = new CallbackAudioEngine();
        try {
            engine.start(Wobble.INSTANCE.getSampleRate(), 1, encoder.output());
        } catch (LineUnavailableException e) {
            e.printStackTrace();
            return;
        }

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Wobble Piano  –  C4 octave");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(false);

            PianoPanel piano = new PianoPanel(controller);
            frame.add(piano);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}