package com.proggerx.dcross2000;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.Log;

import java.io.File;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import static org.junit.Assert.assertFalse;

/** Verifies that the Nothing OS NType families actually resolve for third-party apps. */
public class FontResolutionTest {
    @Test
    public void ntypeHeadlineDiffersFromSystemSans() {
        assertFalse("NType82-Headline did not resolve",
                render("NType82-Headline").sameAs(render("sans-serif")));
    }

    @Test
    public void ntypeRegularDiffersFromSystemSans() {
        assertFalse("NType82-Regular did not resolve",
                render("NType82-Regular").sameAs(render("sans-serif")));
    }

    @Test
    public void reportWhichFaceNtypeResolvesTo() {
        Bitmap ntype = render("NType82-Headline");
        Bitmap file = renderFile("/system/fonts/NType82-Headline.otf");
        Log.i("FontResolution", "ntype==sans " + ntype.sameAs(render("sans-serif")));
        Log.i("FontResolution", "ntype==serif " + ntype.sameAs(render("serif")));
        Log.i("FontResolution", "ntype==roboto " + ntype.sameAs(render("roboto")));
        Log.i("FontResolution", "ntype==file " + ntype.sameAs(file));
        Log.i("FontResolution", "serif==file " + render("serif").sameAs(file));
    }

    private Bitmap renderFile(String path) {
        return render(Typeface.createFromFile(new File(path)));
    }

    private Bitmap render(String family) {
        return render(Typeface.create(family, Typeface.BOLD));
    }

    private Bitmap render(Typeface typeface) {
        Bitmap bitmap = Bitmap.createBitmap(420, 130, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(typeface);
        paint.setTextSize(64f);
        paint.setColor(Color.BLACK);
        canvas.drawText("AaBbGg 123", 10f, 88f, paint);
        return bitmap;
    }
}
