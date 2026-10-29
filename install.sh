#!/bin/sh
set -e

REPO="snashoan/girnet"
BRANCH="${GIRCHAT_BRANCH:-main}"
RAW_BASE="https://raw.githubusercontent.com/$REPO/$BRANCH"

WANT_C=0
for arg in "$@"; do
    case "$arg" in
        --c|--native) WANT_C=1 ;;
    esac
done
if [ "$GIRCHAT_EDITION" = "c" ] || [ "$GIRCHAT_EDITION" = "native" ]; then
    WANT_C=1
fi

echo "============================================================"
echo "  girchat / shnet - Direct GitHub Installer"
echo "  repository: https://github.com/$REPO"
echo "============================================================"

# Determine target bin directory
if [ -n "$PREFIX" ]; then
    # Termux environment
    BIN_DIR="$PREFIX/bin"
elif [ -d "/usr/local/bin" ] && ( [ "$(id -u)" = "0" ] || [ -w "/usr/local/bin" ] ); then
    BIN_DIR="/usr/local/bin"
elif [ -d "/opt/homebrew/bin" ] && [ -w "/opt/homebrew/bin" ]; then
    # macOS Homebrew bin directory
    BIN_DIR="/opt/homebrew/bin"
else
    # Standard user ~/.local/bin
    BIN_DIR="$HOME/.local/bin"
fi
mkdir -p "$BIN_DIR"

ARCH=$(uname -m)
OS=$(uname -s)
INSTALLED=0

echo "[INFO] Detected OS: $OS | Architecture: $ARCH"
echo "[INFO] Target install directory: $BIN_DIR"

# Strategy 1: Prebuilt static ARM64/aarch64 binary from GitHub (for Linux / Android Termux)
if [ "$OS" = "Linux" ] && ( [ "$ARCH" = "aarch64" ] || [ "$ARCH" = "arm64" ] ); then
    echo "[INSTALL] Downloading prebuilt static aarch64 binary from GitHub..."
    TMP_BIN=$(mktemp)
    if curl -fSL "$RAW_BASE/bin/girchat" -o "$TMP_BIN" 2>/dev/null; then
        chmod +x "$TMP_BIN"
        if "$TMP_BIN" version >/dev/null 2>&1; then
            mv "$TMP_BIN" "$BIN_DIR/girchat"
            chmod +x "$BIN_DIR/girchat"
            ln -sf "$BIN_DIR/girchat" "$BIN_DIR/shnet" 2>/dev/null || cp "$BIN_DIR/girchat" "$BIN_DIR/shnet"
            echo "[SUCCESS] Native static binary installed to $BIN_DIR/girchat and $BIN_DIR/shnet"
            INSTALLED=1
        else
            echo "[WARN] Prebuilt binary execution verification failed, checking alternative methods..."
            rm -f "$TMP_BIN"
        fi
    fi
else
    echo "[INFO] Prebuilt binary is for Linux aarch64. Target system is $OS ($ARCH)."
fi

# Strategy 2: If C compiler available, build native binary locally from GitHub sources (macOS, x86_64, BSD)
if [ "$INSTALLED" -eq 0 ]; then
    CC=""
    if command -v clang >/dev/null 2>&1; then CC="clang"
    elif command -v gcc >/dev/null 2>&1; then CC="gcc"
    elif command -v cc >/dev/null 2>&1; then CC="cc"
    fi

    if [ -n "$CC" ]; then
        echo "[INSTALL] Found $CC. Compiling native binary from GitHub sources..."
        TMP_DIR=$(mktemp -d 2>/dev/null || mktemp -d -t 'girchat' 2>/dev/null || echo "/tmp/girchat_build_$$")
        mkdir -p "$TMP_DIR"
        if curl -fSL "$RAW_BASE/c/shnet.c" -o "$TMP_DIR/shnet.c" 2>/dev/null && \
           curl -fSL "$RAW_BASE/c/qrcodegen.c" -o "$TMP_DIR/qrcodegen.c" 2>/dev/null && \
           curl -fSL "$RAW_BASE/c/qrcodegen.h" -o "$TMP_DIR/qrcodegen.h" 2>/dev/null; then
            if $CC -O2 -pthread "$TMP_DIR/shnet.c" "$TMP_DIR/qrcodegen.c" -o "$BIN_DIR/girchat"; then
                strip "$BIN_DIR/girchat" 2>/dev/null || true
                chmod +x "$BIN_DIR/girchat"
                ln -sf "$BIN_DIR/girchat" "$BIN_DIR/shnet" 2>/dev/null || cp "$BIN_DIR/girchat" "$BIN_DIR/shnet"
                echo "[SUCCESS] Compiled & installed native binary to $BIN_DIR/girchat and $BIN_DIR/shnet"
                INSTALLED=1
            else
                echo "[WARN] Native compilation with $CC failed."
            fi
        fi
        rm -rf "$TMP_DIR"
    else
        if [ "$OS" = "Darwin" ]; then
            echo "[INFO] No C compiler found. To build native binary on macOS, run: xcode-select --install"
        fi
        if [ "$WANT_C" -eq 1 ]; then
            echo "[ERROR] Native C edition requested, but no compiler (clang/gcc/cc) found on this system."
            exit 1
        fi
    fi
fi

# Strategy 3: Universal pure Python 3 edition (zero external dependencies)
if [ "$INSTALLED" -eq 0 ] && [ "$WANT_C" -eq 0 ] && command -v python3 >/dev/null 2>&1; then
    echo "[INFO] Falling back to universal Python 3 edition (zero external dependencies)..."
    if curl -fSL "$RAW_BASE/python/shnet.py" -o "$BIN_DIR/girchat" 2>/dev/null; then
        chmod +x "$BIN_DIR/girchat"
        ln -sf "$BIN_DIR/girchat" "$BIN_DIR/shnet" 2>/dev/null || cp "$BIN_DIR/girchat" "$BIN_DIR/shnet"
        echo "[SUCCESS] Pure Python edition installed to $BIN_DIR/girchat and $BIN_DIR/shnet"
        INSTALLED=1
    fi
fi

if [ "$INSTALLED" -eq 0 ]; then
    echo "[ERROR] Installation failed. Ensure curl and python3 (or clang/gcc) are installed."
    exit 1
fi

# Check PATH
case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *)
        echo ""
        echo "[NOTE] Add $BIN_DIR to your PATH by adding this line to your ~/.bashrc or ~/.zshrc:"
        echo "       export PATH=\"$BIN_DIR:\$PATH\""
        ;;
esac

echo ""
echo "Installation complete!"
"$BIN_DIR/girchat" version 2>/dev/null || "$BIN_DIR/girchat" -v 2>/dev/null || true
echo "Run 'girchat' or 'shnet' to begin."
