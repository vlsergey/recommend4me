// THE SEARCHES OF EVERY MODEL OF A FITTING ON THE CARD AT ONCE (L-BFGS, Nocedal and Wright,
// algorithm 7.4): every model's point x, gradient g, direction d and remembered steps S and changes
// Y there, size numbers each, a model after another; the two loops of the direction a remembered
// step at a time for every model at once — a launch for them all, not tens of products a model.
//
// A MODEL'S NUMBERS IN THE SLOTS OF THE PRODUCTS: its point and its gradient lie in five pieces, as the
// products read and write them — the weights A, B, E, the biases, the pair's own weights — of the
// slot it holds, na .. nf numbers of each piece a slot, the slots one after another. The order of a
// model's numbers is the pieces' one after another.
//
// A list of models: `count` of them, `list` their numbers; their sums of a launch added in double,
// three — or one — of each.

#define BLOCK 256

// THE REMEMBERED STEPS AND CHANGES IN BRAIN FLOATING POINT (16 bits: single precision's range, a fraction of 8
// bits): what a direction reads twenty times a step, half the bytes; the point, the gradient and the direction
// single
__device__ __forceinline__ unsigned short to_half(float x) {
    unsigned short r;
    asm("cvt.rn.bf16.f32 %0, %1;" : "=h"(r) : "f"(x));
    return r;
}

__device__ __forceinline__ float of_half(unsigned short h) { return __uint_as_float(((unsigned) h) << 16); }

// The blocks of a model of a launch over its size numbers
#define PER_MODEL 64

// The piece of a model's number e and where it lies of the slot s
__device__ __forceinline__ float* piece(
    int e, int s, float* a, float* b, float* c, float* d, float* f, int na, int nb, int nc, int nd, int nf
) {
    if (e < na) return a + (size_t) s * na + e;
    e -= na;
    if (e < nb) return b + (size_t) s * nb + e;
    e -= nb;
    if (e < nc) return c + (size_t) s * nc + e;
    e -= nc;
    if (e < nd) return d + (size_t) s * nd + e;
    return f + (size_t) s * nf + (e - nd);
}

#define PIECES float* a, float* b, float* c, float* d, float* f, int na, int nb, int nc, int nd, int nf
#define PIECES_OF(p) p##a, p##b, p##c, p##d, p##f, na, nb, nc, nd, nf

// The block's sums of three numbers added into sums[0..2]
__device__ void sums3(double x, double y, double z, double* sums) {
    __shared__ double s[3][BLOCK];
    int t = threadIdx.x;
    s[0][t] = x;
    s[1][t] = y;
    s[2][t] = z;
    __syncthreads();
    for (int half = BLOCK / 2; half > 0; half >>= 1) {
        if (t < half) {
            s[0][t] += s[0][t + half];
            s[1][t] += s[1][t + half];
            s[2][t] += s[2][t + half];
        }
        __syncthreads();
    }
    if (t == 0) {
        atomicAdd(sums, s[0][0]);
        atomicAdd(sums + 1, s[1][0]);
        atomicAdd(sums + 2, s[2][0]);
    }
}

// The block's sum of one number added into *sum
__device__ void sum1(double x, double* sum) {
    __shared__ double s[BLOCK];
    int t = threadIdx.x;
    s[t] = x;
    __syncthreads();
    for (int half = BLOCK / 2; half > 0; half >>= 1) {
        if (t < half) s[t] += s[t + half];
        __syncthreads();
    }
    if (t == 0) atomicAdd(sum, s[0]);
}

// The point every slot still searching is asked at: of the model in it, x — or x + t·d once its search
// has begun — written into the slot's pieces
extern "C" __global__ void propose(
    int k, int size, const int* slotModel, const float* X, const float* D, const float* t, const int* begun, PIECES
) {
    long long i = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= (long long) k * size) return;
    int s = (int) (i / size);
    int e = (int) (i % size);
    int q = slotModel[s];
    size_t at = (size_t) q * size + e;
    float v = X[at];
    if (begun[q]) v += t[q] * D[at];
    *piece(e, s, a, b, c, d, f, na, nb, nc, nd, nf) = v;
}

// Of every model of the list, the point taken and its gradient read from its slot's pieces (p…, g…):
// the step s = point − x and the change y = gradient − g into its ring's place `ring` (none when its
// search begins there, −1), x and g made the new ones; the sums s·y, y·y and g·g of the new g
extern "C" __global__ void remember(
    int count, int size, const int* list, const int* slots, const int* ring, int memory,
    float* X, float* G, unsigned short* S, unsigned short* Y,
    float* pa, float* pb, float* pc, float* pd, float* pf,
    float* ga, float* gb, float* gc, float* gd, float* gf, int na, int nb, int nc, int nd, int nf, double* sums
) {
    int idx = blockIdx.x / PER_MODEL;
    if (idx >= count) return;
    int q = list[idx], s = slots[idx], k = ring[idx];
    double sy = 0.0, yy = 0.0, gg = 0.0;
    for (int e = (blockIdx.x % PER_MODEL) * BLOCK + threadIdx.x; e < size; e += PER_MODEL * BLOCK) {
        size_t at = (size_t) q * size + e;
        float p = *piece(e, s, PIECES_OF(p));
        float gn = *piece(e, s, PIECES_OF(g));
        if (k >= 0) {
            float sv = p - X[at];
            float yv = gn - G[at];
            size_t r = ((size_t) q * memory + k) * size + e;
            S[r] = to_half(sv);
            Y[r] = to_half(yv);
            sy += (double) sv * yv;
            yy += (double) yv * yv;
        }
        X[at] = p;
        G[at] = gn;
        gg += (double) gn * gn;
    }
    sums3(sy, yy, gg, sums + 3 * idx);
}

// A STEP OF A LOOP OF THE DIRECTION, of every model of the list, one pass over d: d moved along V of its
// remembered step `along[idx]` (−1: none) — of the first loop (second = 0) by −α, α = ρ · (s·d of that
// step, in dotsIn) kept in alpha; of the second by α − β, β = ρ · (y·d, in dotsIn) — and, d as it is
// then, W of its remembered step `dotted[idx]` (−1: none) times d summed into dotsOut: the product the
// next step moves by. `scale` (or null) multiplies d first: between the loops, d ·= γ; `begin` makes d
// the gradient first
extern "C" __global__ void ring_step(
    int count, int size, const int* list, int memory, const int* along, const unsigned short* V, const float* rho, const double* dotsIn,
    float* alpha, int second, const int* dotted, const unsigned short* W, double* dotsOut, const float* scale, int begin,
    const float* G, float* D
) {
    int idx = blockIdx.x / PER_MODEL;
    if (idx >= count) return;
    int q = list[idx];
    int ka = along[idx], kd = dotted[idx];
    float c = 0.0f;
    if (ka >= 0) {
        float product = rho[idx] * (float) dotsIn[idx];
        if (second) c = alpha[idx] - product;
        else {
            c = -product;
            if (blockIdx.x % PER_MODEL == 0 && threadIdx.x == 0) alpha[idx] = product;
        }
    }
    float gamma = scale != 0 ? scale[idx] : 1.0f;
    float* dq = D + (size_t) q * size;
    const float* gq = G + (size_t) q * size;
    const unsigned short* v = ka >= 0 ? V + ((size_t) q * memory + ka) * size : 0;
    const unsigned short* w = kd >= 0 ? W + ((size_t) q * memory + kd) * size : 0;
    double sum = 0.0;
    for (int e = (blockIdx.x % PER_MODEL) * BLOCK + threadIdx.x; e < size; e += PER_MODEL * BLOCK) {
        float x = begin ? gq[e] : dq[e];
        x *= gamma;
        if (ka >= 0) x += c * of_half(v[e]);
        dq[e] = x;
        if (kd >= 0) sum += (double) of_half(w[e]) * x;
    }
    if (kd >= 0) sum1(sum, dotsOut + idx);
}

// d = −d of every model of the list, and its slope g·d summed into slopes[idx]
extern "C" __global__ void direction_end(int count, int size, const int* list, const float* G, float* D, double* slopes) {
    int idx = blockIdx.x / PER_MODEL;
    if (idx >= count) return;
    int q = list[idx];
    double sum = 0.0;
    for (int e = (blockIdx.x % PER_MODEL) * BLOCK + threadIdx.x; e < size; e += PER_MODEL * BLOCK) {
        size_t at = (size_t) q * size + e;
        float v = -D[at];
        D[at] = v;
        sum += (double) G[at] * v;
    }
    sum1(sum, slopes + idx);
}

// d = −g / |g| of every model of the list: not a descent, the history forgotten, the gradient followed
extern "C" __global__ void direction_reset(int count, int size, const int* list, const float* inverse, const float* G, float* D) {
    long long i = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= (long long) count * size) return;
    int idx = (int) (i / size);
    size_t at = (size_t) list[idx] * size + i % size;
    D[at] = -G[at] * inverse[idx];
}
