package com.proggerx.dcross2000.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import com.proggerx.dcross2000.R;
import com.proggerx.dcross2000.domain.MusicDirectory;
import com.proggerx.dcross2000.mix.Curve;
import com.proggerx.dcross2000.mix.MixStore;
import com.proggerx.dcross2000.mix.TransitionPreviewPlayer;
import com.proggerx.dcross2000.mix.TransitionSpec;
import com.proggerx.dcross2000.mix.WaveformCache;
import com.proggerx.dcross2000.service.DownloadFile;
import com.proggerx.dcross2000.service.DownloadService;
import com.proggerx.dcross2000.util.Typefaces;
import com.proggerx.dcross2000.util.Util;
import com.proggerx.dcross2000.view.MixWheelView;
import com.proggerx.dcross2000.view.TransitionCurveView;

/**
 * One zoomed surface for a transition: waveforms, both volume curves and both
 * tempo curves. The toggle groups choose which of the four curves the pencil
 * writes; the others stay visible as references.
 */
public class TransitionEditorFragment extends SubsonicFragment {
    private static final String ARG_FROM = "mix.from";
    private static final String ARG_TO = "mix.to";

    private static final int WHEEL_STEP_MS = 10;
    private static final int WHEEL_MAX_MS = 60000;

    private MusicDirectory.Entry fromEntry;
    private MusicDirectory.Entry toEntry;
    private DownloadFile fromFile;
    private DownloadFile toFile;
    private int fromDurationMs;
    private int toDurationMs;

    private TransitionCurveView curveView;
    private MixWheelView leadWheel;
    private MixWheelView overlapWheel;
    private MixWheelView bStartWheel;
    private MixWheelView tailWheel;
    private MixWheelView aEndWheel;
    private TextView leadLabel;
    private TextView overlapLabel;
    private TextView bStartLabel;
    private TextView tailLabel;
    private TextView aEndLabel;
    private TextView warning;
    private MaterialButton previewButton;
    private Button deleteButton;
    private MaterialButtonToggleGroup sideGroup;
    private MaterialButtonToggleGroup parameterGroup;

    private WaveformCache.Waveform fromWaveform;
    private WaveformCache.Waveform toWaveform;
    private boolean filesCached;
    private final Handler waveHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingWaveLoad;
    private int waveToken;
    private int fromWaveStart = -1;
    private int fromWaveEnd = -1;
    private int toWaveStart = -1;
    private int toWaveEnd = -1;
    private TransitionPreviewPlayer preview;

    public static Bundle args(MusicDirectory.Entry from, MusicDirectory.Entry to) {
        Bundle bundle = new Bundle();
        bundle.putSerializable(ARG_FROM, from);
        bundle.putSerializable(ARG_TO, to);
        return bundle;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle bundle) {
        rootView = inflater.inflate(R.layout.transition_editor, container, false);

        Bundle arguments = getArguments();
        fromEntry = arguments == null ? null : (MusicDirectory.Entry) arguments.getSerializable(ARG_FROM);
        toEntry = arguments == null ? null : (MusicDirectory.Entry) arguments.getSerializable(ARG_TO);
        if(fromEntry == null || toEntry == null || fromEntry.getId() == null || toEntry.getId() == null) {
            Util.toast(context, R.string.mix_invalid_pair);
            context.onBackPressed();
            return rootView;
        }

        fromDurationMs = fromEntry.getDuration() == null ? 0 : fromEntry.getDuration() * 1000;
        toDurationMs = toEntry.getDuration() == null ? 0 : toEntry.getDuration() * 1000;
        fromFile = new DownloadFile(context, fromEntry, false);
        toFile = new DownloadFile(context, toEntry, false);

        curveView = rootView.findViewById(R.id.transition_curve);
        leadWheel = rootView.findViewById(R.id.transition_lead);
        overlapWheel = rootView.findViewById(R.id.transition_overlap);
        bStartWheel = rootView.findViewById(R.id.transition_bstart);
        tailWheel = rootView.findViewById(R.id.transition_tail);
        aEndWheel = rootView.findViewById(R.id.transition_aend);
        leadLabel = rootView.findViewById(R.id.transition_lead_label);
        overlapLabel = rootView.findViewById(R.id.transition_overlap_label);
        bStartLabel = rootView.findViewById(R.id.transition_bstart_label);
        tailLabel = rootView.findViewById(R.id.transition_tail_label);
        aEndLabel = rootView.findViewById(R.id.transition_aend_label);
        warning = rootView.findViewById(R.id.transition_warning);
        previewButton = (MaterialButton) rootView.findViewById(R.id.transition_preview);
        deleteButton = rootView.findViewById(R.id.transition_delete);
        sideGroup = rootView.findViewById(R.id.transition_side);
        parameterGroup = rootView.findViewById(R.id.transition_parameter);

        TextView fromTitle = rootView.findViewById(R.id.transition_from_title);
        TextView toTitle = rootView.findViewById(R.id.transition_to_title);
        fromTitle.setText(fromEntry.getTitle());
        toTitle.setText(toEntry.getTitle());
        Typefaces.apply(fromTitle);
        Typefaces.apply(toTitle);

        leadLabel.setText(R.string.mix_lead_title);
        overlapLabel.setText(R.string.mix_overlap_title);
        tailLabel.setText(R.string.mix_tail_title);
        aEndLabel.setText(R.string.mix_aend_title);
        bStartLabel.setText(R.string.mix_bstart_title);

        leadWheel.setRange(0, WHEEL_MAX_MS, WHEEL_STEP_MS);
        overlapWheel.setRange(TransitionSpec.MIN_FADE_MS, WHEEL_MAX_MS, WHEEL_STEP_MS);
        bStartWheel.setRange(0, WHEEL_MAX_MS, WHEEL_STEP_MS);
        tailWheel.setRange(0, WHEEL_MAX_MS, WHEEL_STEP_MS);
        aEndWheel.setRange(0, WHEEL_MAX_MS, WHEEL_STEP_MS);
        aEndWheel.setNegativeDisplay(true);

        MixWheelView.OnValueChangedListener timingListener = value -> updateLabels();
        leadWheel.setOnValueChangedListener(timingListener);
        overlapWheel.setOnValueChangedListener(timingListener);
        bStartWheel.setOnValueChangedListener(timingListener);
        tailWheel.setOnValueChangedListener(timingListener);
        aEndWheel.setOnValueChangedListener(timingListener);

        sideGroup.check(R.id.transition_side_outgoing);
        parameterGroup.check(R.id.transition_parameter_volume);
        curveView.setMode(TransitionCurveView.Side.OUTGOING, TransitionCurveView.Parameter.VOLUME);
        sideGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if(!isChecked) return;
            curveView.setMode(checkedId == R.id.transition_side_outgoing
                    ? TransitionCurveView.Side.OUTGOING : TransitionCurveView.Side.INCOMING, parameter());
        });
        parameterGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if(!isChecked) return;
            curveView.setMode(side(), parameter());
        });

        previewButton.setOnClickListener(view -> onPreviewButton());
        previewButton.setOnLongClickListener(view -> {
            // Long press stops the preview entirely.
            if(preview != null) preview.stop();
            updatePreviewIcon();
            return true;
        });
        rootView.findViewById(R.id.transition_reset).setOnClickListener(view -> {
            curveView.setCurves(TransitionSpec.defaultOutVolume().copyValues(),
                    TransitionSpec.defaultInVolume().copyValues(),
                    TransitionSpec.defaultTempoCurve().copyValues(),
                    TransitionSpec.defaultTempoCurve().copyValues());
            updateLabels();
        });
        deleteButton.setOnClickListener(view -> {
            MixStore.get(context).remove(fromEntry.getId(), toEntry.getId());
            rearm();
            Util.toast(context, R.string.mix_deleted);
            context.onBackPressed();
        });
        rootView.findViewById(R.id.transition_save).setOnClickListener(view -> save());

        MixStore store = MixStore.get(context);
        TransitionSpec saved = store.get(fromEntry.getId(), toEntry.getId());
        deleteButton.setEnabled(saved != null);
        filesCached = fromFile.isCompleteFileAvailable() && toFile.isCompleteFileAvailable();
        previewButton.setEnabled(filesCached);
        bind(saved == null ? TransitionSpec.defaults(fromEntry.getId(), toEntry.getId()) : saved);
        updateWaveformStatus();

        // Metadata durations are rounded to whole seconds; read the real file
        // duration so the fade ends exactly where the audio ends.
        WaveformCache.loadDuration(fromFile, duration -> {
            if(duration > 0 && duration != fromDurationMs && curveView != null) {
                fromDurationMs = duration;
                requestWaves();
            }
        });
        WaveformCache.loadDuration(toFile, duration -> {
            if(duration > 0 && duration != toDurationMs && curveView != null) {
                toDurationMs = duration;
                requestWaves();
            }
        });

        setTitle(R.string.mix_edit_title);
        setSubtitle(fromEntry.getTitle() + " → " + toEntry.getTitle());
        return rootView;
    }

    @Override
    public void onStop() {
        super.onStop();
        if(preview != null) preview.stop();
        if(curveView != null) curveView.setPlayhead(-1f);
        updatePreviewIcon();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        waveToken++;
        if(pendingWaveLoad != null) {
            waveHandler.removeCallbacks(pendingWaveLoad);
            pendingWaveLoad = null;
        }
        if(preview != null) {
            preview.release();
            preview = null;
        }
    }

    private void bind(TransitionSpec spec) {
        leadWheel.setValue(spec.leadMs);
        overlapWheel.setValue(spec.fadeMs);
        bStartWheel.setValue(spec.bStartMs);
        tailWheel.setValue(spec.tailMs);
        aEndWheel.setValue(spec.aEndMs);
        curveView.setCurves(spec.out.copyValues(), spec.in.copyValues(),
                spec.outTempo.copyValues(), spec.inTempo.copyValues());
        updateLabels();
    }

    private TransitionSpec currentSpec() {
        int lead = leadWheel.getValue();
        int overlap = Math.max(TransitionSpec.MIN_FADE_MS, overlapWheel.getValue());
        int bStart = bStartWheel.getValue();
        int tail = tailWheel.getValue();
        int aEnd = aEndWheel.getValue();
        return new TransitionSpec(fromEntry.getId(), toEntry.getId(), lead, overlap, tail, bStart, aEnd,
                Curve.of(curveView.getOutVolume()), Curve.of(curveView.getInVolume()),
                Curve.of(curveView.getOutTempo()), Curve.of(curveView.getInTempo()));
    }

    private TransitionCurveView.Side side() {
        return sideGroup.getCheckedButtonId() == R.id.transition_side_incoming
                ? TransitionCurveView.Side.INCOMING : TransitionCurveView.Side.OUTGOING;
    }

    private TransitionCurveView.Parameter parameter() {
        return parameterGroup.getCheckedButtonId() == R.id.transition_parameter_tempo
                ? TransitionCurveView.Parameter.TEMPO : TransitionCurveView.Parameter.VOLUME;
    }

    private void updateWaveformStatus() {
        if(warning == null) return;
        if(!filesCached) {
            warning.setText(R.string.mix_requires_cache);
            warning.setVisibility(View.VISIBLE);
            return;
        }
        TransitionSpec spec = currentSpec();
        boolean ready = waveformMatches(fromWaveform, fromWaveStart, fromWaveEnd,
                windowFromStart(spec), windowFromEnd(spec))
                && waveformMatches(toWaveform, toWaveStart, toWaveEnd,
                windowToStart(spec), windowToEnd(spec));
        if(ready) {
            warning.setVisibility(View.GONE);
        } else {
            warning.setText(R.string.mix_analyzing);
            warning.setVisibility(View.VISIBLE);
        }
    }

    private static boolean waveformMatches(WaveformCache.Waveform waveform,
                                           int loadedStart, int loadedEnd,
                                           int wantedStart, int wantedEnd) {
        return waveform != null && !waveform.isEmpty()
                && loadedStart == wantedStart && loadedEnd == wantedEnd;
    }

    private void updateLabels() {
        updateWaveforms(currentSpec());
    }

    private void updateWaveforms(TransitionSpec spec) {
        int total = Math.max(1, spec.windowMs());
        curveView.setSpans(0f, spec.outgoingWindowMs() / (float) total,
                spec.leadMs / (float) total, 1f);

        // Dragging a slider changes the window constantly; only decode once the
        // finger has settled.
        if(pendingWaveLoad != null) {
            waveHandler.removeCallbacks(pendingWaveLoad);
        }
        pendingWaveLoad = this::requestWaves;
        waveHandler.postDelayed(pendingWaveLoad, 120);
    }

    private void requestWaves() {
        pendingWaveLoad = null;
        if(!filesCached) return;

        TransitionSpec spec = currentSpec();
        final int requestToken = ++waveToken;
        final int fromStart = windowFromStart(spec);
        final int fromEnd = windowFromEnd(spec);
        final int toStart = windowToStart(spec);
        final int toEnd = windowToEnd(spec);

        if(fromEnd > fromStart) {
            WaveformCache.loadRange(context, fromFile, fromStart, fromEnd, waveform -> {
                if(requestToken != waveToken) return;
                fromWaveform = waveform;
                fromWaveStart = fromStart;
                fromWaveEnd = fromEnd;
                applyWaves();
                updateWaveformStatus();
            });
        } else {
            fromWaveform = null;
            fromWaveStart = fromWaveEnd = -1;
        }

        if(toEnd > toStart) {
            WaveformCache.loadRange(context, toFile, toStart, toEnd, waveform -> {
                if(requestToken != waveToken) return;
                toWaveform = waveform;
                toWaveStart = toStart;
                toWaveEnd = toEnd;
                applyWaves();
                updateWaveformStatus();
            });
        } else {
            toWaveform = null;
            toWaveStart = toWaveEnd = -1;
        }
    }

    private void applyWaves() {
        TransitionSpec spec = currentSpec();
        curveView.setWaveforms(
                waveformMatches(fromWaveform, fromWaveStart, fromWaveEnd,
                        windowFromStart(spec), windowFromEnd(spec)) ? fromWaveform : null,
                waveformMatches(toWaveform, toWaveStart, toWaveEnd,
                        windowToStart(spec), windowToEnd(spec)) ? toWaveform : null);
    }

    private int windowFromStart(TransitionSpec spec) {
        if(fromDurationMs <= 0) return 0;
        return Math.max(0, fromDurationMs - spec.aEndMs - Math.max(1, spec.outgoingWindowMs()));
    }

    private int windowFromEnd(TransitionSpec spec) {
        return Math.max(0, fromDurationMs - spec.aEndMs);
    }

    private int windowToStart(TransitionSpec spec) {
        return Math.max(0, spec.bStartMs);
    }

    private int windowToEnd(TransitionSpec spec) {
        int end = Math.max(0, spec.bStartMs) + spec.incomingWindowMs();
        if(toDurationMs > 0) {
            end = Math.min(toDurationMs, end);
        }
        return end;
    }

    private void onPreviewButton() {
        if(preview != null && preview.isPlaying()) {
            preview.pause();
            updatePreviewIcon();
            return;
        }
        if(preview != null && preview.isPaused()) {
            preview.resume();
            updatePreviewIcon();
            return;
        }
        startPreview();
        updatePreviewIcon();
    }

    private void updatePreviewIcon() {
        if(previewButton == null) return;
        boolean playing = preview != null && preview.isPlaying();
        previewButton.setIconResource(playing ? R.drawable.ic_ms_pause : R.drawable.ic_ms_play);
        previewButton.setContentDescription(getString(
                playing ? R.string.mix_preview_pause : R.string.mix_preview_play));
    }

    private void startPreview() {
        TransitionSpec spec = currentSpec();
        if(!fromFile.isCompleteFileAvailable() || !toFile.isCompleteFileAvailable()) {
            Util.toast(context, R.string.mix_requires_cache);
            return;
        }
        if(fromDurationMs <= spec.aEndMs + spec.outgoingWindowMs()) {
            Util.toast(context, R.string.mix_unknown_duration);
            return;
        }
        DownloadService service = DownloadService.getInstance();
        if(service != null) service.pause();
        if(preview == null) {
            preview = new TransitionPreviewPlayer(context);
            preview.setListener(fraction -> {
                curveView.setPlayhead(fraction);
                updatePreviewIcon();
            });
            preview.setSpecProvider(this::currentSpec);
        }
        preview.play(fromFile.getCompleteFile(), fromDurationMs, toFile.getCompleteFile(), spec);
    }

    private void save() {
        MixStore.get(context).put(currentSpec());
        rearm();
        Util.toast(context, R.string.mix_saved);
        context.onBackPressed();
    }

    /** Applies the edit immediately when this pair is the one already playing. */
    private void rearm() {
        DownloadService service = DownloadService.getInstance();
        if(service != null) service.rearmTransition();
    }

}
