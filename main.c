#include <float.h>
#include <math.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdbool.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define UNREACHABLE(where) do { \
	fprintf(stderr, "Entered unreachable state ("where")\n"); \
	abort(); \
} while(0)

/* --- Config --- */

static const size_t sample_rate_hz = 44100;

static inline size_t
ms_to_samples(float ms)
{
	return ms / 1000 * sample_rate_hz;
}

/* --- Shared --- */

typedef struct module Module;
typedef int(*pull_fn)(Module* m, float* smpl, unsigned n);

typedef enum
{
	MODULE_OSCILLATOR,
	MODULE_MIXER,
	MODULE_BIQUAD,
	MODULE_AMPLIFIER,
	MODULE_ENVELOPE,

	MODULE_COUNT
} ModuleKind;

static pull_fn pull_cb_table[MODULE_COUNT];
#define MODULE_PULL(mod, smpl, n) pull_cb_table[(mod)->kind]((mod),(smpl),(n))

/* --- Oscillator --- */

typedef enum
{
	SHAPE_SINE,
	SHAPE_RECT,
	SHAPE_SAW,
} OscillatorShape;

typedef struct
{
	float freq;
	float phase;
	float duty;
	OscillatorShape shape;
	Module* fm;
	float mod_ratio;
} OscillatorModule;

Module *oscillator(OscillatorShape shape, float freq);
void oscillator_set_freq(Module *mod, float freq);
void oscillator_set_duty(Module *mod, float duty);
void oscillator_connect(Module *mod, Module* fm);
void oscillator_set_modulation(Module *mod, float ratio);
int oscillator_cb(Module* mod, float* smpl, unsigned n);

/* --- Mixer --- */

typedef struct
{
	Module* in;
	float gain;
	bool set;
} MixerInput;

typedef struct 
{
	MixerInput* inputs;
	size_t input_count;
} MixerModule;

Module *mixer(size_t ninputs);
void mixer_add(Module* mod, unsigned id, Module *in, float gain);
void mixer_set(Module *mod, unsigned id, float gain);
int mixer_cb(Module* mod, float* smpl, unsigned n);

/* --- Biquad --- */

typedef enum
{
	BQ_LOWPASS,
	BQ_HIGHPASS,
	BQ_BANDPASS,
	BQ_NOTCH,
	BQ_PEAK,
	BQ_LOWSHELF,
	BQ_HIGHSHELF,
} BiquadFilterKind;

typedef struct
{
	float a[3];
	float b[3];
	float x[2];
	float y[2];
	Module* in;
} BiquadModule;

Module *biquad(BiquadFilterKind kind, float freq, float Q, float gain_db, Module *in);
int biquad_cb(Module *mod, float *smpl, unsigned n);

/* --- Amplifier --- */

typedef struct
{
	float gain;
	Module *in;
	Module *cv;
} AmplifierModule;

Module *amplifier(float gain, Module *in);
void amplifier_cv(Module *mod, Module *cv);
int amplifier_cb(Module *mod, float *smpl, unsigned n);

/* Envelope */

typedef enum
{
	ENV_IDLE,
	ENV_ATTACK,
	ENV_DECAY,
	ENV_SUSTAIN,
	ENV_RELEASE
} EnvelopeState;

typedef struct
{
	EnvelopeState state;
	float phase;

	bool gate_override;
	Module *gate;

	bool auto_trigger;

	float At_secs;
	float Dt_secs;
	float S_level;
	float Rt_secs;
} EnvelopeModule;

Module *envelope(float A, float D, float S, float R);

void envelope_set_attack (Module *mod, float A);
void envelope_set_decay (Module *mod, float D);
void envelope_set_sustain (Module *mod, float S);
void envelope_set_release (Module *mod, float R);

void envelope_toggle_gate (Module *mod); // manual gate control
void envelope_toggle_auto(Module *mod);  // toggle automatic re-trigger after release
void envelope_gate(Module *mod, Module *gate); // module gate control (> 0.5 => ON)

int envelope_cb (Module *mod, float *smpl, unsigned n);

/* --- Shared --- */

struct module
{
	ModuleKind kind;
	union
	{
		OscillatorModule oscillator;
		MixerModule mixer;
		BiquadModule biquad;
		AmplifierModule amplifier;
		EnvelopeModule envelope;
	} as;
};

void register_modules(void)
{
	pull_cb_table[MODULE_OSCILLATOR] = oscillator_cb;
	pull_cb_table[MODULE_MIXER] = mixer_cb;
	pull_cb_table[MODULE_BIQUAD] = biquad_cb;
	pull_cb_table[MODULE_AMPLIFIER] = amplifier_cb;
	pull_cb_table[MODULE_ENVELOPE] = envelope_cb;
}

/* This describes:
	sine -(+)-> rect -> (+) -> amp -> LP filter -> OUT
	               saw --^      ^
	               envelope ---/
	Or something like that
*/
int
main(void)
{
	register_modules();

	Module *main = oscillator(SHAPE_SAW, 170);
	oscillator_set_duty(main, 0);

    Module *mod = oscillator(SHAPE_SINE, 300);
	
	oscillator_connect(main, mod);
	oscillator_set_modulation(main, 0.1);

	Module *square = oscillator(SHAPE_RECT, 150);

	Module *sum = mixer(2);
	mixer_add(sum, 0, main, 0.4);
	mixer_add(sum, 1, square, 0.4);

	Module *boop = envelope(0.03, 0.01, 0.8, 0.1);
	
	Module *gate = oscillator(SHAPE_RECT, 20);
	oscillator_set_duty(gate, 0.2);
	envelope_gate(boop, gate);

	Module *dump = biquad(BQ_LOWPASS, 200, 0.7071f, 6, sum);

	Module *out = amplifier(1.3, dump);
	amplifier_cv(out, boop);

	const int want = ms_to_samples(1000);
    float *buffer = calloc(want, sizeof *buffer);

    int chunk_size = want/128;
    int got = 0;

    while (got < want) {
        #define MIN(a,b) ((a) > (b)) ? (b) : (a)
        int got_now = MODULE_PULL(out, buffer + got, MIN(want - got, chunk_size));
        if (got_now < 0) return 69;
        got += got_now;
    }

	FILE *ofile = fopen ("out.raw", "wb"); // open with audacity or whatever
    fwrite (buffer, sizeof *buffer, got, ofile);
    fclose (ofile);
	return 0;
}
/* --- Implementation --- */

Module *oscillator(OscillatorShape shape, float freq)
{
	Module *mod = malloc(sizeof *mod);
	if (!mod) return NULL;

	mod->kind = MODULE_OSCILLATOR;
	mod->as.oscillator.shape = shape;
	mod->as.oscillator.freq = freq;
	mod->as.oscillator.duty = 0.5f;
	mod->as.oscillator.phase = 0.0f;
	mod->as.oscillator.fm = NULL;
	mod->as.oscillator.mod_ratio = 0.0f;

	return mod;
}

// static float generate_sample(OscillatorModule *om)
static float generate_sample(OscillatorShape shape, float duty, float phase)
{    
	const bool pgtd = phase < duty;
	const float a = 2/(1-duty); 

	switch (shape)
	{
	case SHAPE_SINE: return sinf(phase * 2.0f * M_PI); 
	case SHAPE_RECT: return pgtd ? 1.0 : -1.0; 
	case SHAPE_SAW:
	{
		if (duty <= FLT_EPSILON) return -2 * phase + 1.0f;
		if (duty -1.0 <= FLT_EPSILON) return 2 * phase - 1.0f;
		return pgtd ? 2*phase/duty - 1 : -a*phase + a - 1;
	}
	default: UNREACHABLE("shape");
	}
}

static void increment_phase(OscillatorModule *om)
{
    om->phase += om->freq / sample_rate_hz;
    
    while (om->phase >= 1.0f) om->phase -= 1.0f;
    while (om->phase < 0.0f)  om->phase += 1.0f;
}

static inline float wrap_phase(float p)
{
    p -= floorf(p);
    return p;
}


int oscillator_cb(Module* mod, float* smpl, unsigned n)
{
	OscillatorModule *om = &mod->as.oscillator;

    float *fm_buf = NULL;
    if (om->fm) {
        fm_buf = malloc(sizeof(float) * n);

		int got = MODULE_PULL(om->fm, fm_buf, n);
        if (got < (int)n) {
            for (int i = got; i < (int)n; ++i) fm_buf[i] = 0.0f;
        }
    }

	for (unsigned i = 0; i < n; ++i)
    {
        float phase_for_sample = om->phase;
        if (fm_buf) {
            float phase_offset = fm_buf[i] * om->mod_ratio;
            phase_for_sample = wrap_phase(phase_for_sample + phase_offset);
        }

        smpl[i] = generate_sample(om->shape, om->duty, phase_for_sample);

        increment_phase(om);
    }
	return n;
}

void oscillator_connect(Module *mod, Module* fm)
{
	mod->as.oscillator.fm = fm;
}

void oscillator_set_duty(Module *mod, float duty)
{
	mod->as.oscillator.duty = duty;
}

void oscillator_set_modulation(Module *mod, float ratio)
{
	mod->as.oscillator.mod_ratio = ratio;
}

Module *mixer(size_t ninputs)
{
	Module *mod = malloc(sizeof *mod);
	if (!mod) return NULL;

	MixerInput *inputs = calloc(ninputs, sizeof *inputs);
	if (!inputs)
	{
		free(mod);
		return NULL;
	}

	mod->kind = MODULE_MIXER;
	mod->as.mixer.inputs = inputs;
	mod->as.mixer.input_count = ninputs;

	return mod;
}

void mixer_add(Module* mod, unsigned id, Module *in, float gain)
{
	MixerModule *mm = &mod->as.mixer;

	mm->inputs[id].in = in;
	mm->inputs[id].gain = gain;
	mm->inputs[id].set = true;
}

int mixer_cb(Module* mod, float* smpl, unsigned n)
{
	MixerModule *mm = &mod->as.mixer;
	float *temp = calloc(n, sizeof *temp); // TODO: remove allocation at runtime;

	for (size_t i = 0; i < n; ++i)
	{
		smpl[i] = 0;
	}

	for (unsigned i = 0; i < mm->input_count; ++i)
	{
		if (!mm->inputs[i].set) continue;
		MODULE_PULL(mm->inputs[i].in, temp, n);


		for (size_t m = 0; m < n; ++m)
		{
			smpl[m] += temp[m] * mm->inputs[i].gain;
		}
	}
	
	for (size_t m = 0; m < n; ++m) {
		if (smpl[m] > 1.0) smpl[m] = 1.0;
		else if (smpl[m] < -1.0) smpl[m] = -1.0; 
	}

	return n;
}

static void
biquad_load_coefficients(BiquadModule* bm, BiquadFilterKind kind,
						float A, float sn, float cs,
						float alpha, float beta) {
	switch (kind)
	{
		case BQ_LOWPASS:
			bm->b[0] = (1.0 - cs) /2.0;
    		bm->b[1] = 1.0 - cs;
    		bm->b[2] = (1.0 - cs) /2.0;
    		bm->a[0] = 1.0 + alpha;
    		bm->a[1] = -2.0 * cs;
    		bm->a[2] = 1.0 - alpha;
			break;

		case BQ_HIGHPASS:
			bm->b[0] = (1 + cs) /2.0;
    		bm->b[1] = -(1 + cs);
    		bm->b[2] = (1 + cs) /2.0;
    		bm->a[0] = 1 + alpha;
    		bm->a[1] = -2 * cs;
    		bm->a[2] = 1 - alpha;
			break;

		case BQ_BANDPASS:
			bm->b[0] = alpha;
    		bm->b[1] = 0;
    		bm->b[2] = -alpha;
    		bm->a[0] = 1 + alpha;
    		bm->a[1] = -2 * cs;
    		bm->a[2] = 1 - alpha;
			break;
		
		case BQ_NOTCH:
			bm->b[0] = 1;
    		bm->b[1] = -2 * cs;
    		bm->b[2] = 1;
    		bm->a[0] = 1 + alpha;
    		bm->a[1] = -2 * cs;
    		bm->a[2] = 1 - alpha;
			break;
		
		case BQ_PEAK:
			bm->b[0] = 1 + (alpha * A);
    		bm->b[1] = -2 * cs;
    		bm->b[2] = 1 - (alpha * A);
    		bm->a[0] = 1 + (alpha /A);
    		bm->a[1] = -2 * cs;
    		bm->a[2] = 1 - (alpha /A);
			break;
		
		case BQ_LOWSHELF:
			bm->b[0] = A * ((A + 1) - (A - 1) * cs + beta * sn);
    		bm->b[1] = 2 * A * ((A - 1) - (A + 1) * cs);
    		bm->b[2] = A * ((A + 1) - (A - 1) * cs - beta * sn);
    		bm->a[0] = (A + 1) + (A - 1) * cs + beta * sn;
    		bm->a[1] = -2 * ((A - 1) + (A + 1) * cs);
    		bm->a[2] = (A + 1) + (A - 1) * cs - beta * sn;
			break;
		
		case BQ_HIGHSHELF:
			bm->b[0] = A * ((A + 1) + (A - 1) * cs + beta * sn);
    		bm->b[1] = -2 * A * ((A - 1) + (A + 1) * cs);
    		bm->b[2] = A * ((A + 1) + (A - 1) * cs - beta * sn);
    		bm->a[0] = (A + 1) - (A - 1) * cs + beta * sn;
    		bm->a[1] = 2 * ((A - 1) - (A + 1) * cs);
    		bm->a[2] = (A + 1) - (A - 1) * cs - beta * sn;
			break;

		default:
			break;
	}
}

Module *biquad(BiquadFilterKind kind, float freq, float Q, float gain_db, Module *in)
{
	Module *mod = malloc(sizeof *mod);
	if (!mod) return NULL;

	mod->kind = MODULE_BIQUAD;
	mod->as.biquad.in = in;

    BiquadModule* bm = &mod->as.biquad;

	float A = pow(10, gain_db / 40);
    float omega = 2 * M_PI * freq / sample_rate_hz;
    float sn = sin(omega);
    float cs = cos(omega);
    float alpha = sn / (2*Q);
    float beta = sqrt(A + A);

	biquad_load_coefficients(bm, kind, A, sn, cs, alpha, beta);

	bm->a[1] /= (bm->a[0]);
	bm->a[2] /= (bm->a[0]);
	bm->b[0] /= (bm->a[0]);
	bm->b[1] /= (bm->a[0]);
	bm->b[2] /= (bm->a[0]);

	bm->x[0] = 0.f;
	bm->x[1] = 0.f;
	bm->y[0] = 0.f;
	bm->y[1] = 0.f;

	return mod;
}

int biquad_cb(Module *mod, float *smpl, unsigned n)
{
    BiquadModule *bm = &mod->as.biquad;

	if (!bm->in) return -1;

    int got = MODULE_PULL(bm->in, smpl, n);
    if (got <= 0) return got;   

    for (int i = 0; i < got; ++i)
    {
        float input = smpl[i];
        float output =  (bm->b[0] * input) +
                        (bm->b[1] * bm->x[0]) +
                        (bm->b[2] * bm->x[1])
                        - (bm->a[1] * bm->y[0])
                        - (bm->a[2] * bm->y[1]);

        bm->x[1] = bm->x[0];
        bm->x[0] = input;
        bm->y[1] = bm->y[0];
        bm->y[0] = output;

        smpl[i] = output;
    }

    return got;
}

Module *amplifier(float gain, Module *in)
{
	Module *mod = malloc(sizeof *mod);
	if (!mod) return NULL;

	mod->kind = MODULE_AMPLIFIER;
	AmplifierModule *am = &mod->as.amplifier;

	am->gain = gain;
	am->in = in;
	am->cv = NULL;

	return mod;
}

void amplifier_cv(Module *mod, Module *cv)
{
	mod->as.amplifier.cv = cv;
}

int amplifier_cb(Module *mod, float *smpl, unsigned n)
{
	AmplifierModule *am = &mod->as.amplifier;

	if (!am->in) return -1;

	int got = MODULE_PULL(am->in, smpl, n);

	float *cv = malloc(n * sizeof*cv); // TODO: no allocations at runtime;
	if (am->cv)
		MODULE_PULL(am->cv, cv, n);
	else
		for (size_t i = 0; i < n; ++i)
        	cv[i] = 1.0f;

	if (got > 0)
	{
		for (size_t i = 0; i < n; ++i)
		{
			smpl[i] *= am->gain * cv[i];
		}
	}

	return got;
}

Module *envelope (float A, float D, float S, float R)
{
	Module *mod = malloc(sizeof *mod);
	if (!mod) return NULL;

	mod->kind = MODULE_ENVELOPE;
	envelope_set_attack (mod, A);
	envelope_set_decay (mod, D);
	envelope_set_sustain (mod, S);
	envelope_set_release (mod, R);

	mod->as.envelope.phase = 0;
	mod->as.envelope.state = ENV_IDLE;
	mod->as.envelope.gate_override = false;
	mod->as.envelope.gate = NULL;
	mod->as.envelope.auto_trigger = false;

	return mod;
}

void envelope_set_attack (Module *mod, float A)
{
	mod->as.envelope.At_secs = A;
}

void envelope_set_decay (Module *mod, float D)
{
	mod->as.envelope.Dt_secs = D;
}

void envelope_set_sustain (Module *mod, float S)
{
	mod->as.envelope.S_level = S;
}

void envelope_set_release (Module *mod, float R)
{
	mod->as.envelope.Rt_secs = R;
}

void envelope_toggle_gate (Module *mod)
{
	EnvelopeModule *em = &mod->as.envelope;

	if (!em->gate_override)
	{
		em->state = ENV_ATTACK;
		em->phase = 0;
		em->gate_override = true;
	}
	else
	{
		em->state = ENV_RELEASE;
		em->phase = 0;
		em->gate_override = false;
	}
}

void envelope_toggle_auto(Module *mod)
{
	bool enabled = mod->as.envelope.auto_trigger;
	mod->as.envelope.auto_trigger = !enabled; 
}

void envelope_gate(Module *mod, Module *gate)
{
	mod->as.envelope.gate = gate;
}

int
envelope_cb (Module *mod, float *smpl, unsigned n)
{
    EnvelopeModule *env = &mod->as.envelope;

    for (unsigned i = 0; i < n; i++)
    {
        switch (env->state)
        {
        case ENV_IDLE:
        {
            smpl[i] = 0.0f;

            bool should_trigger = env->gate_override;
            if (env->gate) {
                float gate_cv = 0.0f;
                MODULE_PULL(env->gate, &gate_cv, 1);
                should_trigger = should_trigger || (gate_cv > 0.5f);
            }

            if ((should_trigger || env->auto_trigger) && env->state == ENV_IDLE) {
                env->state = ENV_ATTACK;
                env->phase = 0.0f;
            }
            break;
        }

        case ENV_ATTACK:
        {
            float p = env->phase;
            float out = p; 
            float inc = 1.0f / (env->At_secs * sample_rate_hz);

            env->phase += inc;

            if (env->phase >= 1.0f) {
                out = 1.0f;
                env->state = ENV_DECAY;
                env->phase = 0.0f;
            }

            smpl[i] = out;
            break;
        }

        case ENV_DECAY:
        {
            float p = env->phase;
            float inc = 1.0f / (env->Dt_secs * sample_rate_hz);

            if (p >= 1.0f) {
                smpl[i] = env->S_level;
                env->state = ENV_SUSTAIN;
                env->phase = 0.0f;
            } else {
                float out = 1.0f - p * (1.0f - env->S_level);
                env->phase += inc;
                if (env->phase >= 1.0f) {
                    smpl[i] = env->S_level;
                    env->state = ENV_SUSTAIN;
                    env->phase = 0.0f;
                } else {
                    smpl[i] = out;
                }
            }
            break;
        }

        case ENV_SUSTAIN:
        {
            smpl[i] = env->S_level;

            bool gate_high = env->gate_override;
            if (env->gate) {
                float gate_cv = 0.0f;
                MODULE_PULL(env->gate, &gate_cv, 1);
                gate_high = gate_high || (gate_cv > 0.5f);
            }

            if (!gate_high) {
                env->state = ENV_RELEASE;
                env->phase = 0.0f;
            }
            break;
        }

        case ENV_RELEASE:
        {
            float p = env->phase;
            float inc = 1.0f / (env->Rt_secs * sample_rate_hz);

            if (p >= 1.0f) {
                smpl[i] = 0.0f;
                env->state = ENV_IDLE;
                env->phase = 0.0f;
            } else {
                float out = env->S_level * (1.0f - p);
                env->phase += inc;
                if (env->phase >= 1.0f) {
                    smpl[i] = 0.0f;
                    env->state = ENV_IDLE;
                    env->phase = 0.0f;
                } else {
                    smpl[i] = out;
                }
            }
            break;
        }

        default: UNREACHABLE ("env->state");
        }
    }
    return n;
}

