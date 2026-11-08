// THE ITEMS' FEATURES ON THE CARD, beside the products of cuBLAS: an input block holds the rows of
// the items that have it alone (InputBlock), its product a row of each of them — carried onto the
// items' own rows and columns here, and taken from them for the products back. The ones of an item
// (the constant, the marks of its blocks, its values of the other facets) summed of the weights.
// And of the scores of a task, what every row is shown with (SuggestionScores.shown): its scores made
// where they are chosen, the few chosen brought back, never the rows of every value.

#define BLOCK 256

// out = the n half-precision numbers of src (a block's rows as kept) as single precision, for the products to read
extern "C" __global__ void halves_to_floats(long long n, const unsigned short* src, float* out) {
    long long t = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    if (t >= n) return;
    unsigned short h = src[t];
    float f;
    asm("cvt.f32.f16 %0, %1;" : "=f"(f) : "h"(h));
    out[t] = f;
}

// out (k × n) += src (k × count) onto the columns of the block's items: an item once in a block
extern "C" __global__ void scatter_columns(int k, int count, const float* src, const int* items, float* out, int n) {
    long long t = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    if (t >= (long long) k * count) return;
    int q = (int) (t / count);
    int j = (int) (t % count);
    out[(size_t) q * n + items[j]] += src[t];
}

// out (k × count) = the columns of the block's items of src (k × n)
extern "C" __global__ void gather_columns(int k, int count, const float* src, int n, const int* items, float* out) {
    long long t = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    if (t >= (long long) k * count) return;
    int q = (int) (t / count);
    int j = (int) (t % count);
    out[t] = src[(size_t) q * n + items[j]];
}

// out (n × width) += src (count × width) onto the rows of the block's items
extern "C" __global__ void scatter_rows(int count, int width, const float* src, const int* items, float* out) {
    long long t = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    if (t >= (long long) count * width) return;
    int j = (int) (t / width);
    int c = (int) (t % width);
    out[(size_t) items[j] * width + c] += src[t];
}

// out (n × width) = of every item the rows of a (features × width) of its ones summed: at[i] .. at[i + 1] of ones
extern "C" __global__ void ones_rows(int n, int width, const int* at, const int* ones, const float* a, float* out) {
    long long t = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    if (t >= (long long) n * width) return;
    int i = (int) (t / width);
    int c = (int) (t % width);
    float s = 0.0f;
    for (int k = at[i]; k < at[i + 1]; k++) s += a[(size_t) ones[k] * width + c];
    out[t] = s;
}

// THE ONES OF THE ITEMS OF EVERY MODEL: Uᵀ (kr × n) = of every row q of Aᵀ (kr × width) and every item
// i, the weights of its ones summed — at[i] .. at[i + 1] of ones — what the blocks' products add to after
extern "C" __global__ void ones_coordinates(int kr, int n, int width, const int* at, const int* ones, const float* aT, float* uT) {
    long long t = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    if (t >= (long long) kr * n) return;
    int q = (int) (t / n);
    int i = (int) (t % n);
    const float* a = aT + (size_t) q * width;
    float s = 0.0f;
    for (int k = at[i]; k < at[i + 1]; k++) s += a[ones[k]];
    uT[t] = s;
}

// And back: dAᵀ (kr × width) = of every row q and every feature c, the slopes of the items whose one it
// is summed — of the feature c, its items at[c] .. at[c + 1] of items; nought of a feature no item has
// as a one, the blocks' columns, which their products write after
extern "C" __global__ void ones_gradient(int kr, int n, int width, const int* at, const int* items, const float* duT, float* gA) {
    long long t = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    if (t >= (long long) kr * width) return;
    int q = (int) (t / width);
    int c = (int) (t % width);
    const float* d = duT + (size_t) q * n;
    float s = 0.0f;
    for (int k = at[c]; k < at[c + 1]; k++) s += d[items[k]];
    gA[t] = s;
}

// THE CHOICE OF WHAT A ROW IS SHOWN WITH, a block a row. Of the row r: its item, the item's fold f —
// its coordinates U[item, f·rank ..] by the fold's model; its values — those of cols from colAt[r],
// colLen[r] of them, or every value when colAt[r] < 0 — each scored as the processor does
// (Simd.combine: the shift, then every coordinate by fma, in order; the site's example after), the
// closed (ascending, closedAt[r] .. closedAt[r + 1]) passed over. A score above half is likely;
// of the rest the `least` highest, of equal scores the lower value.

#define MAX_RANK 64
#define MAX_LEAST 8

struct Row {
    int item, fold, colAt, colLen, closedFrom, closedTo, exFrom, exTo;
};

__device__ __forceinline__ Row row_of(int r, const int* item, const int* foldOf, const int* colAt, const int* colLen, const int* closedAt, const int* exAt) {
    Row w;
    w.item = item[r];
    w.fold = foldOf[w.item];
    w.colAt = colAt[r];
    w.colLen = colLen[r];
    w.closedFrom = closedAt[r];
    w.closedTo = closedAt[r + 1];
    w.exFrom = exAt[r];
    w.exTo = exAt[r + 1];
    return w;
}

__device__ __forceinline__ bool shut(int v, const int* closed, int from, int to) {
    int lo = from, hi = to - 1;
    while (lo <= hi) {
        int mid = (lo + hi) >> 1;
        int c = closed[mid];
        if (c == v) return true;
        if (c < v) lo = mid + 1; else hi = mid - 1;
    }
    return false;
}

__device__ __forceinline__ float score_of(
    int v, const float* u, int rank, const float* side, int m, const float* shift, const Row& w, const int* ex, float example
) {
    float s = shift[v];
    for (int q = 0; q < rank; q++) s = fmaf(side[(size_t) q * m + v], u[q], s);
    for (int e = w.exFrom; e < w.exTo; e++) if (ex[e] == v) s += example;
    return s;
}

// Of a score and its value before another's: higher, or as high and of a lower value
__device__ __forceinline__ bool before(float s, int v, float t, int u) {
    return s > t || s == t && v < u;
}

#define SHOWN_PARAMETERS     const int* item, const int* foldOf, const float* U, int width, int rank,     const float* vt, const float* shifts, const float* examples, int m,     const int* colAt, const int* colLen, const int* cols, const int* closedAt, const int* closed, const int* exAt, const int* ex, float half

// The likely of a row kept in its own stripe of `cap` by the one pass; a row of more is written again whole (shown_likely)
#define WARPS (BLOCK / 32)

// Of two lists of the highest, each the highest first, the `least` highest of both into the first
__device__ void merge_highest(float* as, int* ap, int* av, int& an, const float* bs, const int* bp, const int* bv, int bn, int least) {
    float rs[MAX_LEAST];
    int rp[MAX_LEAST], rv[MAX_LEAST];
    int i = 0, j = 0, k = 0;
    while (k < least && (i < an || j < bn)) {
        bool first = j >= bn || i < an && before(as[i], av[i], bs[j], bv[j]);
        if (first) {
            rs[k] = as[i]; rp[k] = ap[i]; rv[k] = av[i]; i++;
        } else {
            rs[k] = bs[j]; rp[k] = bp[j]; rv[k] = bv[j]; j++;
        }
        k++;
    }
    for (int q = 0; q < k; q++) {
        as[q] = rs[q]; ap[q] = rp[q]; av[q] = rv[q];
    }
    an = k;
}

// ONE PASS OVER A ROW, a block a row, a value a thread at a time: of every value its score; the likely
// written into the row's stripe in the order of their places — a warp's by its ballot, the warps' one
// after another — `cap` at most, their number in likely[r] however many; of the rest every thread's
// `least` highest, merged a warp at a time by its lanes' registers, then the warps' by one thread:
// topPlace, topScore, topCount
extern "C" __global__ void shown_one(
    SHOWN_PARAMETERS, int least, int cap, int* likely, int* topCount, int* topPlace, float* topScore, int* stripePlace, float* stripeScore
) {
    __shared__ float u[MAX_RANK];
    __shared__ int warpCount[WARPS];
    __shared__ int warpBase[WARPS];
    __shared__ int written;
    __shared__ float wScore[WARPS * MAX_LEAST];
    __shared__ int wPlace[WARPS * MAX_LEAST];
    __shared__ int wValue[WARPS * MAX_LEAST];
    __shared__ int wKept[WARPS];
    int r = blockIdx.x;
    int lane = threadIdx.x & 31;
    int warp = threadIdx.x >> 5;
    Row w = row_of(r, item, foldOf, colAt, colLen, closedAt, exAt);
    if (threadIdx.x < rank) u[threadIdx.x] = U[(size_t) w.item * width + w.fold * rank + threadIdx.x];
    if (threadIdx.x == 0) written = 0;
    __syncthreads();
    const float* side = vt + (size_t) w.fold * rank * m;
    const float* shift = shifts + (size_t) w.fold * m;
    float example = examples[w.fold];
    int len = w.colAt < 0 ? m : w.colLen;
    int kept = 0;
    float ks[MAX_LEAST];
    int kp[MAX_LEAST], kv[MAX_LEAST];
    for (int from = 0; from < len; from += blockDim.x) {
        int p = from + threadIdx.x;
        bool flag = false;
        float s = 0.0f;
        if (p < len) {
            int v = w.colAt < 0 ? p : cols[w.colAt + p];
            if (!shut(v, closed, w.closedFrom, w.closedTo)) {
                s = score_of(v, u, rank, side, m, shift, w, ex, example);
                if (s > half) flag = true;
                else if (least > 0 && (kept < least || before(s, v, ks[least - 1], kv[least - 1]))) {
                    int at = kept < least ? kept : least - 1;
                    while (at > 0 && before(s, v, ks[at - 1], kv[at - 1])) {
                        ks[at] = ks[at - 1]; kp[at] = kp[at - 1]; kv[at] = kv[at - 1];
                        at--;
                    }
                    ks[at] = s; kp[at] = p; kv[at] = v;
                    if (kept < least) kept++;
                }
            }
        }
        unsigned mask = __ballot_sync(0xffffffffu, flag);
        if (lane == 0) warpCount[warp] = __popc(mask);
        __syncthreads();
        if (threadIdx.x == 0) {
            int b = written;
            for (int k = 0; k < WARPS; k++) {
                warpBase[k] = b;
                b += warpCount[k];
            }
            written = b;
        }
        __syncthreads();
        if (flag) {
            int at = warpBase[warp] + __popc(mask & ((1u << lane) - 1u));
            if (at < cap) {
                stripePlace[(size_t) r * cap + at] = p;
                stripeScore[(size_t) r * cap + at] = s;
            }
        }
    }
    // The highest of the rest: a warp's lanes' lists merged by halves, through their registers
    for (int offset = 16; offset > 0; offset >>= 1) {
        int bn = __shfl_down_sync(0xffffffffu, kept, offset);
        float bs[MAX_LEAST];
        int bp[MAX_LEAST], bv[MAX_LEAST];
        for (int k = 0; k < MAX_LEAST; k++) {
            bs[k] = __shfl_down_sync(0xffffffffu, ks[k], offset);
            bp[k] = __shfl_down_sync(0xffffffffu, kp[k], offset);
            bv[k] = __shfl_down_sync(0xffffffffu, kv[k], offset);
        }
        if (lane < offset) merge_highest(ks, kp, kv, kept, bs, bp, bv, bn, least);
    }
    if (lane == 0) {
        wKept[warp] = kept;
        for (int k = 0; k < kept; k++) {
            wScore[warp * MAX_LEAST + k] = ks[k];
            wPlace[warp * MAX_LEAST + k] = kp[k];
            wValue[warp * MAX_LEAST + k] = kv[k];
        }
    }
    __syncthreads();
    if (threadIdx.x != 0) return;
    // The warps' lists merged by one thread: a few dozen of them
    for (int k = 1; k < WARPS; k++) merge_highest(ks, kp, kv, kept, wScore + k * MAX_LEAST, wPlace + k * MAX_LEAST, wValue + k * MAX_LEAST, wKept[k], least);
    likely[r] = written;
    topCount[r] = kept;
    for (int k = 0; k < kept; k++) {
        topPlace[(size_t) r * least + k] = kp[k];
        topScore[(size_t) r * least + k] = ks[k];
    }
}

// The stripes' likely packed one row after another, from outAt[r] on: a thread a place of a stripe
extern "C" __global__ void shown_pack(
    int count, int cap, const int* likely, const int* outAt, const int* stripePlace, const float* stripeScore, int* outPlace, float* outScore
) {
    long long t = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    if (t >= (long long) count * cap) return;
    int r = (int) (t / cap);
    int k = (int) (t % cap);
    if (k >= min(likely[r], cap)) return;
    outPlace[outAt[r] + k] = stripePlace[t];
    outScore[outAt[r] + k] = stripeScore[t];
}

// THE LIKELY OF THE ROWS OF MORE THAN A STRIPE HOLDS, whole: of the rows `which` (a block each), in the
// order of their places, from outAt[k] on — a stride of the block's values after another, each
// thread's likely placed after those of the threads before it
extern "C" __global__ void shown_likely(SHOWN_PARAMETERS, const int* which, const int* outAt, int* outPlace, float* outScore) {
    __shared__ float u[MAX_RANK];
    __shared__ int warpCount[WARPS];
    __shared__ int warpBase[WARPS];
    __shared__ int written;
    int r = which[blockIdx.x];
    int lane = threadIdx.x & 31;
    int warp = threadIdx.x >> 5;
    Row w = row_of(r, item, foldOf, colAt, colLen, closedAt, exAt);
    if (threadIdx.x < rank) u[threadIdx.x] = U[(size_t) w.item * width + w.fold * rank + threadIdx.x];
    if (threadIdx.x == 0) written = outAt[blockIdx.x];
    __syncthreads();
    const float* side = vt + (size_t) w.fold * rank * m;
    const float* shift = shifts + (size_t) w.fold * m;
    float example = examples[w.fold];
    int len = w.colAt < 0 ? m : w.colLen;
    for (int from = 0; from < len; from += blockDim.x) {
        int p = from + threadIdx.x;
        float s = 0.0f;
        bool flag = false;
        if (p < len) {
            int v = w.colAt < 0 ? p : cols[w.colAt + p];
            if (!shut(v, closed, w.closedFrom, w.closedTo)) {
                s = score_of(v, u, rank, side, m, shift, w, ex, example);
                flag = s > half;
            }
        }
        unsigned mask = __ballot_sync(0xffffffffu, flag);
        if (lane == 0) warpCount[warp] = __popc(mask);
        __syncthreads();
        if (threadIdx.x == 0) {
            int b = written;
            for (int k = 0; k < WARPS; k++) {
                warpBase[k] = b;
                b += warpCount[k];
            }
            written = b;
        }
        __syncthreads();
        if (flag) {
            int at = warpBase[warp] + __popc(mask & ((1u << lane) - 1u));
            outPlace[at] = p;
            outScore[at] = s;
        }
    }
}
