// THE SEARCH OF PAIRS ON THE CARD (CardSearch): a block of rows of one site's works against every work of another.
// The cosines of their titles and descriptions are cuBLAS's products, made before; here the facets their values share,
// through the other site's works of every value (facets_onto); the works of one author (authors_onto); and every
// model's score of every pair, the likeliest work of every model kept of every row (best) — every model in the same
// pass over the pairs.

#define BLOCK 256
#define MAX_MODELS 4

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

// Of every row r — the work from + r of this site, a block of threads a row — every value k of its (rowAt[i] ..
// rowAt[i + 1] of values, of weights wa) through the other site's works of it (at[v] .. at[v + 1] of items, of weights
// wb): the product of the two weights added at V[family][r][j]. Every entry of every list of a row one thread's: cum[k]
// where the k-th value's list begins among the row's, total[i] how many there are
extern "C" __global__ void facets_onto(
    int rows, int from, const int* rowAt, const int* values, const float* wa, const int* cum, const int* total,
    const int* at, const int* items, const float* wb, const int* familyOf, int n, float* V
) {
    int r = blockIdx.x;
    if (r >= rows) return;
    int i = from + r;
    int s = rowAt[i], e = rowAt[i + 1];
    if (s == e) return;
    int count = total[i];
    for (int idx = threadIdx.x; idx < count; idx += blockDim.x) {
        int k = list_of(cum, s, e, idx);
        int v = values[k];
        int q = at[v] + idx - cum[k];
        int j = items[q];
        atomicAdd(V + ((size_t) familyOf[v] * rows + r) * n + j, wa[k] * wb[q]);
    }
}

// Of every row r, the works of the other site of an author one with the row's (its authors and their kin, kinAt[i] ..
// kinAt[i + 1] of kin: the places of those authors among the other site's, whose works are authorAt[a] .. authorAt[a + 1]
// of authorItems) marked at S[r][j]; kinCum and kinTotal as cum and total of facets_onto
extern "C" __global__ void authors_onto(
    int rows, int from, const int* kinAt, const int* kin, const int* kinCum, const int* kinTotal,
    const int* authorAt, const int* authorItems, int n, unsigned char* S
) {
    int r = blockIdx.x;
    if (r >= rows) return;
    int i = from + r;
    int s = kinAt[i], e = kinAt[i + 1];
    if (s == e) return;
    int count = kinTotal[i];
    for (int idx = threadIdx.x; idx < count; idx += blockDim.x) {
        int k = list_of(kinCum, s, e, idx);
        int j = authorItems[authorAt[kin[k]] + idx - kinCum[k]];
        S[(size_t) r * n + j] = 1;
    }
}

// A score of the model of the numbers m (Logistic.Laid.flat: the mean's mark, the constant, the title, the description
// and that both have one, the size and that both have it, one author, then of every facet its weight and that both have
// it) of a pair of these layers: of a learnt model its logit, else the mean of the layers both have
__device__ float score_of(
    const float* m, int families, float t, float d, bool hasD, float s, bool hasS, unsigned long long both,
    const float* V, size_t at, size_t stride, bool same
) {
    if (m[0] != 0.0f) {
        float sum = t;
        int layers = 1;
        if (hasD) { sum += d; layers++; }
        if (hasS) { sum += s; layers++; }
        if (both != 0ULL) {
            float f = 0.0f;
            int c = 0;
            unsigned long long bits = both;
            while (bits != 0ULL) {
                int k = __ffsll((long long) bits) - 1;
                f += V[k * stride + at];
                c++;
                bits &= bits - 1ULL;
            }
            sum += f / c;
            layers++;
        }
        return sum / layers;
    }
    float z = m[1] + m[2] * t;
    if (hasD) z += m[3] * d + m[4];
    if (hasS) z += m[5] * s + m[6];
    if (same) z += m[7];
    unsigned long long bits = both;
    while (bits != 0ULL) {
        int k = __ffsll((long long) bits) - 1;
        z += m[8 + 2 * k] * V[k * stride + at] + m[9 + 2 * k];
        bits &= bits - 1ULL;
    }
    return z;
}

// Of every row r — a block a row — of every work j of the other site but those the user answered on (passAt[i] ..
// passAt[i + 1] of pass), the layers made of the products T and D, the facets V, the authors S, the descriptions both
// have, the sizes (of every number both state, the smaller over the larger, their mean) — and every model's score of
// them: of every model the work of the highest score, of equal ones the first, written at best[r][model] (-1: none)
extern "C" __global__ void best(
    int rows, int from, int n, int nA, const float* T, const float* D, const float* V, const unsigned char* S,
    const unsigned char* describedA, const unsigned char* describedB, const unsigned long long* facetsA,
    const unsigned long long* facetsB, int sizes, const float* sizesA, const float* sizesB,
    const int* passAt, const int* pass, int models, const float* weights, int families, int* best
) {
    __shared__ float bestScore[MAX_MODELS][BLOCK];
    __shared__ int bestAt[MAX_MODELS][BLOCK];
    int r = blockIdx.x;
    if (r >= rows) return;
    int i = from + r;
    int stride = 8 + 2 * families;
    float own[MAX_MODELS];
    int at[MAX_MODELS];
    for (int m = 0; m < models; m++) { own[m] = -__int_as_float(0x7f800000); at[m] = -1; }
    bool describedOwn = describedA[i] != 0;
    unsigned long long facetsOwn = facetsA[i];
    size_t plane = (size_t) rows * n;
    for (int j = threadIdx.x; j < n; j += blockDim.x) {
        bool passed = false;
        for (int p = passAt[i]; p < passAt[i + 1]; p++) if (pass[p] == j) { passed = true; break; }
        if (passed) continue;
        size_t c = (size_t) r * n + j;
        float sum = 0.0f;
        int known = 0;
        for (int k = 0; k < sizes; k++) {
            float u = sizesA[(size_t) k * nA + i];
            float v = sizesB[(size_t) k * n + j];
            if (u > 0.0f && v > 0.0f) { sum += fminf(u, v) / fmaxf(u, v); known++; }
        }
        bool hasS = known > 0;
        float s = hasS ? sum / known : 0.0f;
        bool hasD = describedOwn && describedB[j] != 0;
        unsigned long long both = facetsOwn & facetsB[j];
        bool same = S[c] != 0;
        for (int m = 0; m < models; m++) {
            float z = score_of(weights + (size_t) m * stride, families, T[c], D[c], hasD, s, hasS, both, V, c, plane, same);
            if (z > own[m]) { own[m] = z; at[m] = j; }
        }
    }
    for (int m = 0; m < models; m++) { bestScore[m][threadIdx.x] = own[m]; bestAt[m][threadIdx.x] = at[m]; }
    __syncthreads();
    for (int half = blockDim.x / 2; half > 0; half >>= 1) {
        if (threadIdx.x < half) {
            for (int m = 0; m < models; m++) {
                float a = bestScore[m][threadIdx.x], b = bestScore[m][threadIdx.x + half];
                int ja = bestAt[m][threadIdx.x], jb = bestAt[m][threadIdx.x + half];
                if (jb >= 0 && (ja < 0 || b > a || b == a && jb < ja)) {
                    bestScore[m][threadIdx.x] = b;
                    bestAt[m][threadIdx.x] = jb;
                }
            }
        }
        __syncthreads();
    }
    if (threadIdx.x == 0) for (int m = 0; m < models; m++) best[(size_t) r * models + m] = bestAt[m][0];
}
