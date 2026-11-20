// The weighed ridge of the words to a character on the card: the examples' rows of features (the mean of every example
// beside them) as offsets into one array of the features' numbers, a double a weight and a number of the vectors.

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
    __shared__ double block[256];
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    double v = 0.0;
    if (i < n) {
        v = s[i];
        if (v != 0.0)
            for (int k = offsets[i]; k < offsets[i + 1]; k++) atomicAdd(&out[features[k]], v);
    }
    block[threadIdx.x] = v;
    __syncthreads();
    for (int half = blockDim.x / 2; half > 0; half >>= 1) {
        if (threadIdx.x < half) block[threadIdx.x] += block[threadIdx.x + half];
        __syncthreads();
    }
    if (threadIdx.x == 0) atomicAdd(&out[0], block[0]);
}
