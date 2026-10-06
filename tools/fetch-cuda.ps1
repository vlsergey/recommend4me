# The CUDA 12 libraries the GPU build of ONNX Runtime 1.20 asks for (cudart64_12, cublas64_12,
# cublasLt64_12, cufft64_11, cudnn64_9 and the parts of cuDNN), taken from NVIDIA's own wheels on
# PyPI rather than from the CUDA toolkit and cuDNN installers: a wheel is a zip, its DLLs are all
# that is needed, and nothing is installed system-wide. They go into the data folder, where
# the application looks for them when started with recommend4me.gpu=true.
#
#   powershell -ExecutionPolicy Bypass -File tools\fetch_cuda.ps1
#
# About 1.5 GB of downloads. cuBLAS 12.9 and cuDNN 9 are new enough for Blackwell (RTX 50xx).
$ErrorActionPreference = 'Stop'
$target = Join-Path $env:LOCALAPPDATA 'recommend4me\cuda'
$work = Join-Path $env:TEMP 'recommend4me-cuda-wheels'
New-Item -ItemType Directory -Force $target, $work | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem

foreach ($package in 'nvidia-cuda-runtime-cu12', 'nvidia-cublas-cu12', 'nvidia-cufft-cu12', 'nvidia-cudnn-cu12') {
    $release = Invoke-RestMethod "https://pypi.org/pypi/$package/json"
    $wheel = $release.urls | Where-Object { $_.filename -like '*win_amd64.whl' } | Select-Object -First 1
    $file = Join-Path $work $wheel.filename
    if (-not (Test-Path $file) -or (Get-Item $file).Length -ne $wheel.size) {
        Write-Host "$package $($release.info.version): $([math]::Round($wheel.size / 1MB)) MB"
        curl.exe -L --fail -o $file $wheel.url
        if ($LASTEXITCODE -ne 0) { throw "$package was not downloaded" }
    }
    $zip = [IO.Compression.ZipFile]::OpenRead($file)
    try {
        # Not nvblas (a BLAS that takes over the calls of others) nor cufftw: the runtime asks for neither
        $zip.Entries | Where-Object { $_.Name -like '*.dll' -and $_.Name -notlike 'nvblas*' -and $_.Name -notlike 'cufftw*' } | ForEach-Object {
            [IO.Compression.ZipFileExtensions]::ExtractToFile($_, (Join-Path $target $_.Name), $true)
        }
    } finally {
        $zip.Dispose()
    }
}
Remove-Item -Recurse -Force $work
Write-Host "CUDA libraries are in $target"
