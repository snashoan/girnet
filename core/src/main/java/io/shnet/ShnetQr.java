package io.shnet;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

public final class ShnetQr {
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_WHITE_BG_BLACK_FG = "\u001B[47;30m";

    private ShnetQr() {
    }

    public static BitMatrix encode(String text, int margin) throws WriterException {
        QRCodeWriter writer = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, margin);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        return writer.encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
    }

    /**
     * Renders a crisp, compact ANSI QR code for direct terminal scanning.
     */
    public static String renderAnsi(String text) {
        try {
            BitMatrix matrix = encode(text, 1);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            StringBuilder sb = new StringBuilder();

            int borderCols = 2;
            int totalCols = width + (borderCols * 2);

            // Top quiet zone (1 line representing 2 rows of white)
            sb.append(ANSI_WHITE_BG_BLACK_FG);
            for (int i = 0; i < totalCols; i++) {
                sb.append(' ');
            }
            sb.append(ANSI_RESET).append('\n');

            for (int y = 0; y < height; y += 2) {
                sb.append(ANSI_WHITE_BG_BLACK_FG);
                // Left quiet zone
                for (int b = 0; b < borderCols; b++) {
                    sb.append(' ');
                }

                for (int x = 0; x < width; x++) {
                    boolean topDark = matrix.get(x, y);
                    boolean bottomDark = (y + 1 < height) && matrix.get(x, y + 1);

                    if (topDark && bottomDark) {
                        sb.append('█');
                    } else if (!topDark && !bottomDark) {
                        sb.append(' ');
                    } else if (topDark) {
                        sb.append('▀');
                    } else {
                        sb.append('▄');
                    }
                }

                // Right quiet zone
                for (int b = 0; b < borderCols; b++) {
                    sb.append(' ');
                }
                sb.append(ANSI_RESET).append('\n');
            }

            // Bottom quiet zone
            sb.append(ANSI_WHITE_BG_BLACK_FG);
            for (int i = 0; i < totalCols; i++) {
                sb.append(' ');
            }
            sb.append(ANSI_RESET).append('\n');

            return sb.toString();
        } catch (WriterException e) {
            return "[Error generating QR code: " + e.getMessage() + "]";
        }
    }

    public static byte[] renderPng(String text, int size) {
        try {
            int actualSize = size > 0 ? size : 300;
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 2);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            BitMatrix matrix = writer.encode(text, BarcodeFormat.QR_CODE, actualSize, actualSize, hints);
            BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate QR PNG", e);
        }
    }

    public static void writePng(String text, int size, File outputFile) throws IOException {
        byte[] bytes = renderPng(text, size);
        if (outputFile.getParentFile() != null) {
            outputFile.getParentFile().mkdirs();
        }
        java.nio.file.Files.write(outputFile.toPath(), bytes);
    }
}
