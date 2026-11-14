// THE LIKENESS OF THE FANDOMS' NAMES LEARNT ON THE CARD (FandomLikenessOnCard): the names' vectors kept
// as the database keeps them — half precision, read two numbers a word of 32 bits and made single by the
// card's own instruction —, the weights, the sums and the steps of Adam in single precision, nothing but
// the weights brought back. Of a step, in this order on the default stream:
//
//   weigh     c[j] = Σ w y_j²                     a block a name
//   products  A of an anchor and each candidate,  a block an anchor, its weighed vector in the block's
//             and √(B C)                          memory, a warp a candidate
//   pairs     the loss's slope of every pair of   a block an anchor: its own part summed in the block
//             an anchor, as numbers of the        and written once; a candidate's and a name's added
//             candidates and of the names         atomically
//   along     Σ (number) a y of every candidate   a thread two dimensions of a run of candidates
//   squares   Σ (number) y² of every name         a thread two dimensions of a run of names
//   adam      a step of every weight              a thread a weight
//
// The sums of many threads added atomically in single precision: their order changes their last
// digits, never more (the speed of a computation before its last digits).

#define BLOCK 256
#define WARPS (BLOCK / 32)

// The longest vector of a name: the anchor's weighed one in a block's memory
#define MAX_DIM 4096

// Candidates, or names, a thread sums two dimensions of: enough work for the threads of a card, few
// atomic additions of a sum
#define RUN 256

__device__ __forceinline__ float half_of(unsigned int h) {
    float f;
    unsigned short s = (unsigned short) h;
    asm("cvt.f32.f16 %0, %1;" : "=f"(f) : "h"(s));
    return f;
}

__device__ __forceinline__ float warp_sum(float x) {
    for (int o = 16; o > 0; o >>= 1) x += __shfl_down_sync(0xffffffff, x, o);
    return x;
}

// The sum of x of every thread of the block, in its first thread
__device__ __forceinline__ float block_sum(float x, float* shared) {
    x = warp_sum(x);
    int lane = threadIdx.x & 31, warp = threadIdx.x >> 5;
    if (lane == 0) shared[warp] = x;
    __syncthreads();
    x = threadIdx.x < WARPS ? shared[threadIdx.x] : 0.0f;
    if (warp == 0) x = warp_sum(x);
    return x;
}

// c[j] = Σ w y_j² of every name j: a block a name, two numbers a reading
extern "C" __global__ void weigh(const unsigned int* y, const float* w, float* c, int n, int dim) {
    int j = blockIdx.x;
    if (j >= n) return;
    __shared__ float shared[WARPS];
    const unsigned int* row = y + (long long) j * (dim / 2);
    float x = 0.0f;
    for (int q = threadIdx.x; q < dim / 2; q += BLOCK) {
        unsigned int two = row[q];
        float a = half_of(two), b = half_of(two >> 16);
        x += w[2 * q] * a * a + w[2 * q + 1] * b * b;
    }
    x = block_sum(x, shared);
    if (threadIdx.x == 0) c[j] = fmaxf(x, 1e-12f);
}

// Of the anchor i and every candidate s of it: prod[s] = A = Σ w a y, root[s] = √(B C), coef[s] = 0 for
// the pairs to add to. A block an anchor, the anchor's weighed vector in the block's memory, a warp a
// candidate, its lanes along the dimensions two at a time
extern "C" __global__ void products(const unsigned int* y, const float* w, const float* c, const int* anchors,
                                   const int* candStart, const int* cand, float* prod, float* root, float* coef,
                                   int count, int dim) {
    int i = blockIdx.x;
    if (i >= count) return;
    __shared__ float wa[MAX_DIM];
    int a = anchors[i];
    const unsigned int* ra = y + (long long) a * (dim / 2);
    for (int q = threadIdx.x; q < dim / 2; q += BLOCK) {
        unsigned int two = ra[q];
        wa[2 * q] = w[2 * q] * half_of(two);
        wa[2 * q + 1] = w[2 * q + 1] * half_of(two >> 16);
    }
    __syncthreads();
    int lane = threadIdx.x & 31, warp = threadIdx.x >> 5;
    float ca = c[a];
    for (int s = candStart[i] + warp; s < candStart[i + 1]; s += WARPS) {
        int name = cand[s];
        const unsigned int* r = y + (long long) name * (dim / 2);
        float x = 0.0f;
        for (int q = lane; q < dim / 2; q += 32) {
            unsigned int two = r[q];
            x += wa[2 * q] * half_of(two) + wa[2 * q + 1] * half_of(two >> 16);
        }
        x = warp_sum(x);
        if (lane == 0) {
            prod[s] = x;
            root[s] = sqrtf(ca * c[name]);
            coef[s] = 0.0f;
        }
    }
}

// The pairs of the anchor i: of the loss log(1 + e^(−(cos(a, near) − cos(a, far)) / temperature)) its
// slope g, and of ∂cos/∂w = a y / √(BC) − A / (2 √(BC)) (a² / B + y² / C) the numbers of a y of every
// candidate (coef) and of y² of every name (ofName) — the anchor's own a² part summed in the block and
// added once
extern "C" __global__ void pairs(const int* pairStart, const int* near, const int* far, const float* weight,
                                const int* anchors, const int* cand, const float* prod, const float* root,
                                const float* c, float* coef, float* ofName, int count, float temperature) {
    int i = blockIdx.x;
    if (i >= count) return;
    __shared__ float shared[WARPS];
    int a = anchors[i];
    float b = c[a];
    float own = 0.0f;
    for (int p = pairStart[i] + threadIdx.x; p < pairStart[i + 1]; p += BLOCK) {
        int n = near[p], f = far[p];
        float rn = root[n], rf = root[f], pn = prod[n], pf = prod[f];
        float margin = (__fdividef(pn, rn) - __fdividef(pf, rf)) / temperature;
        // d/dm log(1 + e^(−m)) = −σ(−m): of the near's cosine +, of the far's −
        float g = -weight[p] / (1.0f + __expf(margin)) / temperature;
        atomicAdd(&coef[n], g / rn);
        atomicAdd(&coef[f], -g / rf);
        own += -g * pn / (2.0f * rn * b) + g * pf / (2.0f * rf * b);
        int yn = cand[n], yf = cand[f];
        atomicAdd(&ofName[yn], -g * pn / (2.0f * rn * c[yn]));
        atomicAdd(&ofName[yf], g * pf / (2.0f * rf * c[yf]));
    }
    own = block_sum(own, shared);
    if (threadIdx.x == 0) atomicAdd(&ofName[a], own);
}

// grad[k] += Σ coef[s] a(s)_k y(s)_k of every candidate s: a thread two dimensions of a run of RUN
// candidates, the threads of a warp along the dimensions — the readings of a row side by side
extern "C" __global__ void along(const unsigned int* y, const int* cand, const int* rowOfAnchor, const float* coef,
                                float* grad, int count, int dim) {
    long long t = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    int half = dim / 2;
    int q = (int) (t % half);
    long long from = (t / half) * RUN;
    if (from >= count) return;
    long long to = min((long long) count, from + RUN);
    float x0 = 0.0f, x1 = 0.0f;
    for (long long s = from; s < to; s++) {
        float k = coef[s];
        if (k == 0.0f) continue;
        unsigned int ya = y[(long long) rowOfAnchor[s] * half + q];
        unsigned int yc = y[(long long) cand[s] * half + q];
        x0 += k * half_of(ya) * half_of(yc);
        x1 += k * half_of(ya >> 16) * half_of(yc >> 16);
    }
    atomicAdd(&grad[2 * q], x0);
    atomicAdd(&grad[2 * q + 1], x1);
}

// grad[k] += Σ ofName[j] y_jk² of every name j: as along
extern "C" __global__ void squares(const unsigned int* y, const float* ofName, float* grad, int count, int dim) {
    long long t = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    int half = dim / 2;
    int q = (int) (t % half);
    long long from = (t / half) * RUN;
    if (from >= count) return;
    long long to = min((long long) count, from + RUN);
    float x0 = 0.0f, x1 = 0.0f;
    for (long long j = from; j < to; j++) {
        float k = ofName[j];
        if (k == 0.0f) continue;
        unsigned int two = y[j * half + q];
        float a = half_of(two), b = half_of(two >> 16);
        x0 += k * a * a;
        x1 += k * b * b;
    }
    atomicAdd(&grad[2 * q], x0);
    atomicAdd(&grad[2 * q + 1], x1);
}

// A step of Adam of every weight, its slope pulled toward 1 by l2, none below nought; the slope cleared
// for the next step. c1 and c2: 1 − β1^t and 1 − β2^t
extern "C" __global__ void adam(float* w, float* m, float* v, float* grad, int dim, float l2, float rate,
                               float b1, float b2, float eps, float c1, float c2) {
    int k = blockIdx.x * blockDim.x + threadIdx.x;
    if (k >= dim) return;
    float g = grad[k] + l2 * (w[k] - 1.0f);
    grad[k] = 0.0f;
    m[k] = b1 * m[k] + (1.0f - b1) * g;
    v[k] = b2 * v[k] + (1.0f - b2) * g * g;
    float step = rate * (m[k] / c1) / (sqrtf(v[k] / c2) + eps);
    w[k] = fmaxf(0.0f, w[k] - step);
}

// The weight of the pairs of the anchor i put in the user's order — the near the closer —, summed in the
// block and added once
extern "C" __global__ void right(const int* pairStart, const int* near, const int* far, const float* weight,
                                const float* prod, const float* root, double* sum, int count) {
    int i = blockIdx.x;
    if (i >= count) return;
    __shared__ float shared[WARPS];
    float own = 0.0f;
    for (int p = pairStart[i] + threadIdx.x; p < pairStart[i + 1]; p += BLOCK) {
        int n = near[p], f = far[p];
        if (prod[n] / root[n] > prod[f] / root[f]) own += weight[p];
    }
    own = block_sum(own, shared);
    if (threadIdx.x == 0) atomicAdd(sum, (double) own);
}

// out[i * n + j] = the cosine of the rows asked[i] and j of every row j: a block a row asked for and a run
// of rows, the row asked for in the block's memory, a warp a row
extern "C" __global__ void cosines(const unsigned int* y, const float* c, const int* asked, int count, int n,
                                  int dim, float* out) {
    int runs = (n + RUN - 1) / RUN;
    int i = blockIdx.x / runs;
    if (i >= count) return;
    int from = (blockIdx.x % runs) * RUN;
    __shared__ float qa[MAX_DIM];
    int a = asked[i];
    const unsigned int* ra = y + (long long) a * (dim / 2);
    for (int q = threadIdx.x; q < dim / 2; q += BLOCK) {
        unsigned int two = ra[q];
        qa[2 * q] = half_of(two);
        qa[2 * q + 1] = half_of(two >> 16);
    }
    __syncthreads();
    int lane = threadIdx.x & 31, warp = threadIdx.x >> 5;
    float ca = c[a];
    int to = min(n, from + RUN);
    for (int j = from + warp; j < to; j += WARPS) {
        const unsigned int* r = y + (long long) j * (dim / 2);
        float x = 0.0f;
        for (int q = lane; q < dim / 2; q += 32) {
            unsigned int two = r[q];
            x += qa[2 * q] * half_of(two) + qa[2 * q + 1] * half_of(two >> 16);
        }
        x = warp_sum(x);
        if (lane == 0) out[(long long) i * n + j] = x / sqrtf(ca * c[j]);
    }
}
