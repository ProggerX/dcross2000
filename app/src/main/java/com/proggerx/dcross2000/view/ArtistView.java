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
import android.content.ContextWrapper;
import android.view.LayoutInflater;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.proggerx.dcross2000.R;
import com.proggerx.dcross2000.activity.SubsonicActivity;
import com.proggerx.dcross2000.domain.Artist;
import com.proggerx.dcross2000.domain.MusicDirectory;
import com.proggerx.dcross2000.util.FileUtil;
import com.proggerx.dcross2000.util.ImageLoader;
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
    private String loadedArtId;
    private Runnable pendingArtLoad;
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

    private void updateArtistArt(Artist artist) {
        if (artView == null || artist == null || artist.getId() == null) return;
        if (artist.getId().equals(loadedArtId)) return;

        // Show the placeholder immediately and do not start any image work while
        // the list is dragging or settling. A short delay then loads the rows
        // that are actually still visible once scrolling stops.
        showPlaceholder(artist);
        RecyclerView list = findList();
        if(list != null && list.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            if(pendingArtLoad != null) artHandler.removeCallbacks(pendingArtLoad);
            pendingArtLoad = () -> {
                pendingArtLoad = null;
                if(item != null && artist.getId().equals(item.getId())) updateArtistArt(artist);
            };
            artHandler.postDelayed(pendingArtLoad, 320);
            return;
        }

        loadedArtId = artist.getId();
        MusicDirectory.Entry entry = new MusicDirectory.Entry(artist.getId());
        entry.setDirectory(true);
        entry.setTitle(artist.getName());
        entry.setArtist(artist.getName());

        ImageLoader loader = findImageLoader();
        if (loader != null) loader.loadImage(artView, entry, false, false);
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
        View current = this;
        while(current != null) {
            android.view.ViewParent parent = current.getParent();
            if(parent instanceof RecyclerView) return (RecyclerView) parent;
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private ImageLoader findImageLoader() {
        Context current = getContext();
        while (current instanceof ContextWrapper) {
            if (current instanceof SubsonicActivity) return ((SubsonicActivity) current).getImageLoader();
            current = ((ContextWrapper) current).getBaseContext();
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
