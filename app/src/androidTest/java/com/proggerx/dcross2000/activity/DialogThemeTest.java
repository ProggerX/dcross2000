package com.proggerx.dcross2000.activity;

import android.view.View;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.proggerx.dcross2000.R;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import org.junit.Rule;
import org.junit.Test;

/** Guards the AppCompat dialog path used by LoadingTask and other legacy callers. */
public class DialogThemeTest {
    @Rule
    public ActivityTestRule<SubsonicFragmentActivity> activity =
            new ActivityTestRule<>(SubsonicFragmentActivity.class);

    @Test
    public void materialLoadingDialogInflatesWithACustomView() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View view = activity.getActivity().getLayoutInflater().inflate(R.layout.dialog_loading, null);
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity.getActivity());
            builder.setView(view).setCancelable(true).show().dismiss();
        });
    }

    @Test
    public void appCompatAlertDialogInflatesWithTheActivityTheme() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AlertDialog dialog = new AlertDialog.Builder(activity.getActivity())
                    .setTitle("DCross2000")
                    .setMessage("dialog theme")
                    .create();
            dialog.show();
            dialog.dismiss();
        });
    }
}
