#!/bin/bash

set -e

echo "=== Signal-Android Build Setup ==="

# Initialize git submodules (includes LNI library)
echo "Initializing git submodules..."
git submodule update --init --recursive

# Download LNI native libraries if not present
LNI_VERSION="0.1.0"
LNI_ZIP_URL="https://github.com/lightning-node-interface/lni/releases/download/v${LNI_VERSION}/lni-android-${LNI_VERSION}.zip"
JNI_LIBS_DIR="app/src/main/jniLibs"

# Check if LNI libraries are already present
if [ ! -f "${JNI_LIBS_DIR}/arm64-v8a/liblni.so" ]; then
    echo "Downloading LNI native libraries v${LNI_VERSION}..."
    
    # Create temp directory
    TEMP_DIR=$(mktemp -d)
    ZIP_FILE="${TEMP_DIR}/lni-android.zip"
    
    # Download the zip file
    curl -L -o "${ZIP_FILE}" "${LNI_ZIP_URL}"
    
    # Extract to jniLibs directory
    echo "Extracting LNI libraries to ${JNI_LIBS_DIR}..."
    mkdir -p "${JNI_LIBS_DIR}"
    unzip -o "${ZIP_FILE}" -d "${JNI_LIBS_DIR}"
    
    # Clean up
    rm -rf "${TEMP_DIR}"
    
    echo "LNI native libraries installed successfully!"
else
    echo "LNI native libraries already present, skipping download."
fi

echo "=== Setup complete! ==="
echo ""
echo "NOTE: For Spark (Breez SDK) support, add your API key to local.properties:"
echo "  breezApiKey=your_api_key_here"
echo "Get a key at: https://breez.technology"