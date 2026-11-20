
// ROWS OF ANY KIND MADE WHOLE HALVES ON THE CARD (Rows, RowsOnCard): what the products and the kernels of the searches
// read. Put after the source of a search, a part of it.

// Of sparse rows, a row a thread: of the row r its entries at[r] .. at[r + 1], each its column in its lower two bytes
// and its half in the higher, written into the row of out, made nought before
extern "C" __global__ void sparse_halves(int rows, int cols, const int* at, const unsigned int* entries, unsigned short* out) {
    int r = blockIdx.x * blockDim.x + threadIdx.x;
    if (r >= rows) return;
    for (int k = at[r]; k < at[r + 1]; k++) {
        unsigned int e = entries[k];
        out[(size_t) r * cols + (e & 0xffffu)] = (unsigned short) (e >> 16);
    }
}

// Of rows of a byte a number, a number a thread: its byte times its row's scale, rounded to half precision
extern "C" __global__ void byte_halves(
    long long n, int cols, const signed char* bytes, const float* scales, unsigned short* out
) {
    long long k = (long long) blockIdx.x * blockDim.x + threadIdx.x;
    if (k >= n) return;
    float f = bytes[k] * scales[k / cols];
    unsigned short h;
    asm("cvt.rn.f16.f32 %0, %1;" : "=h"(h) : "f"(f));
    out[k] = h;
}
