#include <math.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdbool.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define UNREACHABLE(where) do { \
	fprintf(stderr, "Entered unreachable state ("where")\n"); \
	abort(); \
} while(0)

/* --- Config --- */

static const size_t sample_rate_hz = 16000;

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
	Module* in;
} OscillatorModule;

Module *oscillator(OscillatorShape shape, float freq);
void oscillator_set_freq(Module *mod, float freq);
void oscillator_set_duty(Module *mod, float duty);
void oscillator_connect(Module *mod, Module* in);
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
} AmplifierModule;

Module *amplifier(float gain, Module *in);
int amplifier_cb(Module *mod, float *smpl, unsigned n);

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
	} as;
};

void register_modules(void)
{
	pull_cb_table[MODULE_OSCILLATOR] = oscillator_cb;
	pull_cb_table[MODULE_MIXER] = mixer_cb;
	pull_cb_table[MODULE_BIQUAD] = biquad_cb;
	pull_cb_table[MODULE_AMPLIFIER] = amplifier_cb;
}


/* --- Testing --- */

int
main(void)
{
	/* --- Setup --- */
	register_modules();

	Module *sine = oscillator(SHAPE_SINE, 1000);
	Module *rect = oscillator(SHAPE_RECT, 100);
	Module *saw = oscillator(SHAPE_SAW, 200);
	Module *sum = mixer(2);

	// mixer_add(sum, 0, sine, 0.1);
	mixer_add(sum, 0, rect, 0.4);
	mixer_add(sum, 1, saw, 0.4);

	Module *amp = amplifier(0.1, sine);
	oscillator_connect(rect, amp);

	// Module *dump = biquad(BQ_LOWPASS, 200, 0.7071f, 6, sum);

	Module *out = sum;

	/* --- Output --- */

	const int want = ms_to_samples(1000);
	float *buffer = calloc(want, sizeof *buffer);

	int got = 0;

	while (got < want)
	{
		int got_now = MODULE_PULL(out, buffer + got, want - got);
		if (got_now < 0) return 69;
		got += got_now;
	}

	FILE* ofile = fopen("out.raw", "wb");
	fwrite(buffer, sizeof *buffer, got, ofile);
	fclose(ofile);
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
	mod->as.oscillator.in = NULL;

	return mod;
}

static float generate_sample(OscillatorModule *om)
{
	const bool pgtd = om->phase < om->duty;
	const float a = 2/(1-om->duty); // TODO: move into struct;

	switch (om->shape)
	{
	case SHAPE_SINE: return sinf(om->phase * 2.0f * M_PI); 
	case SHAPE_RECT: return pgtd ? 1.0 : -1.0; 
	case SHAPE_SAW:  return pgtd ? 2*om->phase/om->duty - 1 : -a*om->phase + a - 1;
	default: UNREACHABLE("om->shape");
	}
}

static void increment_phase(OscillatorModule *om)
{
	om->phase += om->freq / sample_rate_hz;
		if (om->phase > 1.0f) om->phase -= 1.0f;
}

int oscillator_cb(Module* mod, float* smpl, unsigned n)
{
	OscillatorModule *om = &mod->as.oscillator;

	if (om->in)
	{
		n = MODULE_PULL(om->in, smpl, n);
	}

	for (size_t i = 0; i < n; ++i)
	{
		if (!om->in) smpl[i] = 0;
		smpl[i] += generate_sample(om);

		increment_phase(om);
	}

	return n;
}

void oscillator_connect(Module *mod, Module* in)
{
	mod->as.oscillator.in = in;
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

	return mod;
}

int amplifier_cb(Module *mod, float *smpl, unsigned n)
{
	AmplifierModule *am = &mod->as.amplifier;

	int got = MODULE_PULL(am->in, smpl, n);

	if (got > 0)
	{
		for (size_t i = 0; i < n; ++i)
			smpl[i] *= am->gain;
	}

	return got;
}

