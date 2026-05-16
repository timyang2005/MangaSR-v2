#!/bin/bash
set -e

echo "=========================================="
echo "MangaSR v2 - Build Preparation Script"
echo "=========================================="

NCNN_VERSION="20250915"
MIRROR="https://ghfast.top"
NCNN_URL="${MIRROR}/https://github.com/Tencent/ncnn/releases/download/${NCNN_VERSION}/ncnn-${NCNN_VERSION}-android-vulkan.zip"
NCNN_DIR="core/superresolution/src/main/cpp/ncnn"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

download_ncnn() {
    log_info "Downloading NCNN v${NCNN_VERSION} (Android Vulkan)..."

    mkdir -p "${NCNN_DIR}"
    TMPDIR=$(mktemp -d)
    cd "${TMPDIR}"

    if ! curl -L --retry 3 --connect-timeout 30 -o "ncnn.zip" "${NCNN_URL}"; then
        log_warn "Direct download failed, trying GitHub mirror..."
        curl -L --retry 3 --connect-timeout 30 -o "ncnn.zip" "https://github.com/Tencent/ncnn/releases/download/${NCNN_VERSION}/ncnn-${NCNN_VERSION}-android-vulkan.zip"
    fi

    log_info "Extracting NCNN..."
    unzip -o "ncnn.zip"

    SRC_DIR="ncnn-${NCNN_VERSION}-android-vulkan"
    PROJ_ROOT="$(cd ../.. && pwd)"

    for abi in arm64-v8a armeabi-v7a x86_64; do
        mkdir -p "${PROJ_ROOT}/${NCNN_DIR}/lib/${abi}"
        cp -v "${SRC_DIR}/${abi}/lib/libncnn.a" "${PROJ_ROOT}/${NCNN_DIR}/lib/${abi}/"
        cp -v "${SRC_DIR}/${abi}/lib/libSPIRV.a" "${PROJ_ROOT}/${NCNN_DIR}/lib/${abi}/"
        cp -v "${SRC_DIR}/${abi}/lib/libglslang.a" "${PROJ_ROOT}/${NCNN_DIR}/lib/${abi}/"
        cp -v "${SRC_DIR}/${abi}/lib/libMachineIndependent.a" "${PROJ_ROOT}/${NCNN_DIR}/lib/${abi}/"
        cp -v "${SRC_DIR}/${abi}/lib/libGenericCodeGen.a" "${PROJ_ROOT}/${NCNN_DIR}/lib/${abi}/"
        cp -v "${SRC_DIR}/${abi}/lib/libglslang-default-resource-limits.a" "${PROJ_ROOT}/${NCNN_DIR}/lib/${abi}/"
        cp -v "${SRC_DIR}/${abi}/lib/libOSDependent.a" "${PROJ_ROOT}/${NCNN_DIR}/lib/${abi}/"
    done

    mkdir -p "${PROJ_ROOT}/${NCNN_DIR}/include/ncnn"
    cp -v "${SRC_DIR}/arm64-v8a/include/ncnn/"* "${PROJ_ROOT}/${NCNN_DIR}/include/ncnn/"

    cd "${PROJ_ROOT}"
    rm -rf "${TMPDIR}"

    log_info "NCNN installed successfully."
}

verify() {
    log_info "Verifying installation..."

    local errors=0

    for abi in arm64-v8a armeabi-v7a x86_64; do
        for lib in libncnn.a libSPIRV.a libglslang.a libMachineIndependent.a libGenericCodeGen.a libglslang-default-resource-limits.a libOSDependent.a; do
            if [ ! -f "${NCNN_DIR}/lib/${abi}/${lib}" ]; then
                log_error "Missing: ${NCNN_DIR}/lib/${abi}/${lib}"
                errors=$((errors + 1))
            fi
        done
    done

    if [ -d "${NCNN_DIR}/include/ncnn" ]; then
        log_info "NCNN headers: OK"
    else
        log_error "NCNN headers missing!"
        errors=$((errors + 1))
    fi

    MODEL_DIR="core/superresolution/src/main/assets/models"
    for model in realcugan-2x-conservative realcugan-4x-conservative realesrgan-anime-fast realesrgan-anime-plus realesrgan-general-fast; do
        if [ -f "${MODEL_DIR}/${model}/${model}.param" ] && [ -f "${MODEL_DIR}/${model}/${model}.bin" ]; then
            log_info "Model ${model}: OK"
        else
            log_error "Model ${model}: INCOMPLETE"
            errors=$((errors + 1))
        fi
    done

    if [ $errors -eq 0 ]; then
        log_info "All verifications passed!"
        return 0
    else
        log_error "Verification failed with $errors errors."
        return 1
    fi
}

main() {
    echo ""
    echo "MangaSR v2 - Build Preparation"
    echo ""

    if [ -d "${NCNN_DIR}/include/ncnn" ] && [ -f "${NCNN_DIR}/lib/arm64-v8a/libncnn.a" ]; then
        log_info "NCNN library is already built-in. Skipping download."
    else
        log_info "NCNN library not found. Downloading..."
        download_ncnn
    fi

    echo ""
    log_info "=========================================="
    log_info "Build preparation completed!"
    log_info "You can now build the project with:"
    log_info "  ./gradlew assembleDebug"
    log_info "=========================================="
}

main "$@"
