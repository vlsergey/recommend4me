// THE SEARCH OF PAIRS ON THE CARD (CardSearch): a block of rows of one site's works against every work of another.
// The cosines of their titles' and authors' trigrams and of their descriptions are cuBLAS's products, made before in
// half precision. The facets a pair shares add to a model's score as the sum, over the values shared, of the product of
// the two weights times the model's weight of the value's facet: of the values few works have (rare), through the
// other site's works of each value, into a plane a model (facets_onto); of those many works have (frequent), in the
// kernel of the scores (best), the other work's short list of them looked up by their numbers among the frequent in
// the rows' — never a value of every pair walked. Whether a pair is of one author, there too, of the two works' lists.
// Every model's score of every pair, the likeliest work of every model kept of every row (best), every model in the
// same pass; a thread's work of the other site read once for ROWS rows.

#define BLOCK 256
#define MAX_MODELS 4

// The rows of a block of best: a thread reads a work of the other site once for them all
#define ROWS 8
// The frequent values of a site at most (Frequent.MOST)
#define FREQUENT 512
// A row's authors and their kin held by its block, at most
#define KIN 64
// The numbers of size of a pair at most (PairSearch.sizes)
#define SIZES 8

// A number of half precision, as cuBLAS wrote it, in single precision
__device__ __forceinline__ float half_to_float(unsigned short h) {
    float f;
    asm("cvt.f32.f16 %0, %1;" : "=f"(f) : "h"(h));
    return f;
}

// Of the row's lists one after another (cum[k] where the k-th begins among them, [from, to) the row's), the list the
// idx-th entry is in: the last k whose cum is not past idx
__device__ __forceinline__ int list_of(const int* cum, int from, int to, int idx) {
    int lo = from, hi = to - 1;
    while (lo < hi) {
        int mid = (lo + hi + 1) >> 1;
        if (cum[mid] <= idx) lo = mid; else hi = mid - 1;
    }
    return lo;
}

// The weight of the facet f in the model of the numbers m (Logistic.Laid.flat): of the mean of the layers, one — its
// facets' cosines summed, then divided by how many facets both have
__device__ __forceinline__ float facet_weight(const float* m, int f) {
    return m[0] != 0.0f ? 1.0f : m[14 + 2 * f];
}

// Of every row r — the work from + r of this site, a block of threads a row — every rare value k of its (rowAt[i] ..
// rowAt[i + 1] of values, of weights wa) through the other site's works of it (at[v] .. at[v + 1] of items, of weights
// wb): the product of the two weights times every model's weight of the value's facet added at P[model][r][j]. Every
// entry of every list of a row one thread's: cum[k] where the k-th value's list begins among the row's, total[i] how
// many there are
extern "C" __global__ void facets_onto(
    int rows, int from, const int* rowAt, const int* values, const float* wa, const int* cum, const int* total,
    const int* at, const int* items, const float* wb, const int* familyOf, int n, int models, const float* weights,
    int stride, float* P
) {
    int r = blockIdx.x;
    if (r >= rows) return;
    int i = from + r;
    int s = rowAt[i], e = rowAt[i + 1];
    if (s == e) return;
    int count = total[i];
    size_t plane = (size_t) rows * n;
    for (int idx = threadIdx.x; idx < count; idx += blockDim.x) {
        int k = list_of(cum, s, e, idx);
        int v = values[k];
        int q = at[v] + idx - cum[k];
        int j = items[q];
        float product = wa[k] * wb[q];
        int f = familyOf[v];
        for (int m = 0; m < models; m++)
            atomicAdd(P + m * plane + (size_t) r * n + j, facet_weight(weights + (size_t) m * stride, f) * product);
    }
}

// The share of the trigrams of a string of length own the other, of length other, has, of the cosine c of the two
// (Lexical.share): at most one
__device__ __forceinline__ float share(float c, float own, float other) {
    return own <= 0.0f ? 0.0f : fminf(fmaxf(c * other / own, 0.0f), 1.0f);
}

// The highest of two (score, work) of the threads of a warp, of equal scores the first work; none (-1) the lowest
__device__ __forceinline__ void keep_higher(float& s, int& j, float t, int k) {
    if (k >= 0 && (j < 0 || t > s || t == s && k < j)) { s = t; j = k; }
}

// Of every block of ROWS rows — the works from + block * ROWS + r of this site — of every work j of the other site but
// those the user answered on (passAt[i] .. passAt[i + 1] of pass), the layers: the cosines of the titles T, of the
// authors' names A and of the descriptions D (half precision, a row of n a row of the rows); what the rare values they
// share add to every model's score (P, a plane a model, of facets_onto) and the frequent — the rows' weights of every
// frequent value of the site by its number (rowAt[i] .. rowAt[i + 1] of the numbers rowValues, of weights rowWeights),
// the other's (otherAt[j] .. otherAt[j + 1] of otherValues, otherWeights) — of the facet frequentFamily[number];
// whether they are of one author — the row's authors and their kin (kinAt[i] .. kinAt[i + 1] of kin, as places among
// the other site's authors) against the other's (otherAuthorAt[j] .. of otherAuthors); the sizes (of every number both
// state, the smaller over the larger, their mean) — and every model's score of them: of every row and model the work of
// the highest score, of equal ones the first, written at best[row][model] (-1: none) with its score at
// scores[row][model]. Of one site against itself (self), a work is never its own pair
extern "C" __global__ void best(
    int rows, int from, int n, int nA, const unsigned short* T, const unsigned short* D, const unsigned short* A,
    const float* P, const float* titleA, const float* titleB, const float* authorA, const float* authorB,
    int descriptions, const unsigned char* describedA, const unsigned char* describedB,
    const unsigned long long* facetsA, const unsigned long long* facetsB, int sizes, const float* sizesA,
    const float* sizesB, const int* passAt, const int* pass,
    const int* rowAt, const int* rowValues, const float* rowWeights, const int* kinAt, const int* kin,
    const int* otherAt, const int* otherValues, const float* otherWeights,
    const int* otherAuthorAt, const int* otherAuthors, int frequent, const int* frequentFamily,
    int models, const float* weights, int families, int self, int* best, float* scores
) {
    __shared__ float held[ROWS][FREQUENT];
    __shared__ float facetOf[MAX_MODELS][FREQUENT];
    __shared__ int ownKin[ROWS][KIN];
    __shared__ float warpScore[BLOCK / 32];
    __shared__ int warpAt[BLOCK / 32];
    int first = blockIdx.x * ROWS;
    if (first >= rows) return;
    int count = min(ROWS, rows - first);
    int stride = 14 + 2 * families;

    // The rows' weights of every frequent value by its number, every model's weight of its facet, the rows' kin
    for (int k = threadIdx.x; k < ROWS * FREQUENT; k += blockDim.x) held[k / FREQUENT][k % FREQUENT] = 0.0f;
    for (int k = threadIdx.x; k < MAX_MODELS * FREQUENT; k += blockDim.x) {
        int m = k / FREQUENT, q = k % FREQUENT;
        facetOf[m][q] = m < models && q < frequent ? facet_weight(weights + (size_t) m * stride, frequentFamily[q]) : 0.0f;
    }
    __syncthreads();
    int kinCount[ROWS];
    #pragma unroll
    for (int r = 0; r < ROWS; r++) {
        kinCount[r] = 0;
        if (r >= count) continue;
        int i = from + first + r;
        for (int k = rowAt[i] + threadIdx.x; k < rowAt[i + 1]; k += blockDim.x) held[r][rowValues[k]] = rowWeights[k];
        kinCount[r] = kinAt[i + 1] - kinAt[i];
        for (int k = threadIdx.x; k < kinCount[r]; k += blockDim.x) ownKin[r][k] = kin[kinAt[i] + k];
    }
    __syncthreads();

    float own[ROWS][MAX_MODELS];
    int at[ROWS][MAX_MODELS];
    #pragma unroll
    for (int r = 0; r < ROWS; r++)
        #pragma unroll
        for (int m = 0; m < MAX_MODELS; m++) { own[r][m] = -__int_as_float(0x7f800000); at[r][m] = -1; }
    size_t plane = (size_t) rows * n;
    for (int j = threadIdx.x; j < n; j += blockDim.x) {
        // The other work, read once for every row
        float tB = titleB[j];
        float aB = authorB[j];
        bool describedOther = descriptions != 0 && describedB[j] != 0;
        unsigned long long facetsOther = facetsB[j];
        float sizeOther[SIZES];
        for (int k = 0; k < sizes; k++) sizeOther[k] = sizesB[(size_t) k * n + j];
        int valuesFrom = otherAt[j], valuesTo = otherAt[j + 1];
        int authorsFrom = otherAuthorAt[j], authorsTo = otherAuthorAt[j + 1];
        #pragma unroll
        for (int r = 0; r < ROWS; r++) {
            if (r >= count) break;
            int row = first + r;
            int i = from + row;
            if (self && j == i) continue;
            bool passed = false;
            for (int p = passAt[i]; p < passAt[i + 1]; p++) if (pass[p] == j) { passed = true; break; }
            if (passed) continue;
            size_t c = (size_t) row * n + j;
            float sum = 0.0f;
            int known = 0;
            for (int k = 0; k < sizes; k++) {
                float u = sizesA[(size_t) k * nA + i];
                float v = sizeOther[k];
                if (u > 0.0f && v > 0.0f) { sum += fminf(u, v) / fmaxf(u, v); known++; }
            }
            bool hasS = known > 0;
            float s = hasS ? sum / known : 0.0f;
            bool hasD = describedOther && describedA[i] != 0;
            unsigned long long both = facetsA[i] & facetsOther;
            // What the values shared add to every model: the rare of facets_onto, the frequent by their numbers
            float f[MAX_MODELS];
            #pragma unroll
            for (int m = 0; m < MAX_MODELS; m++) f[m] = m < models ? P[m * plane + c] : 0.0f;
            if (both != 0ULL) {
                for (int q = valuesFrom; q < valuesTo; q++) {
                    int v = otherValues[q];
                    float product = held[r][v] * otherWeights[q];
                    if (product != 0.0f) {
                        #pragma unroll
                        for (int m = 0; m < MAX_MODELS; m++) f[m] += facetOf[m][v] * product;
                    }
                }
            }
            bool same = false;
            for (int q = authorsFrom; q < authorsTo && !same; q++) {
                int author = otherAuthors[q];
                for (int k = 0; k < kinCount[r]; k++) if (ownKin[r][k] == author) { same = true; break; }
            }
            float t = half_to_float(T[c]);
            float a = half_to_float(A[c]);
            float d = hasD ? half_to_float(D[c]) : 0.0f;
            float tA = titleA[i];
            float aA = authorA[i];
            bool hasA = aA > 0.0f && aB > 0.0f;
            float tw = share(t, tA, tB), th = share(t, tB, tA);
            float aw = hasA ? share(a, aA, aB) : 0.0f, ah = hasA ? share(a, aB, aA) : 0.0f;
            int layersBoth = __popcll(both);
            #pragma unroll
            for (int m = 0; m < MAX_MODELS; m++) {
                if (m >= models) break;
                const float* w = weights + (size_t) m * stride;
                float z;
                if (w[0] != 0.0f) {
                    // The mean of the layers both have
                    float total = t;
                    int layers = 1;
                    if (hasA) { total += a; layers++; }
                    if (hasD) { total += d; layers++; }
                    if (hasS) { total += s; layers++; }
                    if (layersBoth > 0) { total += f[m] / layersBoth; layers++; }
                    z = total / layers;
                } else {
                    z = w[1] + w[2] * t + w[8] * tw + w[9] * th + f[m];
                    if (hasA) z += w[10] * a + w[11] + w[12] * aw + w[13] * ah;
                    if (hasD) z += w[3] * d + w[4];
                    if (hasS) z += w[5] * s + w[6];
                    if (same) z += w[7];
                    unsigned long long bits = both;
                    while (bits != 0ULL) {
                        int k = __ffsll((long long) bits) - 1;
                        z += w[15 + 2 * k];
                        bits &= bits - 1ULL;
                    }
                }
                if (z > own[r][m]) { own[r][m] = z; at[r][m] = j; }
            }
        }
    }
    // Of every row and model, the highest of the block's threads: of a warp by its lanes, then of the warps
    int lane = threadIdx.x & 31, warp = threadIdx.x >> 5;
    #pragma unroll
    for (int r = 0; r < ROWS; r++) {
        if (r >= count) break;
        #pragma unroll
        for (int m = 0; m < MAX_MODELS; m++) {
        if (m >= models) break;
        float s = own[r][m];
        int j = at[r][m];
        for (int offset = 16; offset > 0; offset >>= 1)
            keep_higher(s, j, __shfl_down_sync(0xffffffff, s, offset), __shfl_down_sync(0xffffffff, j, offset));
        if (lane == 0) { warpScore[warp] = s; warpAt[warp] = j; }
        __syncthreads();
        if (threadIdx.x == 0) {
            float bs = warpScore[0];
            int bj = warpAt[0];
            for (int w = 1; w < BLOCK / 32; w++) keep_higher(bs, bj, warpScore[w], warpAt[w]);
            best[(size_t) (first + r) * models + m] = bj;
            scores[(size_t) (first + r) * models + m] = bs;
        }
        __syncthreads();
        }
    }
}
