// THE PAIRS AND THE NAMES OF A FITTING ON THE CARD: what Ensemble.pairs and Ensemble.names work out on
// the processor, of EVERY MODEL STILL SEARCHING AT ONCE, a launch for them all. Every model's numbers
// one model after another, as the processor lays them: its items' coordinates Uᵀ (rank × items), its
// values' Vᵀ (rank × values), its names' Uhᵀ (rank × names), row after row; of a model in slot s, the
// numbers from s times their size on. The sums of a model — the loss, the slopes of the common bias
// and of the example — gathered by a block and added in double.
//
// NO SCORE IS KEPT: a pair's score U·V is made again where it is needed, sixteen products — of the
// items' side and of the values' side apart, each by its own kernel, without a matrix of the scores
// written and read back, nor any sum of a thread's added into another's.

// The logistic loss of z against the label y, weighed by w, and its slope scaled: softplus and the
// sigmoid of one exponent, as the processor's (Simd.logistic) — by the card's fast instructions (the
// exponent, the logarithm, the reciprocal of a few units in the last place), log(1 + e) of a small e
// by its series, which the logarithm of 1 + e would lose
__device__ __forceinline__ float softplus_of(float e) {
    return e > 1e-3f ? __logf(1.0f + e) : e * (1.0f - 0.5f * e);
}

__device__ __forceinline__ void logistic(float z, float y, float w, float scale, float* loss, float* slope) {
    float e = __expf(-fabsf(z));
    float inverse = __frcp_rn(1.0f + e);
    float sigmoid = z >= 0.0f ? inverse : e * inverse;
    *loss = w * (fmaxf(z, 0.0f) + softplus_of(e) - y * z);
    *slope = w * (sigmoid - y) * scale;
}

// The slope alone, of the same numbers: the side that sums no loss
__device__ __forceinline__ float slope_of(float z, float y, float w, float scale) {
    float e = __expf(-fabsf(z));
    float inverse = __frcp_rn(1.0f + e);
    float sigmoid = z >= 0.0f ? inverse : e * inverse;
    return w * (sigmoid - y) * scale;
}

#define BLOCK 256

// The rank the application fits with, compiled as known: a model's numbers in registers. Any other up
// to MAX_RANK, held in local memory
#define MAX_RANK 64

// The values or items a block holds at once of the other side, by rank: as many as fit 16 KB
#define CHUNK(R) ((R) > 0 ? 4096 / (R) : 64)

// A row of r coordinates of the block's memory into registers: of the rank compiled as known, four
// numbers a reading, a quarter of the readings the products would make of them one by one
template <int R>
__device__ __forceinline__ void row_of_block(const float* from, int r, float* into) {
    if (R == 16) {
        const float4* four = reinterpret_cast<const float4*>(from);
        for (int k = 0; k < 4; k++) {
            float4 x = four[k];
            into[4 * k] = x.x;
            into[4 * k + 1] = x.y;
            into[4 * k + 2] = x.z;
            into[4 * k + 3] = x.w;
        }
    } else
        for (int q = 0; q < r; q++) into[q] = from[q];
}

// The block's three sums added into sums[0..2]
__device__ void block_sums(double a, double b, double c, double* sums) {
    __shared__ double s[3][BLOCK];
    int t = threadIdx.x;
    s[0][t] = a;
    s[1][t] = b;
    s[2][t] = c;
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

// Whether the item i may have the value j: its array of the values it may have, as a mask of a bit
// a value — g the array's, -1 any value
__device__ __forceinline__ bool may(int g, const unsigned int* masks, int words, int j) {
    return g < 0 || ((masks[(size_t) g * words + (j >> 5)] >> (j & 31)) & 1u);
}

// What a model in its slot is read with: its scale (one over its training's weight), the weight of
// the example and the common bias — three numbers a slot — and its held-out fold
struct Model {
    float scale, example, common;
    int heldOut;
};

__device__ __forceinline__ Model model_of(const float* params, const int* heldOut, int slot) {
    Model mo;
    mo.scale = params[3 * slot];
    mo.example = params[3 * slot + 1];
    mo.common = params[3 * slot + 2];
    mo.heldOut = heldOut[slot];
    return mo;
}

// THE ITEMS' SIDE: a thread an item of a model, a block the items of one model. Of every value — the
// values a chunk at a time, their coordinates and biases held by the block — the pair's score made,
// and the pair as it weighs: an entry of the item's own (its value, weight — nought: no pair — label,
// whether the site gives it as an example), else a value the item merely lacks, weighing lack[i] and
// labelled nought where it may have it. Of the items outside the held-out fold: the loss and the
// slopes summed, the item's slopes dU = Σ slope·V written (nought of every other item)
template <int R>
__device__ void pairs_items_of(
    int k, int n, int m, int r, const float* uT, const float* vT, const float* bias, const float* params, const int* heldOut,
    const float* lack, const int* group, const unsigned int* masks, int words, const int* foldOf,
    const long long* firstEntry, const int* entryValue, const float* entryWeight, const float* entryLabel, const float* entryExample,
    const int* order, float* duT, double* sums
) {
    if (R > 0) r = R;
    const int C = CHUNK(R);
    __shared__ __align__(16) float vs[4096];
    __shared__ float bs[CHUNK(R)];
    int tiles = (n + BLOCK - 1) / BLOCK;
    int slot = blockIdx.x / tiles;
    if (slot >= k) return;
    // The items in the order of the arrays of values they may have: a warp's threads go through the same
    // values, not each through its own
    int at = (blockIdx.x % tiles) * BLOCK + threadIdx.x;
    int i = at < n ? order[at] : n;
    Model mo = model_of(params, heldOut, slot);
    const float* u = uT + (size_t) slot * r * n;
    const float* v = vT + (size_t) slot * r * m;
    const float* b = bias + (size_t) slot * m;
    bool on = i < n && foldOf[i] != mo.heldOut;
    float w = on ? lack[i] : 0.0f;
    int g = on ? group[i] : -1;
    long long e = on ? firstEntry[i] : 0, end = on ? firstEntry[i + 1] : 0;
    on = on && (w > 0.0f || e < end);
    float a[R > 0 ? R : MAX_RANK], acc[R > 0 ? R : MAX_RANK];
    for (int q = 0; q < r; q++) {
        a[q] = on ? u[(size_t) q * n + i] : 0.0f;
        acc[q] = 0.0f;
    }
    double l = 0.0, dCommon = 0.0, dExample = 0.0;
    int next = e < end ? entryValue[e] : m;
    for (int from = 0; from < m; from += C) {
        int many = min(C, m - from);
        __syncthreads();
        for (int t = threadIdx.x; t < many * r; t += BLOCK) {
            int q = t / many, jj = t % many;
            vs[jj * r + q] = v[(size_t) q * m + from + jj];
        }
        for (int t = threadIdx.x; t < many; t += BLOCK) bs[t] = b[from + t] + mo.common;
        __syncthreads();
        if (!on) continue;
        // The sums of a chunk in single precision, then added in double: a double sum a pair is the slowest
        // of the card's numbers
        float cl = 0.0f, cc = 0.0f, ce = 0.0f;
        // The item's mask of the values it may have, a word of 32 values read at a time
        unsigned word = 0;
        for (int jj = 0; jj < many; jj++) {
            int j = from + jj;
            if (jj == 0 || (j & 31) == 0) {
                if (g >= 0) word = masks[(size_t) g * words + (j >> 5)];
                // The next 32 values, of none of which a thread of the warp has a pair: passed over at once —
                // the items of the same arrays of values side by side ([order]), most words are so
                bool idle = next >= j + 32 && (w <= 0.0f || (g >= 0 && word == 0u));
                if (__all_sync(__activemask(), idle)) {
                    jj += 31 - (j & 31);
                    continue;
                }
            }
            float slope, loss;
            float vj[R > 0 ? R : MAX_RANK];
            if (j == next) {
                row_of_block<R>(vs + jj * r, r, vj);
                float z = bs[jj];
                for (int q = 0; q < r; q++) z += a[q] * vj[q];
                float we = entryWeight[e];
                if (we > 0.0f) {
                    float ex = entryExample[e];
                    logistic(z + mo.example * ex, entryLabel[e], we, mo.scale, &loss, &slope);
                    cl += loss;
                    ce += slope * ex;
                } else slope = 0.0f;
                e++;
                next = e < end ? entryValue[e] : m;
            } else if (w > 0.0f && (g < 0 || ((word >> (j & 31)) & 1u))) {
                row_of_block<R>(vs + jj * r, r, vj);
                float z = bs[jj];
                for (int q = 0; q < r; q++) z += a[q] * vj[q];
                logistic(z, 0.0f, w, mo.scale, &loss, &slope);
                cl += loss;
            } else continue;
            cc += slope;
            for (int q = 0; q < r; q++) acc[q] += slope * vj[q];
        }
        l += cl;
        dCommon += cc;
        dExample += ce;
    }
    if (i < n) for (int q = 0; q < r; q++) duT[(size_t) slot * r * n + (size_t) q * n + i] = acc[q];
    block_sums(l, dCommon, dExample, sums + 3 * slot);
}

#define PAIRS_ITEMS_PARAMETERS int k, int n, int m, int r, const float* uT, const float* vT, const float* bias, const float* params, const int* heldOut, const float* lack, const int* group, const unsigned int* masks, int words, const int* foldOf, const long long* firstEntry, const int* entryValue, const float* entryWeight, const float* entryLabel, const float* entryExample, const int* order, float* duT, double* sums
#define PAIRS_ITEMS_ARGUMENTS k, n, m, r, uT, vT, bias, params, heldOut, lack, group, masks, words, foldOf, firstEntry, entryValue, entryWeight, entryLabel, entryExample, order, duT, sums

extern "C" __global__ void pairs_items(PAIRS_ITEMS_PARAMETERS) { pairs_items_of<0>(PAIRS_ITEMS_ARGUMENTS); }
extern "C" __global__ void pairs_items_16(PAIRS_ITEMS_PARAMETERS) { pairs_items_of<16>(PAIRS_ITEMS_ARGUMENTS); }

// THE VALUES' SIDE: a thread a value of a model, a block the values of one model. Of every item — the
// items a chunk at a time, their coordinates and what they weigh held by the block — the same pairs as
// the items' side, their slopes made again of the same numbers: the value's own entries of the items
// (the entries again, by the values: their item, weight, label, example) and every item that merely
// lacks it. The value's slopes dV = Σ slope·U and of its bias written; the names' added after them
template <int R>
__device__ void pairs_values_of(
    int k, int n, int m, int r, const float* uT, const float* vT, const float* bias, const float* params, const int* heldOut,
    const float* lack, const int* group, const unsigned int* masks, int words, const int* foldOf,
    const long long* firstOfValue, const int* entryItem, const float* entryWeight, const float* entryLabel, const float* entryExample,
    float* gE, float* gBias
) {
    if (R > 0) r = R;
    const int C = CHUNK(R);
    __shared__ __align__(16) float us[4096];
    __shared__ float ws[CHUNK(R)];
    __shared__ int gs[CHUNK(R)];
    int tiles = (m + BLOCK - 1) / BLOCK;
    int slot = blockIdx.x / tiles;
    if (slot >= k) return;
    int j = (blockIdx.x % tiles) * BLOCK + threadIdx.x;
    Model mo = model_of(params, heldOut, slot);
    const float* u = uT + (size_t) slot * r * n;
    const float* v = vT + (size_t) slot * r * m;
    bool on = j < m;
    float c[R > 0 ? R : MAX_RANK], acc[R > 0 ? R : MAX_RANK];
    for (int q = 0; q < r; q++) {
        c[q] = on ? v[(size_t) q * m + j] : 0.0f;
        acc[q] = 0.0f;
    }
    float base = on ? bias[(size_t) slot * m + j] + mo.common : 0.0f;
    float sum = 0.0f;
    long long e = on ? firstOfValue[j] : 0, end = on ? firstOfValue[j + 1] : 0;
    int next = e < end ? entryItem[e] : n;
    for (int from = 0; from < n; from += C) {
        int many = min(C, n - from);
        __syncthreads();
        for (int t = threadIdx.x; t < many * r; t += BLOCK) {
            int q = t / many, ii = t % many;
            us[ii * r + q] = u[(size_t) q * n + from + ii];
        }
        // An item outside the training of the model weighs nothing, its own entries neither
        for (int t = threadIdx.x; t < many; t += BLOCK) {
            int i = from + t;
            bool trains = foldOf[i] != mo.heldOut;
            ws[t] = trains ? lack[i] : -1.0f;
            gs[t] = group[i];
        }
        __syncthreads();
        if (!on) continue;
        for (int ii = 0; ii < many; ii++) {
            int i = from + ii;
            float w = ws[ii];
            float slope;
            float ui[R > 0 ? R : MAX_RANK];
            if (i == next) {
                float we = entryWeight[e];
                if (w >= 0.0f && we > 0.0f) {
                    row_of_block<R>(us + ii * r, r, ui);
                    float z = base;
                    for (int q = 0; q < r; q++) z += c[q] * ui[q];
                    float ex = entryExample[e];
                    slope = slope_of(z + mo.example * ex, entryLabel[e], we, mo.scale);
                }
                e++;
                next = e < end ? entryItem[e] : n;
                // No pair of it: nothing read of its coordinates, nothing added
                if (!(w >= 0.0f && we > 0.0f)) continue;
            } else if (w > 0.0f && may(gs[ii], masks, words, j)) {
                row_of_block<R>(us + ii * r, r, ui);
                float z = base;
                for (int q = 0; q < r; q++) z += c[q] * ui[q];
                slope = slope_of(z, 0.0f, w, mo.scale);
            } else continue;
            sum += slope;
            for (int q = 0; q < r; q++) acc[q] += slope * ui[q];
        }
    }
    if (on) {
        for (int q = 0; q < r; q++) gE[(size_t) slot * r * m + (size_t) q * m + j] = acc[q];
        gBias[(size_t) slot * m + j] = sum;
    }
}

#define PAIRS_VALUES_PARAMETERS int k, int n, int m, int r, const float* uT, const float* vT, const float* bias, const float* params, const int* heldOut, const float* lack, const int* group, const unsigned int* masks, int words, const int* foldOf, const long long* firstOfValue, const int* entryItem, const float* entryWeight, const float* entryLabel, const float* entryExample, float* gE, float* gBias
#define PAIRS_VALUES_ARGUMENTS k, n, m, r, uT, vT, bias, params, heldOut, lack, group, masks, words, foldOf, firstOfValue, entryItem, entryWeight, entryLabel, entryExample, gE, gBias

extern "C" __global__ void pairs_values(PAIRS_VALUES_PARAMETERS) { pairs_values_of<0>(PAIRS_VALUES_ARGUMENTS); }
extern "C" __global__ void pairs_values_16(PAIRS_VALUES_PARAMETERS) { pairs_values_of<16>(PAIRS_VALUES_ARGUMENTS); }

// The coordinates of every name and of every value of every group gathered where the groups read
// them, of every model: of a model, gu the groups' names one after another, gv their values, a row of
// rank numbers each — read by neighbouring threads side by side, not scattered over the rank's rows
// of every name and value. `from` a model's rows of stride numbers each, its model's after it
extern "C" __global__ void gather(int k, int count, const int* index, const float* from, int stride, int r, float* into) {
    long long t = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    long long size = (long long) count * r;
    if (t >= size * k) return;
    int slot = (int) (t / size);
    long long own = t % size;
    int at = (int) (own / r);
    int q = (int) (own % r);
    into[t] = from[(size_t) slot * r * stride + (size_t) q * stride + index[at]];
}

// THE NAMES OF EVERY MODEL, a group's names against the group's values, every pair weighing one and
// labelled nought but of the name's own value: as the pairs, from both sides, a block of a group a
// tile of BLOCK of one side, the other side's coordinates a chunk at a time in the block's memory.
// `tiles` of a side: its group and its first name or value, two numbers a tile.

// THE VALUES' SIDE: a thread a value of a group. Of every name of the group, the score Uh·V with the
// bias and the common bias; the loss and the slopes summed, the value's slopes over the names carried
// to its coordinates (dVᵀ += Uhᵀ Sn) and its bias — a value of several groups by each of them
template <int R>
__device__ void names_values_of(
    int k, int tileCount, const int* tiles, const int* firstRow, const int* firstValue, const int* width, const int* height,
    const int* values, const int* positive, const float* gu, const float* gv, int names, int columns, int m, int r,
    const float* bias, const float* params, float* gE, float* gBias, double* sums
) {
    if (R > 0) r = R;
    const int C = CHUNK(R);
    __shared__ __align__(16) float us[4096];
    __shared__ int ps[CHUNK(R)];
    int slot = blockIdx.x / tileCount;
    if (slot >= k) return;
    const int* tile = tiles + 2 * (blockIdx.x % tileCount);
    float scale = params[3 * slot], common = params[3 * slot + 2];
    int g = tile[0];
    int col = tile[1] + threadIdx.x;
    int c = width[g];
    int rowsOf = height[g];
    const float* u = gu + ((size_t) slot * names + firstRow[g]) * r;
    bool on = col < c;
    int at = firstValue[g] + (on ? col : 0);
    int val = values[at];
    float b[R > 0 ? R : MAX_RANK], acc[R > 0 ? R : MAX_RANK];
    const float* vv = gv + ((size_t) slot * columns + at) * r;
    for (int q = 0; q < r; q++) {
        b[q] = on ? vv[q] : 0.0f;
        acc[q] = 0.0f;
    }
    float base = on ? bias[(size_t) slot * m + val] + common : 0.0f;
    float sum = 0.0f;
    double l = 0.0, dCommon = 0.0;
    for (int from = 0; from < rowsOf; from += C) {
        int many = min(C, rowsOf - from);
        __syncthreads();
        for (int t = threadIdx.x; t < many * r; t += BLOCK) us[t] = u[(size_t) from * r + t];
        for (int t = threadIdx.x; t < many; t += BLOCK) ps[t] = positive[firstRow[g] + from + t];
        __syncthreads();
        if (!on) continue;
        // The sums of a chunk in single precision, then added in double
        float cl = 0.0f, cc = 0.0f;
        for (int row = 0; row < many; row++) {
            float a[R > 0 ? R : MAX_RANK];
            row_of_block<R>(us + row * r, r, a);
            float z = base;
            for (int q = 0; q < r; q++) z += a[q] * b[q];
            float loss, slope;
            logistic(z, ps[row] == col ? 1.0f : 0.0f, 1.0f, scale, &loss, &slope);
            cl += loss;
            cc += slope;
            for (int q = 0; q < r; q++) acc[q] += a[q] * slope;
            sum += slope;
        }
        l += cl;
        dCommon += cc;
    }
    if (on) {
        for (int q = 0; q < r; q++) atomicAdd(gE + (size_t) slot * r * m + (size_t) q * m + val, acc[q]);
        atomicAdd(gBias + (size_t) slot * m + val, sum);
    }
    block_sums(l, dCommon, 0.0, sums + 3 * slot);
}

#define NAMES_VALUES_PARAMETERS int k, int tileCount, const int* tiles, const int* firstRow, const int* firstValue, const int* width, const int* height, const int* values, const int* positive, const float* gu, const float* gv, int names, int columns, int m, int r, const float* bias, const float* params, float* gE, float* gBias, double* sums
#define NAMES_VALUES_ARGUMENTS k, tileCount, tiles, firstRow, firstValue, width, height, values, positive, gu, gv, names, columns, m, r, bias, params, gE, gBias, sums

extern "C" __global__ void names_values(NAMES_VALUES_PARAMETERS) { names_values_of<0>(NAMES_VALUES_ARGUMENTS); }
extern "C" __global__ void names_values_16(NAMES_VALUES_PARAMETERS) { names_values_of<16>(NAMES_VALUES_ARGUMENTS); }

// THE NAMES' SIDE: a thread a name of a group. Of every value of the group, the same score and slope
// made again of the same numbers, carried to the name's coordinates (dUhᵀ = Vᵀ Snᵀ): a name is of one
// group alone, its slopes written once
template <int R>
__device__ void names_items_of(
    int k, int tileCount, const int* tiles, const int* firstRow, const int* firstValue, const int* width, const int* height,
    const int* rows, const int* positive, const int* values, const float* gu, const float* gv, int names, int columns,
    const float* bias, const float* params, int m, int h, int r, float* duh
) {
    if (R > 0) r = R;
    const int C = CHUNK(R);
    __shared__ __align__(16) float vs[4096];
    __shared__ float bs[CHUNK(R)];
    int slot = blockIdx.x / tileCount;
    if (slot >= k) return;
    const int* tile = tiles + 2 * (blockIdx.x % tileCount);
    float scale = params[3 * slot], common = params[3 * slot + 2];
    int g = tile[0];
    int row = tile[1] + threadIdx.x;
    int c = width[g];
    bool on = row < height[g];
    int name = firstRow[g] + (on ? row : 0);
    const float* a = gu + ((size_t) slot * names + name) * r;
    const float* vv = gv + ((size_t) slot * columns + firstValue[g]) * r;
    float own[R > 0 ? R : MAX_RANK], acc[R > 0 ? R : MAX_RANK];
    for (int q = 0; q < r; q++) {
        own[q] = on ? a[q] : 0.0f;
        acc[q] = 0.0f;
    }
    int positiveCol = on ? positive[name] : -1;
    for (int from = 0; from < c; from += C) {
        int many = min(C, c - from);
        __syncthreads();
        for (int t = threadIdx.x; t < many * r; t += BLOCK) vs[t] = vv[(size_t) from * r + t];
        for (int t = threadIdx.x; t < many; t += BLOCK)
            bs[t] = bias[(size_t) slot * m + values[firstValue[g] + from + t]] + common;
        __syncthreads();
        if (!on) continue;
        for (int col = 0; col < many; col++) {
            float bc[R > 0 ? R : MAX_RANK];
            row_of_block<R>(vs + col * r, r, bc);
            float z = bs[col];
            for (int q = 0; q < r; q++) z += own[q] * bc[q];
            float slope = slope_of(z, positiveCol == from + col ? 1.0f : 0.0f, 1.0f, scale);
            for (int q = 0; q < r; q++) acc[q] += bc[q] * slope;
        }
    }
    if (on) for (int q = 0; q < r; q++) duh[(size_t) slot * r * h + (size_t) q * h + rows[name]] += acc[q];
}

#define NAMES_ITEMS_PARAMETERS int k, int tileCount, const int* tiles, const int* firstRow, const int* firstValue, const int* width, const int* height, const int* rows, const int* positive, const int* values, const float* gu, const float* gv, int names, int columns, const float* bias, const float* params, int m, int h, int r, float* duh
#define NAMES_ITEMS_ARGUMENTS k, tileCount, tiles, firstRow, firstValue, width, height, rows, positive, values, gu, gv, names, columns, bias, params, m, h, r, duh

extern "C" __global__ void names_items(NAMES_ITEMS_PARAMETERS) { names_items_of<0>(NAMES_ITEMS_ARGUMENTS); }
extern "C" __global__ void names_items_16(NAMES_ITEMS_PARAMETERS) { names_items_of<16>(NAMES_ITEMS_ARGUMENTS); }

// THE NAMES AS TEXTS OF A WORK, OF EVERY MODEL: every text block holds the name, so a name's
// coordinates are the name times the text blocks' weights summed — Σ_b s_b A_b, the rows of every
// model's Aᵀ (rank rows a model, dx numbers a row) — plus the constant's and the blocks' marks'.
// `blocks` text blocks, each at offsets[b] of a row (its mark; its numbers after it) and scaled by
// scales[b]. Of every row of the first kr: its sum of the blocks' weights (hd numbers) and its base
extern "C" __global__ void names_sum(
    int kr, int dx, int hd, int blocks, const int* offsets, const float* scales, const float* aT, float* sumT, float* base
) {
    long long t = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    if (t >= (long long) kr * hd) return;
    int row = (int) (t / hd);
    int c = (int) (t % hd);
    const float* a = aT + (size_t) row * dx;
    float s = 0.0f;
    for (int b = 0; b < blocks; b++) s += scales[b] * a[offsets[b] + 1 + c];
    sumT[t] = s;
    if (c == 0) {
        float mark = a[0];
        for (int b = 0; b < blocks; b++) mark += a[offsets[b]];
        base[row] = mark;
    }
}

// Every row of the first kr, of `width` numbers, given its base
extern "C" __global__ void add_base(int kr, int width, const float* base, float* rows) {
    long long t = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    if (t >= (long long) kr * width) return;
    rows[t] += base[t / width];
}

// The names' slopes carried back to the text blocks' weights of every row of the first kr: its slopes
// of the blocks' sum (dSum, hd numbers a row) onto every block's numbers scaled, its names' slopes
// summed (total) onto the constant and every block's mark
extern "C" __global__ void names_back(
    int kr, int dx, int hd, int blocks, const int* offsets, const float* scales, const float* dSum, const float* total, float* gA
) {
    long long t = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    if (t >= (long long) kr * hd) return;
    int row = (int) (t / hd);
    int c = (int) (t % hd);
    float* g = gA + (size_t) row * dx;
    float d = dSum[t];
    for (int b = 0; b < blocks; b++) g[offsets[b] + 1 + c] += scales[b] * d;
    if (c == 0) {
        float sum = total[row];
        g[0] += sum;
        for (int b = 0; b < blocks; b++) g[offsets[b]] += sum;
    }
}

// THE LOSS OF EVERY MODEL ON THE CARD, ITS NUMBERS THERE: a model's point and gradient in five pieces
// of the slot it holds — the weights A, B, E, the biases, the pair's own weights — na .. nf numbers of
// each a slot, the slots one after another; the order of a model's numbers the pieces' one after another

#define PER_SLOT 64

// The piece of a slot's number e and where it lies
__device__ __forceinline__ float* piece_of(
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

// What a slot is read with, three numbers each: its scale, of scales; the weight of the example and the
// common bias, of its point's pair's own weights (nf a slot)
extern "C" __global__ void params_of(int k, const float* scales, const float* pair, int nf, float* params) {
    int s = blockIdx.x * blockDim.x + threadIdx.x;
    if (s >= k) return;
    params[3 * s] = scales[s];
    params[3 * s + 1] = pair[(size_t) s * nf];
    params[3 * s + 2] = pair[(size_t) s * nf + 1];
}

// The block's sum of one number added into *sum
__device__ void slot_sum(double x, double* sum) {
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

// The penalty of every slot of the first k on its point (p…), its slope added to its gradient (g…):
// θ·θ summed into penalties[s] and 2λθ added — of every weight but the common bias, the pair's own
// second weight, the base rate of every value
extern "C" __global__ void penalty(
    int k, int size, const float* lambda,
    float* pa, float* pb, float* pc, float* pd, float* pf,
    float* ga, float* gb, float* gc, float* gd, float* gf, int na, int nb, int nc, int nd, int nf, double* penalties
) {
    int s = blockIdx.x / PER_SLOT;
    if (s >= k) return;
    float l2 = 2.0f * lambda[s];
    int common = size - nf + 1;
    double sum = 0.0;
    for (int e = (blockIdx.x % PER_SLOT) * BLOCK + threadIdx.x; e < size; e += PER_SLOT * BLOCK) {
        if (e == common) continue;
        float v = *piece_of(e, s, pa, pb, pc, pd, pf, na, nb, nc, nd, nf);
        *piece_of(e, s, ga, gb, gc, gd, gf, na, nb, nc, nd, nf) += l2 * v;
        sum += (double) v * v;
    }
    slot_sum(sum, penalties + s);
}

// Of every slot of the first k, the pair's own slopes of its sums (the loss, the slopes of the common
// bias and of the example, three a slot): the example's first, nf a slot — before the penalty adds to them
extern "C" __global__ void pair_slopes(int k, const double* sums, int nf, float* gPair) {
    int s = blockIdx.x * blockDim.x + threadIdx.x;
    if (s >= k) return;
    gPair[(size_t) s * nf] = (float) sums[3 * s + 2];
    gPair[(size_t) s * nf + 1] = (float) sums[3 * s + 1];
}

// Of every slot of the first k, its loss: the pairs' and the names' over its training's weight
// (inverse[s] one over it), with its penalty
extern "C" __global__ void losses_of(
    int k, const double* sums, const float* inverse, const float* lambda, const double* penalties, float* losses
) {
    int s = blockIdx.x * blockDim.x + threadIdx.x;
    if (s >= k) return;
    losses[s] = (float) (sums[3 * s] * inverse[s] + lambda[s] * penalties[s]);
}
