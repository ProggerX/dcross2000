package com.proggerx.dcross2000.mix;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * Remembers the transition drawn for an ordered pair of songs.
 *
 * Transitions are keyed by song ID, not by playlist position, so reordering a
 * playlist keeps every hand drawn fade attached to the two tracks it belongs to.
 */
public final class MixStore {
    private static final String PREFERENCES_NAME = "mix";
    private static final String PAIR_SEPARATOR = "->";

    private static MixStore instance;

    private final SharedPreferences preferences;

    private MixStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized MixStore get(Context context) {
        if(instance == null) {
            instance = new MixStore(context);
        }
        return instance;
    }

    public TransitionSpec get(String fromId, String toId) {
        if(fromId == null || toId == null) return null;
        return TransitionSpec.decode(preferences.getString(key(fromId, toId), null));
    }

    public boolean has(String fromId, String toId) {
        if(fromId == null || toId == null) return false;
        // Cheap enough to call for every bound row; does not parse the spec.
        return preferences.contains(key(fromId, toId));
    }

    public void put(TransitionSpec spec) {
        if(spec == null || spec.fromId == null || spec.toId == null) return;
        String encoded = spec.encode();
        if(encoded != null) {
            preferences.edit().putString(key(spec.fromId, spec.toId), encoded).apply();
        }
    }

    public void remove(String fromId, String toId) {
        if(fromId == null || toId == null) return;
        preferences.edit().remove(key(fromId, toId)).apply();
    }

    public int size() {
        return preferences.getAll().size();
    }

    /**
     * Drops malformed entries, e.g. after an interrupted write or a downgrade.
     */
    public synchronized int prune() {
        SharedPreferences.Editor editor = null;
        int removed = 0;
        for(Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String value = entry.getValue() instanceof String ? (String) entry.getValue() : null;
            if(value == null || TransitionSpec.decode(value) == null) {
                if(editor == null) editor = preferences.edit();
                editor.remove(entry.getKey());
                removed++;
            }
        }
        if(editor != null) editor.apply();
        return removed;
    }

    private static String key(String fromId, String toId) {
        return fromId + PAIR_SEPARATOR + toId;
    }
}
