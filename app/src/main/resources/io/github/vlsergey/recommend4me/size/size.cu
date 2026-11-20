// The weighed ridge of the words to a character solved on the card, its conjugate gradients whole: the examples' rows
// of features (the mean of every example beside them) as offsets into one array of the features' numbers, a double a
// weight and a number of the vectors; the vectors of the features — the solution, the residual, the direction — kept
// on the card, a sum of a step's alone brought back.

// The value v of every thread of a block summed in the block, the sum added to *result: every thread of the block
// calls it.
__device__ void block_sum(double v, double* result) {
    __shared__ double block[256];
    block[threadIdx.x] = v;
    __syncthreads();
    for (int half = blockDim.x / 2; half > 0; half >>= 1) {
        if (threadIdx.x < half) block[threadIdx.x] += block[threadIdx.x + half];
        __syncthreads();
    }
    if (threadIdx.x == 0) atomicAdd(result, block[0]);
}

// Of every example i, its weight times its row's product with x: x[0] (the mean) and x of its every feature.
extern "C" __global__ void size_rows(int n, const int* offsets, const int* features, const double* weight,
                                     const double* x, double* s) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= n) return;
    double sum = x[0];
    for (int k = offsets[i]; k < offsets[i + 1]; k++) sum += x[features[k]];
    s[i] = weight[i] * sum;
}

// Of every example i, its s added to every feature of its row and, summed in the block first, to the mean's.
extern "C" __global__ void size_scatter(int n, const int* offsets, const int* features, const double* s,
                                        double* out) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    double v = 0.0;
    if (i < n) {
        v = s[i];
        if (v != 0.0)
            for (int k = offsets[i]; k < offsets[i + 1]; k++) atomicAdd(&out[features[k]], v);
    }
    block_sum(v, &out[0]);
}

// Of every example i, its weight times its logarithm less the shift.
extern "C" __global__ void size_weigh(int n, const double* weight, const double* y, double shift, double* s) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i < n) s[i] = weight[i] * (y[i] - shift);
}

// Of every feature f, a[f] added to out[f].
extern "C" __global__ void size_add(int m, const double* a, double* out) {
    int f = blockIdx.x * blockDim.x + threadIdx.x;
    if (f < m) out[f] += a[f];
}

// aᵀ D⁻¹ a added to *result: every feature's square by its diagonal, a feature of none left out.
extern "C" __global__ void size_scaled(int m, const double* a, const double* d, double* result) {
    int f = blockIdx.x * blockDim.x + threadIdx.x;
    double v = 0.0;
    if (f < m && d[f] > 0) v = a[f] * a[f] / d[f];
    block_sum(v, result);
}

// The beginning: the residual r = b - (G x + Λ x) of the product gx = G x, z = D⁻¹ r, the direction p = z; rᵀz added
// to *result.
extern "C" __global__ void size_start(int m, const double* b, const double* gx, const double* lambda,
                                      const double* x, const double* d, double* r, double* z, double* p,
                                      double* result) {
    int f = blockIdx.x * blockDim.x + threadIdx.x;
    double v = 0.0;
    if (f < m) {
        double rf = b[f] - gx[f] - lambda[f] * x[f];
        double zf = d[f] > 0 ? rf / d[f] : 0.0;
        r[f] = rf;
        z[f] = zf;
        p[f] = zf;
        v = rf * zf;
    }
    block_sum(v, result);
}

// The product of the direction with the penalty added, ap = G p + Λ p; pᵀ ap added to *result.
extern "C" __global__ void size_curve(int m, const double* p, double* ap, const double* lambda, double* result) {
    int f = blockIdx.x * blockDim.x + threadIdx.x;
    double v = 0.0;
    if (f < m) {
        double a = ap[f] + lambda[f] * p[f];
        ap[f] = a;
        v = p[f] * a;
    }
    block_sum(v, result);
}

// A step of length alpha along p: x += alpha p, r -= alpha ap, z = D⁻¹ r; rᵀz added to *result.
extern "C" __global__ void size_step(int m, double alpha, const double* p, const double* ap, const double* d,
                                     double* x, double* r, double* z, double* result) {
    int f = blockIdx.x * blockDim.x + threadIdx.x;
    double v = 0.0;
    if (f < m) {
        x[f] += alpha * p[f];
        double rf = r[f] - alpha * ap[f];
        double zf = d[f] > 0 ? rf / d[f] : 0.0;
        r[f] = rf;
        z[f] = zf;
        v = rf * zf;
    }
    block_sum(v, result);
}

// The next direction: p = z + beta p.
extern "C" __global__ void size_direction(int m, double beta, const double* z, double* p) {
    int f = blockIdx.x * blockDim.x + threadIdx.x;
    if (f < m) p[f] = z[f] + beta * p[f];
}
