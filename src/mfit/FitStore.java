package com.hanxing.mifit;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** FIT 落盘：Android 10+ 走 MediaStore 的 Download，更老的写 App 外部目录 */
public class FitStore {
    public static String save(Context ctx, byte[] data, String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download");
            Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("无法写入 Download");
            OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            os.write(data);
            os.close();
            return "Download/" + name;
        }
        File dir = ctx.getExternalFilesDir(null);
        File f = new File(dir, name);
        FileOutputStream fo = new FileOutputStream(f);
        fo.write(data);
        fo.close();
        return f.getAbsolutePath();
    }
}
