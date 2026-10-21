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

// Every pair that weighs anything — an entry: its item, its value, weight, label, whether the site
// gives it as an example, how much the item's texts name it — of the items outside the held-out fold:
// its score S[item, value] (U Vᵀ, made before) with the bias, the common bias, the example and the
// mention; its slope written into G (nought elsewhere), its loss and slopes summed
extern "C" __global__ void pairs(
    long long entries, int m, const float* S, float* G,
    const int* item, const int* value, const float* weight, const float* label, const float* exampleOf, const float* mentionOf,
    const int* foldOf, int heldOut, const float* bias, float common, float example, float mention, float scale, double* sums
) {
    long long k = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    double l = 0.0, dCommon = 0.0, dExample = 0.0, dMention = 0.0;
    if (k < entries) {
        int i = item[k];
        if (foldOf[i] != heldOut) {
            int j = value[k];
            size_t at = (size_t) i * m + j;
            float ex = exampleOf[k];
            float me = mentionOf[k];
            float z = S[at] + bias[j] + common + example * ex + mention * me;
            float loss, slope;
            logistic(z, label[k], weight[k], scale, &loss, &slope);
            G[at] = slope;
            l = loss;
            dCommon = slope;
            dExample = slope * ex;
            dMention = slope * me;
        }
    }
    block_sums(l, dCommon, dExample, dMention, sums);
}

// The group of a pair of a name and a value: the last whose first pair is at k or before
__device__ __forceinline__ int group_of(long long k, const long long* first, int groups) {
    int lo = 0, hi = groups - 1;
    while (lo < hi) {
        int mid = (lo + hi + 1) / 2;
        if (first[mid] <= k) lo = mid; else hi = mid - 1;
    }
    return lo;
}

// Every name of a group against every value of it, the label nought but of the name's own value,
// which the text names: the score Uh·V with the bias and the common bias, the slope written into Sn
// (the group's names × its values, row after row, one group after another), the loss and slopes summed
extern "C" __global__ void names_loss(
    long long pairs, int groups, const long long* firstPair, const int* firstRow, const int* firstValue, const int* width,
    const int* rows, const int* values, const int* positive,
    const float* uh, int h, const float* v, int m, int r,
    const float* bias, float common, float mention, float named, float scale, float* Sn, double* sums
) {
    long long k = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    double l = 0.0, dCommon = 0.0, dMention = 0.0;
    if (k < pairs) {
        int g = group_of(k, firstPair, groups);
        long long local = k - firstPair[g];
        int c = width[g];
        int row = (int) (local / c);
        int col = (int) (local % c);
        int name = rows[firstRow[g] + row];
        int val = values[firstValue[g] + col];
        float z = bias[val] + common;
        for (int q = 0; q < r; q++) z += uh[(size_t) q * h + name] * v[(size_t) q * m + val];
        float loss, slope;
        if (positive[firstRow[g] + row] == col) {
            logistic(z + mention * named, 1.0f, 1.0f, scale, &loss, &slope);
            dMention = slope * named;
        } else {
            logistic(z, 0.0f, 1.0f, scale, &loss, &slope);
        }
        Sn[k] = slope;
        l = loss;
        dCommon = slope;
    }
    block_sums(l, dCommon, 0.0, dMention, sums);
}

#define MAX_RANK 64

// Of every value of every group — one thread each — its slopes over the group's names carried to the
// value's coordinates (dVᵀ += Uhᵀ Sn) and its bias (the column summed)
extern "C" __global__ void names_values(
    int columns, const int* groupOfValue, const long long* firstPair, const int* firstRow, const int* firstValue, const int* width,
    const int* height, const int* rows, const int* values, const float* Sn,
    const float* uh, int h, int m, int r, float* gE, float* gBias
) {
    int k = blockIdx.x * blockDim.x + threadIdx.x;
    if (k >= columns) return;
    int g = groupOfValue[k];
    int col = k - firstValue[g];
    int c = width[g];
    int kRows = height[g];
    int val = values[k];
    const float* s = Sn + firstPair[g] + col;
    float acc[MAX_RANK];
    for (int q = 0; q < r; q++) acc[q] = 0.0f;
    float sum = 0.0f;
    for (int row = 0; row < kRows; row++) {
        float slope = s[(size_t) row * c];
        if (slope == 0.0f) continue;
        int name = rows[firstRow[g] + row];
        for (int q = 0; q < r; q++) acc[q] += uh[(size_t) q * h + name] * slope;
        sum += slope;
    }
    for (int q = 0; q < r; q++) atomicAdd(gE + (size_t) q * m + val, acc[q]);
    atomicAdd(gBias + val, sum);
}

// Of every name of every group — one thread each — its slopes over the group's values carried to
// the name's coordinates (dUhᵀ = Vᵀ Snᵀ): a name is of one group alone
extern "C" __global__ void names_rows(
    int names, const int* groupOfRow, const long long* firstPair, const int* firstRow, const int* firstValue, const int* width,
    const int* rows, const int* values, const float* Sn,
    const float* v, int h, int m, int r, float* duh
) {
    int k = blockIdx.x * blockDim.x + threadIdx.x;
    if (k >= names) return;
    int g = groupOfRow[k];
    int row = k - firstRow[g];
    int c = width[g];
    int name = rows[k];
    const float* s = Sn + firstPair[g] + (long long) row * c;
    const int* own = values + firstValue[g];
    float acc[MAX_RANK];
    for (int q = 0; q < r; q++) acc[q] = 0.0f;
    for (int col = 0; col < c; col++) {
        float slope = s[col];
        int val = own[col];
        for (int q = 0; q < r; q++) acc[q] += v[(size_t) q * m + val] * slope;
    }
    for (int q = 0; q < r; q++) duh[(size_t) q * h + name] += acc[q];
}
