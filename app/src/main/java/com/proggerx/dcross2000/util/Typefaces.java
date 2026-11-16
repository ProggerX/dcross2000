package com.proggerx.dcross2000.util;

import android.graphics.Typeface;
import android.widget.TextView;

import java.io.File;

/**
 * NType82 is a Nothing OS system font. It is not exposed through the normal
 * XML family lookup for third-party apps, so load the installed file directly
 * and fall back to the platform serif elsewhere. Nothing's licence forbids
 * bundling the font, so it is never shipped inside the APK.
 */
public final class Typefaces {
    private static final String NTYPE_HEADLINE = "/system/fonts/NType82-Headline.otf";
    private static Typeface brand;

    private Typefaces() {
    }

    public static synchronized Typeface brand() {
        if(brand == null) {
            Typeface loaded = null;
            try {
                File file = new File(NTYPE_HEADLINE);
                if(file.isFile()) {
                    loaded = Typeface.createFromFile(file);
                }
            } catch(Throwable ignored) {
            }
            if(loaded == null) {
                loaded = Typeface.SERIF;
            }
            brand = loaded;
        }
        return brand;
    }

    public static void apply(TextView view) {
        if(view != null) {
            view.setTypeface(brand());
        }
    }
}
