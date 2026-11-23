// THE GRAPH OF THE LIKELIEST (GraphSearch): every work of every site, one numbering. Two works are as near as a model
// finds them likely, by everything a search compares them by (pair_scores): of every work, of every model its share of
// the K neighbours, the likeliest by that model, found by descent (descend: of a work's neighbours' neighbours); of
// every work its neighbours, theirs and its other candidates then scored by every model (scored), the likeliest of
// every model kept. Both kernels bounded to fill a multiprocessor of the card (BLOCKS_A_MULTIPROCESSOR, given as they
// are compiled: CudaKernels.blocks), their registers fitted to it: waiting for the works' rows, a multiprocessor of
// more blocks has more to go on with.

#define BLOCK 256
#define MAX_MODELS 4
// The neighbours of a work in the graph
#define K 16
// The candidates of a work in a step of the descent: its neighbours and theirs
#define CANDIDATES (K + K * K)

// What a pair is scored by, of every work (see pair_scores)
// The rows kept small (AllOnCard, owner, 2026-11-20: every work's rows whole ran the card out of its memory): the
// trigrams of the short texts sparse — of a work its entries tAt[u] .. tAt[u + 1] of tE, each its column in the lower
// two bytes and its half in the higher, by their columns ascending —, the vectors a byte a number with a scale a work
// (D and dS, V and vS), the description's trigrams whole halves (L)
#define WORKS_PARAMS \
    int n, const int* siteOf, int sites, const unsigned char* comparedD, int width, int described, \
    const int* tAt, const unsigned int* tE, const int* aAt, const unsigned int* aE, \
    const signed char* D, const float* dS, const float* tl, const float* al, \
    const unsigned char* hasD, const unsigned long long* facets, int sizes, const float* sizeOf, \
    const int* valueAt, const int* values, const float* valueWeights, const int* familyOf, \
    const int* kinAt, const int* kin, const int* authorAt, const int* authors, \
    const unsigned char* comparedV, int titled, const signed char* V, const float* vS, const unsigned char* hasV, \
    const unsigned short* L, const int* fAt, const unsigned int* fE, const int* cAt, const unsigned int* cE, \
    const float* ll, int models, const float* weights, int stride
#define WORKS_ARGS \
    n, siteOf, sites, comparedD, width, described, tAt, tE, aAt, aE, D, dS, tl, al, hasD, facets, sizes, sizeOf, \
    valueAt, values, valueWeights, familyOf, kinAt, kin, authorAt, authors, comparedV, titled, V, vS, hasV, L, fAt, fE, \
    cAt, cE, ll, models, weights, stride

#define NO_SCORE (-__int_as_float(0x7f800000))

// The texts compared letter by letter besides the title and the authors' names (LetteredText)
#define LETTERED 3
// The numbers of a model before its facets' (Logistic.FLAT_HEAD)
#define HEAD (17 + 2 * LETTERED)

// A number of half precision in single precision
__device__ __forceinline__ float half_to_float(unsigned short h) {
    float f;
    asm("cvt.f32.f16 %0, %1;" : "=f"(f) : "h"(h));
    return f;
}

// The share of the trigrams of a string of length own the other, of length other, has, of the cosine c of the two
__device__ __forceinline__ float share(float c, float own, float other) {
    return own <= 0.0f ? 0.0f : fminf(fmaxf(c * other / own, 0.0f), 1.0f);
}

// The product of two rows of n numbers of half precision
__device__ __forceinline__ float dot_halves(const unsigned short* a, const unsigned short* b, int n) {
    float s = 0.0f;
    for (int q = 0; q < n; q++) s += half_to_float(a[q]) * half_to_float(b[q]);
    return s;
}

// The product of the eight halves of two pieces of sixteen bytes
__device__ __forceinline__ float dot_piece(uint4 x, uint4 y) {
    unsigned int xs[4] = {x.x, x.y, x.z, x.w};
    unsigned int ys[4] = {y.x, y.y, y.z, y.w};
    float s = 0.0f;
    for (int q = 0; q < 4; q++) {
        s += half_to_float((unsigned short) (xs[q] & 0xffffu)) * half_to_float((unsigned short) (ys[q] & 0xffffu));
        s += half_to_float((unsigned short) (xs[q] >> 16)) * half_to_float((unsigned short) (ys[q] >> 16));
    }
    return s;
}

// The product of two rows of n halves by a warp, every lane of it: read together, sixteen bytes a lane, when the rows
// are of whole pieces (n of eights: their starts of sixteen bytes), else a half a lane
__device__ float warp_dot(const unsigned short* a, const unsigned short* b, int n, int lane) {
    float s = 0.0f;
    if ((n & 7) == 0) {
        const uint4* x = (const uint4*) a;
        const uint4* y = (const uint4*) b;
        for (int q = lane; q < n / 8; q += 32) s += dot_piece(x[q], y[q]);
    } else {
        for (int q = lane; q < n; q += 32) s += half_to_float(a[q]) * half_to_float(b[q]);
    }
    for (int offset = 16; offset > 0; offset >>= 1) s += __shfl_xor_sync(0xffffffff, s, offset);
    return s;
}

// The product of the sparse rows u and j (at .. of e): of the columns both have, their halves' products
__device__ float sparse_dot(const int* at, const unsigned int* e, int u, int j) {
    float s = 0.0f;
    int p = at[u], pEnd = at[u + 1], q = at[j], qEnd = at[j + 1];
    while (p < pEnd && q < qEnd) {
        unsigned int x = e[p] & 0xffffu, y = e[q] & 0xffffu;
        if (x == y) {
            s += half_to_float((unsigned short) (e[p] >> 16)) * half_to_float((unsigned short) (e[q] >> 16));
            p++;
            q++;
        } else if (x < y) p++; else q++;
    }
    return s;
}

// The same by a warp, every lane of it: the lanes share u's entries, each found among j's by halving
__device__ float warp_sparse_dot(const int* at, const unsigned int* e, int u, int j, int lane) {
    float s = 0.0f;
    int from = at[j], to = at[j + 1];
    for (int p = at[u] + lane; p < at[u + 1]; p += 32) {
        unsigned int x = e[p] & 0xffffu;
        int lo = from, hi = to;
        while (lo < hi) {
            int mid = (lo + hi) >> 1;
            if ((e[mid] & 0xffffu) < x) lo = mid + 1; else hi = mid;
        }
        if (lo < to && (e[lo] & 0xffffu) == x)
            s += half_to_float((unsigned short) (e[p] >> 16)) * half_to_float((unsigned short) (e[lo] >> 16));
    }
    for (int offset = 16; offset > 0; offset >>= 1) s += __shfl_xor_sync(0xffffffff, s, offset);
    return s;
}

// The product of two rows of n bytes, as integers, four at a time (n of fours: their starts of four bytes)
__device__ float dot_bytes(const signed char* a, const signed char* b, int n) {
    int s = 0;
    if ((n & 3) == 0) {
        const int* x = (const int*) a;
        const int* y = (const int*) b;
        for (int q = 0; q < n / 4; q++) s = __dp4a(x[q], y[q], s);
    } else {
        for (int q = 0; q < n; q++) s += a[q] * b[q];
    }
    return (float) s;
}

// The same by a warp, every lane of it
__device__ float warp_dot_bytes(const signed char* a, const signed char* b, int n, int lane) {
    int s = 0;
    if ((n & 3) == 0) {
        const int* x = (const int*) a;
        const int* y = (const int*) b;
        for (int q = lane; q < n / 4; q += 32) s = __dp4a(x[q], y[q], s);
    } else {
        for (int q = lane; q < n; q += 32) s += a[q] * b[q];
    }
    for (int offset = 16; offset > 0; offset >>= 1) s += __shfl_xor_sync(0xffffffff, s, offset);
    return (float) s;
}

// THE PAIR u, j SCORED BY EVERY MODEL as a search scores it (PairSearch.layers, Logistic.Laid.flat), to z[model]: the
// cosines of the titles (tAt, tE), of the authors' names (aAt, aE) and of the descriptions (D, dS, when the two sites'
// are of one encoder: comparedD[site][site]), the shares of the trigrams of the lengths tl, al; the other way, the
// titles' vectors (V, vS, when the two sites' are of one encoder: comparedV, and both have one: hasV) and the other
// texts' trigrams (the description's L, the fandoms' fAt, fE, the characters' cAt, cE, of the lengths ll: a text after
// another, of every work each); the sizes (of every number both
// state, the smaller over the larger, their mean); the facets they share (the values of both, valueAt .. of values
// ascending and weights, summed by the facet familyOf by the model's weight of it) and that both have (facets); one
// author (the authors of u, authorAt .. of authors, among the other's, both ascending) or one in part (the kin of u,
// kinAt .. of kin, so), none in common; the
// two sites' weights (weights[site][site][model], stride numbers a model). Of every model its chance to chance[model],
// when asked: of a learnt one the logistic of its score, of one before any answer (w[0]) the mean of the layers itself
// What the values shared add, of every model to f[model]: of the two lists, ascending, each value's product by its
// facet's weight — a model before any answer (w[0]) weighing every facet alike
__device__ void shared_values(int u, int j, const float* pairWeights, WORKS_PARAMS, float* f) {
    for (int m = 0; m < models; m++) f[m] = 0.0f;
    for (int p = valueAt[u], q = valueAt[j]; p < valueAt[u + 1] && q < valueAt[j + 1];) {
        int x = values[p], y = values[q];
        if (x == y) {
            float product = valueWeights[p] * valueWeights[q];
            for (int m = 0; m < models; m++) {
                const float* w = pairWeights + (size_t) m * stride;
                f[m] += (w[0] != 0.0f ? 1.0f : w[HEAD + 2 * familyOf[x]]) * product;
            }
            p++;
            q++;
        } else if (x < y) p++; else q++;
    }
}

// The same by a warp, every lane of it: the lanes share u's values, each found in j's by halving
__device__ void warp_shared_values(int u, int j, int lane, const float* pairWeights, WORKS_PARAMS, float* f) {
    for (int m = 0; m < models; m++) f[m] = 0.0f;
    int from = valueAt[j], to = valueAt[j + 1];
    for (int p = valueAt[u] + lane; p < valueAt[u + 1]; p += 32) {
        int x = values[p];
        int lo = from, hi = to;
        while (lo < hi) {
            int mid = (lo + hi) >> 1;
            if (values[mid] < x) lo = mid + 1; else hi = mid;
        }
        if (lo < to && values[lo] == x) {
            float product = valueWeights[p] * valueWeights[lo];
            for (int m = 0; m < models; m++) {
                const float* w = pairWeights + (size_t) m * stride;
                f[m] += (w[0] != 0.0f ? 1.0f : w[HEAD + 2 * familyOf[x]]) * product;
            }
        }
    }
    for (int m = 0; m < models; m++)
        for (int offset = 16; offset > 0; offset >>= 1) f[m] += __shfl_xor_sync(0xffffffff, f[m], offset);
}

// Of the products of the rows given — the titles' t, the authors' names' av, the descriptions' d, the other way the
// titles' vectors' v and every other text's trigrams' l — and of what the values shared add of every model, f
__device__ void pair_of(
    int u, int j, float t, float av, float d, float v, const float* l, const float* f, WORKS_PARAMS, float* z,
    float* chance
) {
    int a = siteOf[u], b = siteOf[j];
    bool hasA = al[u] > 0.0f && al[j] > 0.0f;
    bool hasDescription = comparedD[a * sites + b] != 0 && hasD[u] != 0 && hasD[j] != 0;
    float sum = 0.0f;
    int known = 0;
    for (int k = 0; k < sizes; k++) {
        float x = sizeOf[(size_t) k * n + u], y = sizeOf[(size_t) k * n + j];
        if (x > 0.0f && y > 0.0f) { sum += fminf(x, y) / fmaxf(x, y); known++; }
    }
    bool hasS = known > 0;
    float s = hasS ? sum / known : 0.0f;
    unsigned long long both = facets[u] & facets[j];
    int layersBoth = __popcll(both);
    // One author: of u's own and j's; else one in part: of u's kin and j's
    bool same = false, partly = false;
    for (int p = authorAt[u], q = authorAt[j]; p < authorAt[u + 1] && q < authorAt[j + 1] && !same;) {
        if (authors[p] == authors[q]) same = true; else if (authors[p] < authors[q]) p++; else q++;
    }
    for (int p = kinAt[u], q = authorAt[j]; !same && p < kinAt[u + 1] && q < authorAt[j + 1] && !partly;) {
        if (kin[p] == authors[q]) partly = true; else if (kin[p] < authors[q]) p++; else q++;
    }
    bool hasTitleVector = comparedV[a * sites + b] != 0 && hasV[u] != 0 && hasV[j] != 0;
    const float* pairWeights = weights + ((size_t) a * sites + b) * models * stride;
    for (int m = 0; m < models; m++) {
        const float* w = pairWeights + (size_t) m * stride;
        float score;
        bool mean = w[0] != 0.0f;
        if (mean) {
            float total = t;
            int layers = 1;
            if (hasA) { total += av; layers++; }
            if (hasDescription) { total += d; layers++; }
            if (hasS) { total += s; layers++; }
            if (layersBoth > 0) { total += f[m] / layersBoth; layers++; }
            score = total / layers;
        } else {
            score = w[1] + w[2] * t + w[8] * share(t, tl[u], tl[j]) + w[9] * share(t, tl[j], tl[u]) + f[m];
            if (hasA) score += w[10] * av + w[11] + w[12] * share(av, al[u], al[j]) + w[13] * share(av, al[j], al[u]);
            if (hasDescription) score += w[3] * d + w[4];
            if (hasS) score += w[5] * s + w[6];
            if (same) score += w[7];
            if (hasTitleVector) score += w[14] * v + w[15];
            if (partly) score += w[16];
            for (int k = 0; k < LETTERED; k++)
                if (ll[(size_t) k * n + u] > 0.0f && ll[(size_t) k * n + j] > 0.0f) score += w[17 + 2 * k] * l[k] + w[18 + 2 * k];
            unsigned long long bits = both;
            while (bits != 0ULL) {
                int k = __ffsll((long long) bits) - 1;
                score += w[HEAD + 1 + 2 * k];
                bits &= bits - 1ULL;
            }
        }
        z[m] = score;
        if (chance != nullptr) chance[m] = mean ? score : 1.0f / (1.0f + __expf(-score));
    }
}

// Whether both have the k-th text compared letter by letter
#define LETTERED_BOTH(k) (ll[(size_t) (k) * n + u] > 0.0f && ll[(size_t) (k) * n + j] > 0.0f)

// The pair scored by a thread alone, its rows' products its own
__device__ void pair_scores(int u, int j, WORKS_PARAMS, float* z, float* chance) {
    int a = siteOf[u], b = siteOf[j];
    float t = sparse_dot(tAt, tE, u, j);
    bool hasA = al[u] > 0.0f && al[j] > 0.0f;
    float av = hasA ? sparse_dot(aAt, aE, u, j) : 0.0f;
    bool hasDescription = comparedD[a * sites + b] != 0 && hasD[u] != 0 && hasD[j] != 0;
    float d = hasDescription
        ? dot_bytes(D + (size_t) u * described, D + (size_t) j * described, described) * dS[u] * dS[j] : 0.0f;
    bool hasTitleVector = comparedV[a * sites + b] != 0 && hasV[u] != 0 && hasV[j] != 0;
    float v = hasTitleVector ? dot_bytes(V + (size_t) u * titled, V + (size_t) j * titled, titled) * vS[u] * vS[j] : 0.0f;
    // The description's trigrams whole, the sites' names of the fandoms and lines of the characters sparse
    float l[LETTERED];
    l[0] = LETTERED_BOTH(0) ? dot_halves(L + (size_t) u * width, L + (size_t) j * width, width) : 0.0f;
    l[1] = LETTERED_BOTH(1) ? sparse_dot(fAt, fE, u, j) : 0.0f;
    l[2] = LETTERED_BOTH(2) ? sparse_dot(cAt, cE, u, j) : 0.0f;
    float f[MAX_MODELS];
    shared_values(u, j, weights + ((size_t) a * sites + b) * models * stride, WORKS_ARGS, f);
    pair_of(u, j, t, av, d, v, l, f, WORKS_ARGS, z, chance);
}

// The pair scored by a warp: the rows' products read together, the rest by its first lane
__device__ void warp_pair_scores(int u, int j, int lane, WORKS_PARAMS, float* z, float* chance) {
    int a = siteOf[u], b = siteOf[j];
    float t = warp_sparse_dot(tAt, tE, u, j, lane);
    bool hasA = al[u] > 0.0f && al[j] > 0.0f;
    float av = hasA ? warp_sparse_dot(aAt, aE, u, j, lane) : 0.0f;
    bool hasDescription = comparedD[a * sites + b] != 0 && hasD[u] != 0 && hasD[j] != 0;
    float d = hasDescription
        ? warp_dot_bytes(D + (size_t) u * described, D + (size_t) j * described, described, lane) * dS[u] * dS[j]
        : 0.0f;
    bool hasTitleVector = comparedV[a * sites + b] != 0 && hasV[u] != 0 && hasV[j] != 0;
    float v = hasTitleVector
        ? warp_dot_bytes(V + (size_t) u * titled, V + (size_t) j * titled, titled, lane) * vS[u] * vS[j]
        : 0.0f;
    float l[LETTERED];
    l[0] = LETTERED_BOTH(0) ? warp_dot(L + (size_t) u * width, L + (size_t) j * width, width, lane) : 0.0f;
    l[1] = LETTERED_BOTH(1) ? warp_sparse_dot(fAt, fE, u, j, lane) : 0.0f;
    l[2] = LETTERED_BOTH(2) ? warp_sparse_dot(cAt, cE, u, j, lane) : 0.0f;
    float f[MAX_MODELS];
    warp_shared_values(u, j, lane, weights + ((size_t) a * sites + b) * models * stride, WORKS_ARGS, f);
    if (lane == 0) pair_of(u, j, t, av, d, v, l, f, WORKS_ARGS, z, chance);
}

// A STEP OF THE DESCENT: of every work u — a block a work — its neighbours (G[u]) and its neighbours' neighbours, every
// model's chance of each with u worked out (pair_scores), and K of them taken, none twice and never u itself: the
// models in turn, each its likeliest of those left — a model finding every pair alike likely taking no neighbour of
// another's. Of a neighbour's neighbour only those new to one of the two (F, of the step before: a neighbour new to
// u, or a neighbour of it new to it) — the rest were candidates of the step before, and lost. Written to G2[u], which
// of them are new to F2[u], every model's chance of each to P2[u]; how many are new added to changed[0], how many
// pairs were scored to changed[1]. When kept, the chances of the neighbours are those of the step before (P[u]), not
// scored anew; and a work of no new candidate keeps its neighbours, in their order: of the same candidates of the
// same chances, the models in turn take the same (2026-11-23: of every step, the neighbours scored anew were 24 million
// pairs of the books, the works of no new candidate most of the last steps)
extern "C" __global__ void __launch_bounds__(BLOCK, BLOCKS_A_MULTIPROCESSOR) descend(
    const int* G, const unsigned char* F, const float* P, int* G2, unsigned char* F2, float* P2, int kept,
    unsigned long long* changed, WORKS_PARAMS
) {
    __shared__ int ids[CANDIDATES];
    __shared__ float chances[CANDIDATES * MAX_MODELS];
    __shared__ bool taken[CANDIDATES];
    __shared__ float bestScore[BLOCK];
    __shared__ int bestAt[BLOCK];
    __shared__ int chosen[K];
    __shared__ int chosenAt[K];
    __shared__ bool fresh;
    int u = blockIdx.x;
    if (u >= n) return;
    for (int c = threadIdx.x; c < CANDIDATES; c += blockDim.x) {
        int id;
        if (c < K) id = G[(size_t) u * K + c];
        else {
            int at = (c - K) / K;
            int v = G[(size_t) u * K + at];
            id = v < 0 || !F[(size_t) u * K + at] && !F[(size_t) v * K + (c - K) % K] ? -1 : G[(size_t) v * K + (c - K) % K];
        }
        ids[c] = id;
        taken[c] = id < 0 || id == u;
    }
    __syncthreads();
    // A work come by several neighbours scored once: of the same work, the first candidate alone
    bool again[(CANDIDATES + BLOCK - 1) / BLOCK];
    for (int c = threadIdx.x, r = 0; c < CANDIDATES; c += blockDim.x, r++) {
        again[r] = false;
        if (!taken[c]) for (int d = 0; d < c && !again[r]; d++) if (ids[d] == ids[c]) again[r] = true;
    }
    __syncthreads();
    if (threadIdx.x == 0) fresh = false;
    __syncthreads();
    for (int c = threadIdx.x, r = 0; c < CANDIDATES; c += blockDim.x, r++) {
        if (again[r]) taken[c] = true;
        else if (c >= K && !taken[c]) fresh = true;
    }
    __syncthreads();
    // No new candidate: the neighbours kept as they are
    if (kept && !fresh) {
        if (threadIdx.x < K) {
            size_t at = (size_t) u * K + threadIdx.x;
            G2[at] = G[at];
            F2[at] = 0;
            for (int m = 0; m < models; m++) P2[at * models + m] = P[at * models + m];
        }
        return;
    }
    // The neighbours' chances of the step before
    if (kept)
        for (int c = threadIdx.x; c < K; c += blockDim.x)
            if (!taken[c])
                for (int m = 0; m < models; m++) chances[c * MAX_MODELS + m] = P[((size_t) u * K + c) * models + m];
    // A warp a candidate: its rows read together
    float z[MAX_MODELS];
    unsigned long long count = 0;
    int lane = threadIdx.x & 31;
    for (int c = (kept ? K : 0) + (threadIdx.x >> 5); c < CANDIDATES; c += blockDim.x >> 5)
        if (!taken[c]) {
            warp_pair_scores(u, ids[c], lane, WORKS_ARGS, z, chances + c * MAX_MODELS);
            count++;
        }
    if (lane == 0 && count > 0) atomicAdd(changed + 1, count);
    __syncthreads();
    // One a round, the models in turn: of the block the likeliest by the round's model, then every candidate of that
    // work put out
    for (int round = 0; round < K; round++) {
        int m = round % models;
        float s = NO_SCORE;
        int at = -1;
        for (int c = threadIdx.x; c < CANDIDATES; c += blockDim.x) {
            if (taken[c]) continue;
            float x = chances[c * MAX_MODELS + m];
            if (x > s || x == s && at >= 0 && ids[c] < ids[at]) { s = x; at = c; }
        }
        bestScore[threadIdx.x] = s;
        bestAt[threadIdx.x] = at;
        __syncthreads();
        for (int half = blockDim.x / 2; half > 0; half >>= 1) {
            if (threadIdx.x < half) {
                float b = bestScore[threadIdx.x + half];
                int bt = bestAt[threadIdx.x + half];
                // Of equal chances the work first in the numbering: of many works alike, the same chosen at every
                // step, the descent settling
                int at = bestAt[threadIdx.x];
                if (bt >= 0 && (at < 0 || b > bestScore[threadIdx.x] || b == bestScore[threadIdx.x] && ids[bt] < ids[at])) {
                    bestScore[threadIdx.x] = b;
                    bestAt[threadIdx.x] = bt;
                }
            }
            __syncthreads();
        }
        int pick = bestAt[0];
        int id = pick >= 0 ? ids[pick] : -1;
        if (threadIdx.x == 0) {
            chosen[round] = id;
            chosenAt[round] = pick;
        }
        __syncthreads();
        if (id >= 0) for (int c = threadIdx.x; c < CANDIDATES; c += blockDim.x) if (ids[c] == id) taken[c] = true;
        __syncthreads();
    }
    if (threadIdx.x < K) {
        int id = chosen[threadIdx.x];
        int pick = chosenAt[threadIdx.x];
        G2[(size_t) u * K + threadIdx.x] = id;
        for (int m = 0; m < models; m++)
            P2[((size_t) u * K + threadIdx.x) * models + m] = pick >= 0 ? chances[pick * MAX_MODELS + m] : NO_SCORE;
        bool known = false;
        for (int k = 0; k < K; k++) if (G[(size_t) u * K + k] == id) known = true;
        F2[(size_t) u * K + threadIdx.x] = !known && id >= 0;
        if (!known && id >= 0) atomicAdd(changed, 1ULL);
    }
}

// THE NEIGHBOURS SCORED: of every work u — a warp a work — of its K neighbours (G[u]) and theirs, and its other
// candidates (extraAt[u] .. extraAt[u + 1] of extra), all but itself and those the user answered on (passAt[u] ..
// passAt[u + 1] of pass), every model's score of the pair (pair_scores). Of every model the highest, of equal ones the
// work first in the numbering, at best[u][model] (-1: none), its score at scores[u][model]
extern "C" __global__ void __launch_bounds__(BLOCK, BLOCKS_A_MULTIPROCESSOR) scored(
    const int* G, const int* extraAt, const int* extra, const int* passAt, const int* pass, int* best, float* scores,
    WORKS_PARAMS
) {
    int warp = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
    int lane = threadIdx.x & 31;
    if (warp >= n) return;
    int u = warp;
    float own[MAX_MODELS];
    int at[MAX_MODELS];
    float z[MAX_MODELS];
    for (int m = 0; m < MAX_MODELS; m++) { own[m] = NO_SCORE; at[m] = -1; }
    int candidates = CANDIDATES + extraAt[u + 1] - extraAt[u];
    for (int c = lane; c < candidates; c += 32) {
        int j;
        if (c < K) j = G[(size_t) u * K + c];
        else if (c >= CANDIDATES) j = extra[extraAt[u] + c - CANDIDATES];
        else {
            int v = G[(size_t) u * K + (c - K) / K];
            j = v < 0 ? -1 : G[(size_t) v * K + (c - K) % K];
        }
        bool passed = j < 0 || j == u;
        for (int p = passAt[u]; p < passAt[u + 1] && !passed; p++) if (pass[p] == j) passed = true;
        if (!passed) {
            pair_scores(u, j, WORKS_ARGS, z, nullptr);
            for (int m = 0; m < models; m++) if (z[m] > own[m] || z[m] == own[m] && j < at[m]) { own[m] = z[m]; at[m] = j; }
        }
    }
    for (int m = 0; m < models; m++) {
        float s = own[m];
        int j = at[m];
        for (int offset = 16; offset > 0; offset >>= 1) {
            float t = __shfl_down_sync(0xffffffff, s, offset);
            int k = __shfl_down_sync(0xffffffff, j, offset);
            if (k >= 0 && (j < 0 || t > s || t == s && k < j)) { s = t; j = k; }
        }
        if (lane == 0) {
            best[(size_t) u * models + m] = j;
            scores[(size_t) u * models + m] = s;
        }
    }
}
