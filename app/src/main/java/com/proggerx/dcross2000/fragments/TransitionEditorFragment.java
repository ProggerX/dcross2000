package com.proggerx.dcross2000.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.material.button.MaterialButtonToggleGroup;

import java.util.Locale;

import com.proggerx.dcross2000.R;
import com.proggerx.dcross2000.domain.MusicDirectory;
import com.proggerx.dcross2000.mix.Curve;
import com.proggerx.dcross2000.mix.MixStore;
import com.proggerx.dcross2000.mix.TransitionPreviewPlayer;
import com.proggerx.dcross2000.mix.TransitionSpec;
import com.proggerx.dcross2000.mix.WaveformCache;
import com.proggerx.dcross2000.service.DownloadFile;
import com.proggerx.dcross2000.service.DownloadService;
import com.proggerx.dcross2000.util.Util;
import com.proggerx.dcross2000.view.TransitionCurveView;

/**
 * One zoomed surface for a transition: waveforms, both volume curves and both
 * tempo curves. The toggle groups choose which of the four curves the pencil
 * writes; the others stay visible as references.
 */
public class TransitionEditorFragment extends SubsonicFragment {
    private static final String ARG_FROM = "mix.from";
    private static final String ARG_TO = "mix.to";

    private static final int BAR_STEP_MS = 100;
    private static final int MAX_BAR_MS = 60000;

    private MusicDirectory.Entry fromEntry;
    private MusicDirectory.Entry toEntry;
    private DownloadFile fromFile;
    private DownloadFile toFile;
    private int fromDurationMs;
    private int toDurationMs;

    private TransitionCurveView curveView;
    private SeekBar leadBar;
    private SeekBar overlapBar;
    private SeekBar bStartBar;
    private SeekBar tailBar;
    private TextView leadLabel;
    private TextView overlapLabel;
    private TextView bStartLabel;
    private TextView tailLabel;
    private TextView warning;
    private Button previewButton;
    private Button deleteButton;
    private MaterialButtonToggleGroup sideGroup;
    private MaterialButtonToggleGroup parameterGroup;

    private float[] fromPeaks;
    private float[] toPeaks;
    private boolean binding;
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
        leadBar = rootView.findViewById(R.id.transition_lead);
        overlapBar = rootView.findViewById(R.id.transition_overlap);
        bStartBar = rootView.findViewById(R.id.transition_bstart);
        tailBar = rootView.findViewById(R.id.transition_tail);
        leadLabel = rootView.findViewById(R.id.transition_lead_label);
        overlapLabel = rootView.findViewById(R.id.transition_overlap_label);
        bStartLabel = rootView.findViewById(R.id.transition_bstart_label);
        tailLabel = rootView.findViewById(R.id.transition_tail_label);
        warning = rootView.findViewById(R.id.transition_warning);
        previewButton = rootView.findViewById(R.id.transition_preview);
        deleteButton = rootView.findViewById(R.id.transition_delete);
        sideGroup = rootView.findViewById(R.id.transition_side);
        parameterGroup = rootView.findViewById(R.id.transition_parameter);

        ((TextView) rootView.findViewById(R.id.transition_from_title)).setText(fromEntry.getTitle());
        ((TextView) rootView.findViewById(R.id.transition_to_title)).setText(toEntry.getTitle());

        leadBar.setMax(MAX_BAR_MS / BAR_STEP_MS);
        overlapBar.setMax(MAX_BAR_MS / BAR_STEP_MS);
        bStartBar.setMax(MAX_BAR_MS / BAR_STEP_MS);
        tailBar.setMax(MAX_BAR_MS / BAR_STEP_MS);

        SeekBar.OnSeekBarChangeListener seekListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if(!binding) updateLabels();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
        leadBar.setOnSeekBarChangeListener(seekListener);
        overlapBar.setOnSeekBarChangeListener(seekListener);
        bStartBar.setOnSeekBarChangeListener(seekListener);
        tailBar.setOnSeekBarChangeListener(seekListener);

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

        previewButton.setOnClickListener(view -> togglePreview());
        rootView.findViewById(R.id.transition_reset).setOnClickListener(view -> {
            curveView.setCurves(Curve.defaultOut().copyValues(), Curve.defaultIn().copyValues(),
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
        bind(saved == null ? TransitionSpec.defaults(fromEntry.getId(), toEntry.getId()) : saved);

        boolean cached = fromFile.isCompleteFileAvailable() && toFile.isCompleteFileAvailable();
        warning.setVisibility(cached ? View.GONE : View.VISIBLE);
        previewButton.setEnabled(cached);
        if(cached) {
            WaveformCache.load(context, fromFile, peaks -> {
                fromPeaks = peaks;
                updateLabels();
            });
            WaveformCache.load(context, toFile, peaks -> {
                toPeaks = peaks;
                updateLabels();
            });
        }

        setTitle(R.string.mix_edit_title);
        setSubtitle(fromEntry.getTitle() + " → " + toEntry.getTitle());
        return rootView;
    }

    @Override
    public void onStop() {
        super.onStop();
        if(preview != null) preview.stop();
        if(curveView != null) curveView.setPlayhead(-1f);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if(preview != null) {
            preview.release();
            preview = null;
        }
    }

    private void bind(TransitionSpec spec) {
        binding = true;
        leadBar.setProgress(Math.max(0, Math.min(MAX_BAR_MS / BAR_STEP_MS, spec.leadMs / BAR_STEP_MS)));
        overlapBar.setProgress(Math.max(TransitionSpec.MIN_FADE_MS / BAR_STEP_MS,
                Math.min(MAX_BAR_MS / BAR_STEP_MS, spec.fadeMs / BAR_STEP_MS)));
        bStartBar.setProgress(Math.max(0, Math.min(MAX_BAR_MS / BAR_STEP_MS, spec.bStartMs / BAR_STEP_MS)));
        tailBar.setProgress(Math.max(0, Math.min(MAX_BAR_MS / BAR_STEP_MS, spec.tailMs / BAR_STEP_MS)));
        curveView.setCurves(spec.out.copyValues(), spec.in.copyValues(),
                spec.outTempo.copyValues(), spec.inTempo.copyValues());
        binding = false;
        updateLabels();
    }

    private TransitionSpec currentSpec() {
        int lead = leadBar.getProgress() * BAR_STEP_MS;
        int overlap = Math.max(TransitionSpec.MIN_FADE_MS, overlapBar.getProgress() * BAR_STEP_MS);
        int bStart = bStartBar.getProgress() * BAR_STEP_MS;
        int tail = tailBar.getProgress() * BAR_STEP_MS;
        return new TransitionSpec(fromEntry.getId(), toEntry.getId(), lead, overlap, tail, bStart,
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

    private void updateLabels() {
        TransitionSpec spec = currentSpec();
        leadLabel.setText(getString(R.string.mix_lead_label, seconds(spec.leadMs)));
        overlapLabel.setText(getString(R.string.mix_overlap_label, seconds(spec.fadeMs)));
        bStartLabel.setText(getString(R.string.mix_bstart_label, seconds(spec.bStartMs)));
        tailLabel.setText(getString(R.string.mix_tail_label, seconds(spec.tailMs)));
        updateWaveforms(spec);
    }

    private void updateWaveforms(TransitionSpec spec) {
        int total = Math.max(1, spec.windowMs());
        curveView.setSpans(0f, spec.outgoingWindowMs() / (float) total,
                spec.leadMs / (float) total, 1f);
        curveView.setWaveforms(outgoingPeaks(spec), incomingPeaks(spec));
    }

    private float[] outgoingPeaks(TransitionSpec spec) {
        if(fromPeaks == null || fromDurationMs <= 0) return null;
        int window = Math.min(fromDurationMs, Math.max(1, spec.outgoingWindowMs()));
        float from = (fromDurationMs - window) / (float) fromDurationMs;
        return WaveformCache.slice(fromPeaks, from, 1f);
    }

    private float[] incomingPeaks(TransitionSpec spec) {
        if(toPeaks == null || toDurationMs <= 0) return null;
        int start = Math.min(toDurationMs, Math.max(0, spec.bStartMs));
        int end = Math.min(toDurationMs, start + spec.incomingWindowMs());
        if(end <= start) return null;
        return WaveformCache.slice(toPeaks, start / (float) toDurationMs, end / (float) toDurationMs);
    }

    private void togglePreview() {
        if(preview != null && preview.isRunning()) {
            preview.stop();
            return;
        }
        TransitionSpec spec = currentSpec();
        if(!fromFile.isCompleteFileAvailable() || !toFile.isCompleteFileAvailable()) {
            Util.toast(context, R.string.mix_requires_cache);
            return;
        }
        if(fromDurationMs <= spec.outgoingWindowMs()) {
            Util.toast(context, R.string.mix_unknown_duration);
            return;
        }
        DownloadService service = DownloadService.getInstance();
        if(service != null) service.pause();
        if(preview == null) {
            preview = new TransitionPreviewPlayer(context);
            preview.setListener(fraction -> curveView.setPlayhead(fraction));
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

    private static String seconds(int millis) {
        return String.format(Locale.US, "%.1f s", millis / 1000f);
    }
}
