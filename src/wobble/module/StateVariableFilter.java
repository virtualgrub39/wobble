package wobble.module;

import wobble.core.Wobble;

import java.nio.FloatBuffer;

import wobble.core.Module;
import wobble.core.Port;

public class StateVariableFilter extends Module
{
	private float g, k, a1, a2, a3;  // filter coefficients
    private float m0, m1, m2;        // mix coefficients
    private float ic1eq, ic2eq;      // internal state

    private FloatBuffer sampleBuffer;
    private Port input;

    public StateVariableFilter(Port input)
    {
    	sampleBuffer = FloatBuffer.allocate(Wobble.INSTANCE.getChunkSize());
    	this.input = input;

    	m0 = 0f;
    	m1 = 0f;
    	m2 = 0f;
    }

	private void setCoefficients(float freq, float Q)
    {
        g = (float)Math.tan(Math.PI * freq / Wobble.INSTANCE.getSampleRate());
        k = 1.0f / Q;
        a1 = 1.0f / (1.0f + g * (g + k));
        a2 = g * a1;
        a3 = g * a2;
    }

    public void lowPass(float freq, float Q)
    {
    	setCoefficients(freq, Q);
    	m0 = 0.0f;
        m1 = 0.0f;
        m2 = 1.0f;
    }

    public void highPass(float freq, float Q)
    {
    	setCoefficients(freq, Q);
    	m0 = 1.0f;
        m1 = -k;
        m2 = -1.0f;
    }

    public void bandPass(float freq, float Q)
    {
    	setCoefficients(freq, Q);
        m0 = 0.0f;
        m1 = k;
        m2 = 0.0f;
    }

    public void notch(float freq, float Q) 
    {
        setCoefficients(freq, Q);
        m0 = 1.0f;
        m1 = -k;
        m2 = 0.0f;
    }

    public void allpass(float freq, float Q) 
    {
        setCoefficients(freq, Q);
        m0 = 1.0f;
        m1 = -2.0f * k;
        m2 = 0.0f;
    }

    public void peaking(float freq, float Q) 
    {
        setCoefficients(freq, Q);
        m0 = 1.0f;
        m1 = -k;
        m2 = -2.0f;
    }

    public void bell(float freq, float Q, float dbGain) 
    {
        final float A = (float)Math.pow(10.0, dbGain / 40.0);
        g = (float)Math.tan(Math.PI * freq / Wobble.INSTANCE.getSampleRate());
        k = 1.0f / (Q * A);
        a1 = 1.0f / (1.0f + g * (g + k));
        a2 = g * a1;
        a3 = g * a2;
        m0 = 1.0f;
        m1 = k * (A*A - 1.0f);
        m2 = 0.0f;
    }

    public void lowShelf(float freq, float Q, float dbGain) 
    {
        final float A = (float)Math.pow(10.0, dbGain / 40.0);
        g = (float)Math.tan(Math.PI * freq / Wobble.INSTANCE.getSampleRate()) / (float)Math.sqrt(A);
        k = 1.0f / Q;
        a1 = 1.0f / (1.0f + g * (g + k));
        a2 = g * a1;
        a3 = g * a2;
        m0 = 1.0f;
        m1 = k * (A - 1.0f);
        m2 = (A*A - 1.0f);
    }

    public void highShelf(float freq, float Q, float dbGain) 
    {
        final float A = (float)Math.pow(10.0, dbGain / 40.0);
        g = (float)Math.tan(Math.PI * freq / Wobble.INSTANCE.getSampleRate()) * (float)Math.sqrt(A);
        k = 1.0f / Q;
        a1 = 1.0f / (1.0f + g * (g + k));
        a2 = g * a1;
        a3 = g * a2;
        m0 = A * A;
        m1 = k * (1.0f - A) * A;
        m2 = (1.0f - A*A);
    }

    public void reset()
    {
        ic1eq = 0.0f;
        ic2eq = 0.0f;
    }

    private float processSample(float v0)
    {
        float v3 = v0 - ic2eq;
        float v1 = a1 * ic1eq + a2 * v3;
        float v2 = ic2eq + a2 * ic1eq + a3 * v3;
        ic1eq = 2.0f * v1 - ic1eq;
        ic2eq = 2.0f * v2 - ic2eq;
        return m0 * v0 + m1 * v1 + m2 * v2;
    }

    @Override
    public void compute()
    {
    	FloatBuffer input_values = input.read();

    	for (int i = 0; i < Wobble.INSTANCE.getChunkSize(); ++i)
    	{
    		sampleBuffer.put(i, processSample(input_values.get(i)));
    	}
    }

    @Override
	public FloatBuffer read(int _id)
	{
		return sampleBuffer.asReadOnlyBuffer();
	}
}
