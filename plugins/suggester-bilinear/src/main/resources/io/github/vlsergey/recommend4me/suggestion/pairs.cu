// THE PAIRS AND THE NAMES OF A FITTING ON THE CARD: what Ensemble.pairs and Ensemble.names work out on
// the processor between their products, the products themselves left to cuBLAS. Every model's
// numbers as there: its items' coordinates Uᵀ (rank × items), its values' Vᵀ (rank × values), its
// names' Uhᵀ (rank × names), row after row. The sums of a launch — the loss, the slopes of the
// common bias, of the example and of the mention — gathered by a block and added in double.

// The logistic loss of z against the label y, weighed by w, and its slope scaled: softplus and the
// sigmoid of one exponent, as the processor's (Simd.logistic)
__device__ __forceinline__ void logistic(float z, float y, float w, float scale, float* loss, float* slope) {
    float e = expf(-fabsf(z));
    float inverse = 1.0f / (1.0f + e);
    float sigmoid = z >= 0.0f ? inverse : e * inverse;
    *loss = w * (fmaxf(z, 0.0f) + log1pf(e) - y * z);
    *slope = w * (sigmoid - y) * scale;
}

#define BLOCK 256

// The names of a group a block of names_tile takes
#define TILE_ROWS 32

// The block's four sums added into sums[0..3]
__device__ void block_sums(double a, double b, double c, double d, double* sums) {
    __shared__ double s[4][BLOCK];
    int t = threadIdx.x;
    s[0][t] = a;
    s[1][t] = b;
    s[2][t] = c;
    s[3][t] = d;
    __syncthreads();
    for (int half = BLOCK / 2; half > 0; half >>= 1) {
        if (t < half) {
            s[0][t] += s[0][t + half];
            s[1][t] += s[1][t + half];
            s[2][t] += s[2][t + half];
            s[3][t] += s[3][t + half];
        }
        __syncthreads();
    }
    if (t == 0) {
        atomicAdd(sums, s[0][0]);
        atomicAdd(sums + 1, s[1][0]);
        atomicAdd(sums + 2, s[2][0]);
        atomicAdd(sums + 3, s[3][0]);
    }
}

// Whether the item i may have the value j: its array of the values it may have, as a mask of a bit
// a value — group[i] the array's, -1 any value
__device__ __forceinline__ bool may(const int* group, const unsigned int* masks, int words, int i, int j) {
    int g = group[i];
    return g < 0 || ((masks[(size_t) g * words + (j >> 5)] >> (j & 31)) & 1u);
}

// Every pair of a block of rows — the items from `from` on, against every value — as a value the item
// merely lacks: of the items outside the held-out fold that have any value, every value they may
// have, weighing lack[i], labelled nought. The score S (U Vᵀ of the block, made before) with the
// bias and the common bias; the slope written into G (nought elsewhere), the loss and slope summed.
// The item's own pairs are set right after (owns)
extern "C" __global__ void lacks(
    int rows, int m, int from, const float* S, float* G, const float* lack, const int* group, const unsigned int* masks, int words,
    const int* foldOf, int heldOut, const float* bias, float common, float scale, double* sums
) {
    long long k = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    double l = 0.0, dCommon = 0.0;
    if (k < (long long) rows * m) {
        int i = from + (int) (k / m);
        int j = (int) (k % m);
        float w = lack[i];
        if (w > 0.0f && foldOf[i] != heldOut && may(group, masks, words, i, j)) {
            float loss, slope;
            logistic(S[k] + bias[j] + common, 0.0f, w, scale, &loss, &slope);
            G[k] = slope;
            l = loss;
            dCommon = slope;
        }
    }
    block_sums(l, dCommon, 0.0, 0.0, sums);
}

// Every pair of the block's items not merely lacked — an entry: its item, its value, weight (nought:
// no pair), label, whether the site gives it as an example, how much the item's texts name it —
// of the items outside the held-out fold: what lacks took it for, if anything, taken back — the same
// score, the same numbers — and the pair as it is, with the example and the mention; its slope
// written into G over the lacking one, its loss and slopes summed
extern "C" __global__ void owns(
    int count, int m, int from, const float* S, float* G,
    const int* item, const int* value, const float* weight, const float* label, const float* exampleOf, const float* mentionOf,
    const float* lack, const int* group, const unsigned int* masks, int words,
    const int* foldOf, int heldOut, const float* bias, float common, float example, float mention, float scale, double* sums
) {
    int k = blockIdx.x * blockDim.x + threadIdx.x;
    double l = 0.0, dCommon = 0.0, dExample = 0.0, dMention = 0.0;
    if (k < count) {
        int i = item[k];
        if (foldOf[i] != heldOut) {
            int j = value[k];
            size_t at = (size_t) (i - from) * m + j;
            float z = S[at] + bias[j] + common;
            float lacking = lack[i];
            float loss, slope;
            if (lacking > 0.0f && may(group, masks, words, i, j)) {
                logistic(z, 0.0f, lacking, scale, &loss, &slope);
                l -= loss;
                dCommon -= slope;
            }
            float w = weight[k];
            slope = 0.0f;
            if (w > 0.0f) {
                float ex = exampleOf[k];
                float me = mentionOf[k];
                logistic(z + example * ex + mention * me, label[k], w, scale, &loss, &slope);
                l += loss;
                dCommon += slope;
                dExample = slope * ex;
                dMention = slope * me;
            }
            G[at] = slope;
        }
    }
    block_sums(l, dCommon, dExample, dMention, sums);
}

// The coordinates of every name and of every value of every group gathered where the groups read
// them: gu the groups' names one after another, gv their values, a row of rank numbers each — read
// by neighbouring threads side by side, not scattered over the rank's rows of every name and value
extern "C" __global__ void gather(int count, const int* index, const float* from, int stride, int r, float* into) {
    long long k = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    if (k >= (long long) count * r) return;
    int at = (int) (k / r);
    int q = (int) (k % r);
    into[k] = from[(size_t) q * stride + index[at]];
}

#define MAX_RANK 64

// A tile of a group, a block each: up to TILE_ROWS of its names against up to BLOCK of its values,
// a thread a value. Of every pair, the label nought but of the name's own value, which the text
// names: the score Uh·V with the bias and the common bias, the slope written into Sn (the group's
// names × its values, row after row, one group after another); the loss and slopes summed, and the
// value's slopes over the tile's names carried at once to its coordinates (dVᵀ += Uhᵀ Sn) and its bias
// R the rank when the compiler knows it, the numbers of a value and of its slopes held in registers;
// nought when only r does, held in MAX_RANK of local memory
template <int R>
__device__ void names_tile_of(
    const int* tiles, const long long* firstPair, const int* firstRow, const int* firstValue, const int* width, const int* height,
    const int* values, const int* positive, const float* gu, const float* gv, int m, int r,
    const float* bias, float common, float mention, float named, float scale, float* Sn, float* gE, float* gBias, double* sums
) {
    if (R > 0) r = R;
    const int* tile = tiles + 3 * blockIdx.x;
    int g = tile[0];
    int rowFrom = tile[1];
    int col = tile[2] + threadIdx.x;
    int c = width[g];
    int rowTo = min(rowFrom + TILE_ROWS, height[g]);
    double l = 0.0, dCommon = 0.0, dMention = 0.0;
    if (col < c) {
        int at = firstValue[g] + col;
        int val = values[at];
        float b[R > 0 ? R : MAX_RANK];
        for (int q = 0; q < r; q++) b[q] = gv[(size_t) at * r + q];
        float base = bias[val] + common;
        float acc[R > 0 ? R : MAX_RANK];
        for (int q = 0; q < r; q++) acc[q] = 0.0f;
        float sum = 0.0f;
        float* s = Sn + firstPair[g] + col;
        for (int row = rowFrom; row < rowTo; row++) {
            int name = firstRow[g] + row;
            const float* a = gu + (size_t) name * r;
            float z = base;
            for (int q = 0; q < r; q++) z += a[q] * b[q];
            float loss, slope;
            if (positive[name] == col) {
                logistic(z + mention * named, 1.0f, 1.0f, scale, &loss, &slope);
                dMention += slope * named;
            } else {
                logistic(z, 0.0f, 1.0f, scale, &loss, &slope);
            }
            s[(size_t) row * c] = slope;
            l += loss;
            dCommon += slope;
            for (int q = 0; q < r; q++) acc[q] += a[q] * slope;
            sum += slope;
        }
        for (int q = 0; q < r; q++) atomicAdd(gE + (size_t) q * m + val, acc[q]);
        atomicAdd(gBias + val, sum);
    }
    block_sums(l, dCommon, 0.0, dMention, sums);
}

#define NAMES_TILE_PARAMETERS     const int* tiles, const long long* firstPair, const int* firstRow, const int* firstValue, const int* width, const int* height,     const int* values, const int* positive, const float* gu, const float* gv, int m, int r,     const float* bias, float common, float mention, float named, float scale, float* Sn, float* gE, float* gBias, double* sums
#define NAMES_TILE_ARGUMENTS tiles, firstPair, firstRow, firstValue, width, height, values, positive, gu, gv, m, r,     bias, common, mention, named, scale, Sn, gE, gBias, sums

extern "C" __global__ void names_tile(NAMES_TILE_PARAMETERS) { names_tile_of<0>(NAMES_TILE_ARGUMENTS); }
extern "C" __global__ void names_tile_16(NAMES_TILE_PARAMETERS) { names_tile_of<16>(NAMES_TILE_ARGUMENTS); }

// Of every name of every group — a warp each, its lanes taking the values in turn — its slopes over
// the group's values carried to the name's coordinates (dUhᵀ = Vᵀ Snᵀ): a name is of one group alone
template <int R>
__device__ void names_rows_of(
    int names, const int* groupOfRow, const long long* firstPair, const int* firstRow, const int* firstValue, const int* width,
    const int* rows, const float* Sn, const float* gv, int h, int r, float* duh
) {
    if (R > 0) r = R;
    long long thread = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    int k = (int) (thread / 32);
    int lane = threadIdx.x % 32;
    if (k >= names) return;
    int g = groupOfRow[k];
    int c = width[g];
    const float* s = Sn + firstPair[g] + (long long) (k - firstRow[g]) * c;
    const float* b = gv + (size_t) firstValue[g] * r;
    float acc[R > 0 ? R : MAX_RANK];
    for (int q = 0; q < r; q++) acc[q] = 0.0f;
    for (int col = lane; col < c; col += 32) {
        float slope = s[col];
        const float* bc = b + (size_t) col * r;
        for (int q = 0; q < r; q++) acc[q] += bc[q] * slope;
    }
    for (int q = 0; q < r; q++) {
        float v = acc[q];
        for (int offset = 16; offset > 0; offset >>= 1) v += __shfl_down_sync(0xffffffff, v, offset);
        if (lane == 0) duh[(size_t) q * h + rows[k]] += v;
    }
}

#define NAMES_ROWS_PARAMETERS     int names, const int* groupOfRow, const long long* firstPair, const int* firstRow, const int* firstValue, const int* width,     const int* rows, const float* Sn, const float* gv, int h, int r, float* duh
#define NAMES_ROWS_ARGUMENTS names, groupOfRow, firstPair, firstRow, firstValue, width, rows, Sn, gv, h, r, duh

extern "C" __global__ void names_rows(NAMES_ROWS_PARAMETERS) { names_rows_of<0>(NAMES_ROWS_ARGUMENTS); }
extern "C" __global__ void names_rows_16(NAMES_ROWS_PARAMETERS) { names_rows_of<16>(NAMES_ROWS_ARGUMENTS); }
