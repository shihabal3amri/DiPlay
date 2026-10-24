// Ported from the user's carlink FX11 integration (c839f925).
package com.shilapi.xcertplay.vehicle;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

/** Public-file DIM experiment. Never falls back to Android/data or a provider. */
public final class PublicArtworkStore {
    private static volatile String lastError = "";

    public static String lastError() { return lastError; }

    public static synchronized Uri publish(String mediaId, Bitmap bitmap) {
        lastError = "";
        if (bitmap == null) { lastError = "没有可导出的封面"; return null; }
        String id = mediaId != null && mediaId.matches("[A-Za-z0-9_-]+") ? mediaId : "default";
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), "DiPlay/dim-artwork/" + id);
        File target = new File(dir, UUID.randomUUID() + ".jpg");
        File temporary = new File(dir, target.getName() + ".tmp");
        Bitmap scaled = bitmap;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建公共目录 " + dir);
            int edge = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (edge > 192) scaled = Bitmap.createScaledBitmap(bitmap,
                    Math.max(1, bitmap.getWidth() * 192 / edge),
                    Math.max(1, bitmap.getHeight() * 192 / edge), true);
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                    throw new IOException("JPEG 编码失败");
                }
                output.getFD().sync();
            }
            if (!temporary.renameTo(target)) throw new IOException("封面原子重命名失败");
            Bitmap decoded = BitmapFactory.decodeFile(target.getAbsolutePath());
            if (decoded == null) throw new IOException("写入后的封面无法解码");
            decoded.recycle();
            File[] previous = dir.listFiles((parent, name) -> name.matches(
                    "[0-9a-f-]{36}\\.jpg") && !name.equals(target.getName()));
            if (previous != null) {
                Arrays.sort(previous, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (int i = 2; i < previous.length; i++) previous[i].delete();
            }
            Log.i("ArtworkProvider", "public JPEG ready uri=" + Uri.fromFile(target)
                    + " bytes=" + target.length() + " selfDecode=ok externalAccess=unverified");
            return Uri.fromFile(target);
        } catch (IOException | RuntimeException error) {
            lastError = error.getMessage() + "；请检查公共目录写入权限（Android 11+：所有文件访问权限）";
            Log.w("ArtworkProvider", "public JPEG failed; no fallback", error);
            target.delete();
            return null;
        } finally {
            temporary.delete();
            if (scaled != bitmap) scaled.recycle();
        }
    }
}
