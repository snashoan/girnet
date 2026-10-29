#!/bin/sh
set -e

REPO="snashoan/girnet"
BRANCH="${GIRCHAT_BRANCH:-main}"
RAW_BASE="https://raw.githubusercontent.com/$REPO/$BRANCH"

echo "============================================================"
echo "  girchat / shnet - Direct GitHub Installer"
echo "  repository: https://github.com/$REPO"
echo "============================================================"

# Determine target bin directory
if [ -n "$PREFIX" ]; then
    # Termux environment
    BIN_DIR="$PREFIX/bin"
    mkdir -p "$BIN_DIR"
elif [ -d "/usr/local/bin" ] && [ "$(id -u)" = "0" ]; then
    # Root user on standard Linux
    BIN_DIR="/usr/local/bin"
else
    # Standard user ~/.local/bin
    BIN_DIR="$HOME/.local/bin"
    mkdir -p "$BIN_DIR"
fi

ARCH=$(uname -m)
OS=$(uname -s)
INSTALLED=0

echo "[INFO] Detected OS: $OS | Architecture: $ARCH"
echo "[INFO] Target install directory: $BIN_DIR"

# Strategy 1: Prebuilt static ARM64/aarch64 binary from GitHub
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
fi

# Strategy 2: If C compiler available, build native binary locally from GitHub sources
if [ "$INSTALLED" -eq 0 ]; then
    CC=""
    if command -v gcc >/dev/null 2>&1; then CC="gcc"; fi
    if command -v clang >/dev/null 2>&1; then CC="clang"; fi

    if [ -n "$CC" ]; then
        echo "[INSTALL] Compiling native binary from GitHub sources using $CC..."
        TMP_DIR=$(mktemp -d)
        if curl -fSL "$RAW_BASE/c/shnet.c" -o "$TMP_DIR/shnet.c" 2>/dev/null && \
           curl -fSL "$RAW_BASE/c/qrcodegen.c" -o "$TMP_DIR/qrcodegen.c" 2>/dev/null && \
           curl -fSL "$RAW_BASE/c/qrcodegen.h" -o "$TMP_DIR/qrcodegen.h" 2>/dev/null; then
            if $CC -O2 -pthread "$TMP_DIR/shnet.c" "$TMP_DIR/qrcodegen.c" -o "$BIN_DIR/girchat" 2>/dev/null; then
                strip "$BIN_DIR/girchat" 2>/dev/null || true
                chmod +x "$BIN_DIR/girchat"
                ln -sf "$BIN_DIR/girchat" "$BIN_DIR/shnet" 2>/dev/null || cp "$BIN_DIR/girchat" "$BIN_DIR/shnet"
                echo "[SUCCESS] Compiled & installed native binary to $BIN_DIR/girchat and $BIN_DIR/shnet"
                INSTALLED=1
            fi
        fi
        rm -rf "$TMP_DIR"
    fi
fi

# Strategy 3: Universal pure Python 3 edition (zero external dependencies)
if [ "$INSTALLED" -eq 0 ] && command -v python3 >/dev/null 2>&1; then
    echo "[INSTALL] Downloading pure Python edition from GitHub..."
    if curl -fSL "$RAW_BASE/python/shnet.py" -o "$BIN_DIR/girchat" 2>/dev/null; then
        chmod +x "$BIN_DIR/girchat"
        ln -sf "$BIN_DIR/girchat" "$BIN_DIR/shnet" 2>/dev/null || cp "$BIN_DIR/girchat" "$BIN_DIR/shnet"
        echo "[SUCCESS] Pure Python edition installed to $BIN_DIR/girchat and $BIN_DIR/shnet"
        INSTALLED=1
    fi
fi

if [ "$INSTALLED" -eq 0 ]; then
    echo "[ERROR] Installation failed. Ensure curl is installed and you have an active network connection."
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
