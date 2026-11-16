/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package com.proggerx.dcross2000.view;

import android.content.Context;
import android.view.LayoutInflater;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import android.graphics.drawable.Drawable;

import com.proggerx.dcross2000.R;
import com.proggerx.dcross2000.domain.Artist;
import com.proggerx.dcross2000.util.ArtistArtwork;
import com.proggerx.dcross2000.util.FileUtil;
import com.proggerx.dcross2000.util.TouchDelegateUtil;

import java.io.File;

/**
 * Used to display albums in a {@code ListView}.
 *
 * @author Sindre Mehus
 */
public class ArtistView extends UpdateView<Artist> {
	private static final String TAG = ArtistView.class.getSimpleName();

	private File file;
    private TextView titleView;
    private RecyclingImageView artView;
    private String displayedArtId;
    private Runnable pendingArtLoad;
    private RecyclerView scrollHost;
    private static final Handler artHandler = new Handler(Looper.getMainLooper());
    private static final android.graphics.drawable.GradientDrawable[] PLACEHOLDERS = new android.graphics.drawable.GradientDrawable[5];
    private static final int[] PLACEHOLDER_COLORS = {0xFF5B8DEF, 0xFF8B7BD8, 0xFF4FA88B, 0xFFD08A5A, 0xFFC06C84};

    public ArtistView(Context context) {
        super(context);
        LayoutInflater.from(context).inflate(R.layout.artist_list_item, this, true);

        titleView = (TextView) findViewById(R.id.item_name);
        artView = (RecyclingImageView) findViewById(R.id.item_art);
		starButton = (ImageButton) findViewById(R.id.item_star);
		starButton.setFocusable(false);
		moreButton = (ImageView) findViewById(R.id.item_more);
		moreButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				v.showContextMenu();
			}
		});
        TouchDelegateUtil.expandTouchArea(moreButton);
    }
    
    protected void setObjectImpl(Artist artist) {
    	titleView.setText(artist.getName());
		file = FileUtil.getArtistDirectory(context, artist);
		updateArtistArt(artist);
    }

    /** Lets the adapter tell the row which list it is scrolling in. */
    public void setScrollHost(RecyclerView host) {
        scrollHost = host;
    }

    private void updateArtistArt(Artist artist) {
        if (artView == null || artist == null || artist.getId() == null) return;
        String artistId = artist.getId();
        if (artistId.equals(displayedArtId)) return;

        if(pendingArtLoad != null) {
            artHandler.removeCallbacks(pendingArtLoad);
            pendingArtLoad = null;
        }

        displayedArtId = artistId;
        showPlaceholder(artist);
        loadArt(artistId, artist.getName());
    }

    private void loadArt(final String artistId, final String artistName) {
        Drawable cached = ArtistArtwork.cached(artistId);
        if(cached != null) {
            artView.setImageDrawable(cached);
            return;
        }

        RecyclerView list = findList();
        if(list != null && list.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            // Only rows that are still on screen after the fling get to load.
            pendingArtLoad = new Runnable() {
                @Override
                public void run() {
                    pendingArtLoad = null;
                    if(item != null && artistId.equals(item.getId())) {
                        loadArt(artistId, artistName);
                    }
                }
            };
            artHandler.postDelayed(pendingArtLoad, 250);
            return;
        }

        ArtistArtwork.load(getContext(), artistId, artistName, artworkSize(), new ArtistArtwork.Callback() {
            @Override
            public void onArtwork(Drawable drawable) {
                if(item != null && artistId.equals(item.getId()) && artView != null) {
                    artView.setImageDrawable(drawable);
                }
            }
        });
    }

    private int artworkSize() {
        int size = (int)(64 * getResources().getDisplayMetrics().density);
        return Math.max(96, Math.min(size, 256));
    }

    private void showPlaceholder(Artist artist) {
        if(artView == null) return;
        if(artView.getDrawable() != null) {
            artView.setImageDrawable(null);
        }
        // Placeholders are shared per colour so a fling does not allocate a new
        // GradientDrawable for every bound row.
        int index = Math.abs(artist.getId().hashCode()) % PLACEHOLDER_COLORS.length;
        android.graphics.drawable.GradientDrawable placeholder = PLACEHOLDERS[index];
        if(placeholder == null) {
            placeholder = new android.graphics.drawable.GradientDrawable();
            placeholder.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            placeholder.setColor(PLACEHOLDER_COLORS[index]);
            PLACEHOLDERS[index] = placeholder;
        }
        artView.setBackground(placeholder);
    }

    private RecyclerView findList() {
        if(scrollHost != null) return scrollHost;
        View current = this;
        while(current != null) {
            android.view.ViewParent parent = current.getParent();
            if(parent instanceof RecyclerView) return (RecyclerView) parent;
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }
    
    @Override
	protected void updateBackground() {
		exists = file.exists();
		isStarred = item.isStarred();
	}

	public File getFile() {
		return file;
	}
}
