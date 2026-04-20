package wobble.module;

import java.nio.FloatBuffer;

import wobble.core.Port;
import wobble.core.Wobble;
import wobble.core.Module;

public class Envelope extends Module {
    private enum Stage {
        ATTACK, DECAY, SUSTAIN, RELEASE
    };

    private Port gate;

    private Stage stage = Stage.RELEASE;
    private int samplesInStage;

    private float gateThreshold = 0.5f;

    private float attackTime;
    private float decayTime;
    private float sustainLevel;
    private float releaseTime;

    private float releaseStartLevel = 0.0f;
    private float attackStartLevel = 0.0f;

    FloatBuffer sampleBuffer;

    public Envelope(float A, float D, float S, float R, Port gate) {
        this.attackTime = A;
        this.decayTime = D;
        this.sustainLevel = S;
        this.releaseTime = R;
        this.gate = gate;

        this.sampleBuffer = FloatBuffer.allocate(Wobble.INSTANCE.getChunkSize());
        this.samplesInStage = Wobble.INSTANCE.timeToSamples(releaseTime);
    }

    private float getCurrentStageTime() {
        switch (stage) {
            case ATTACK:
                return attackTime;
            case DECAY:
                return decayTime;
            case SUSTAIN:
                return 0;
            case RELEASE:
                return releaseTime;
            default:
                throw new IllegalStateException("Invalid stage: " + stage);
        }
    }

    private float evaluateStage() {
    final float stageProgress = (float) samplesInStage / Wobble.INSTANCE.timeToSamples(getCurrentStageTime());
    final float phase = Math.clamp(stageProgress, 0, 1);

    switch (stage) {
        case ATTACK: 
            return attackStartLevel + (1.0f - attackStartLevel) * phase;
            
        case DECAY: 
            return 1.0f - (1.0f - sustainLevel) * phase;
            
        case SUSTAIN: 
            return sustainLevel;
            
        case RELEASE: 
            return releaseStartLevel * (1.0f - phase);
            
        default: 
            throw new IllegalStateException("Invalid stage: " + stage);
    }
}

    public void setAttackTime(float attackTime) {
        this.attackTime = attackTime;
    }

    public void setDecayTime(float decayTime) {
        this.decayTime = decayTime;
    }

    public void setSustainLevel(float sustainLevel) {
        this.sustainLevel = sustainLevel;
    }

    public void setReleaseTime(float releaseTime) {
        this.releaseTime = releaseTime;
    }

    public void setGateThreshold(float gateThreshold) {
        this.gateThreshold = gateThreshold;
    }

    public void gate(Port cv) {
        this.gate = cv;
    }

    @Override
    public void compute() {
        final FloatBuffer gateValues = (FloatBuffer)gate.read();

        for (int i = 0; i < Wobble.INSTANCE.getChunkSize(); i++) {
            final boolean gateOn = gateValues.get(i) > gateThreshold;
            
            if (gateOn && stage == Stage.RELEASE) {
                attackStartLevel = evaluateStage(); 
                stage = Stage.ATTACK;
                samplesInStage = 0;
            }

            if (!gateOn && stage != Stage.RELEASE) {
                releaseStartLevel = evaluateStage();
                stage = Stage.RELEASE;
                samplesInStage = 0;
            }

            int stageDurationSamples = 0;
            switch (stage) {
                case ATTACK: {
                    stageDurationSamples = Wobble.INSTANCE.timeToSamples(attackTime);
                    if (samplesInStage >= stageDurationSamples) {
                        stage = Stage.DECAY;
                        samplesInStage = 0;
                    }
                    break;
                }
                case DECAY: {
                    stageDurationSamples = Wobble.INSTANCE.timeToSamples(decayTime);
                    if (samplesInStage >= stageDurationSamples) {
                        stage = Stage.SUSTAIN;
                        samplesInStage = 0;
                    }
                    break;
                }
                case SUSTAIN: {
                    break;
                }
                case RELEASE: {
                    stageDurationSamples = Wobble.INSTANCE.timeToSamples(releaseTime);
                    if (samplesInStage >= stageDurationSamples) {
                        break;
                    }
                    break;
                }
            }

            sampleBuffer.put(i, evaluateStage());
            samplesInStage++;
        }
    }

    @Override
    public FloatBuffer read(int _id) {
        return sampleBuffer.asReadOnlyBuffer();
    }
}
