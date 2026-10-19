package com.limelight.tv;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/** Artemide TV: a QR code for a link, so a phone can pick it up from the TV screen. */
public final class TvQr {
    private TvQr() {}

    /** @return a black-on-white QR code of about sizePx square, or null if it could not be made */
    public static Bitmap make(String text, int sizePx) {
        try {
            final Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 2);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            final BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            final int w = m.getWidth(), h = m.getHeight();
            final int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                final int row = y * w;
                for (int x = 0; x < w; x++) {
                    px[row + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            final Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(px, 0, w, 0, 0, w, h);
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }
}
