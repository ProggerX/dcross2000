/*
 This file is part of DCross2000.

 DCross2000 is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 DCross2000 is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with DCross2000.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.proggerx.dcross2000.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.proggerx.dcross2000.domain.ArtistInfo;
import com.proggerx.dcross2000.domain.MusicDirectory;
import com.proggerx.dcross2000.service.MusicService;
import com.proggerx.dcross2000.service.MusicServiceFactory;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Small, UI-thread friendly loader for artist artwork in list rows.
 *
 * The generic {@link ImageLoader} path builds a placeholder bitmap for every
 * unknown artist on the calling thread, which is far too slow for list binds.
 * This loader keeps the row placeholder purely as a drawable, fetches only
 * real artist images on worker threads, and caches the result per artist id.
 */
public final class ArtistArtwork {
	private static final int MAX_CACHE_BYTES = 8 * 1024 * 1024;

	private static final LruCache<String, Drawable> cache = new LruCache<String, Drawable>(MAX_CACHE_BYTES) {
		@Override
		protected int sizeOf(String key, Drawable value) {
			Bitmap bitmap = value instanceof BitmapDrawable ? ((BitmapDrawable) value).getBitmap() : null;
			return bitmap == null ? 1 : bitmap.getByteCount();
		}
	};

	private static final Set<String> withoutArt = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
	private static final Set<String> loading = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
	private static final Handler mainHandler = new Handler(Looper.getMainLooper());

	private static final ExecutorService executor = Executors.newFixedThreadPool(2, new ThreadFactory() {
		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "ArtistArtwork");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		}
	});

	public interface Callback {
		void onArtwork(Drawable drawable);
	}

	private ArtistArtwork() {
	}

	/** Returns a cached image without touching the network, or {@code null}. */
	public static Drawable cached(String artistId) {
		return artistId == null ? null : cache.get(artistId);
	}

	/**
	 * Loads artwork for {@code artistId} if it is not cached yet. The callback
	 * runs on the main thread and is not called for artists without artwork.
	 */
	public static void load(Context context, final String artistId, final String artistName, final int size, final Callback callback) {
		if (artistId == null || context == null) {
			return;
		}

		Drawable cachedDrawable = cache.get(artistId);
		if (cachedDrawable != null) {
			callback.onArtwork(cachedDrawable);
			return;
		}

		if (withoutArt.contains(artistId) || loading.contains(artistId)) {
			return;
		}

		if (Util.isOffline(context)) {
			return;
		}

		loading.add(artistId);
		final Context appContext = context.getApplicationContext();
		executor.execute(new Runnable() {
			@Override
			public void run() {
				try {
					MusicService musicService = MusicServiceFactory.getMusicService(appContext);
					Bitmap bitmap = null;

					// Tag browsing exposes real artist ids, so ask the server for
					// a dedicated artist image first.
					if (Util.isTagBrowsing(appContext)) {
						ArtistInfo artistInfo = musicService.getArtistInfo(artistId, false, true, appContext, null);
						String url = artistInfo == null ? null : artistInfo.getImageUrl();
						if (url != null && !url.trim().isEmpty()) {
							bitmap = musicService.getBitmap(url, size, appContext, null, null);
						}
					}

					MusicDirectory.Entry artistEntry = new MusicDirectory.Entry(artistId);
					artistEntry.setDirectory(true);
					artistEntry.setTitle(artistName);
					artistEntry.setArtist(artistName);

					// Navidrome et al. serve artist artwork through getCoverArt
					// with the artist id.
					if (bitmap == null) {
						MusicDirectory.Entry coverEntry = new MusicDirectory.Entry(artistId);
						coverEntry.setDirectory(true);
						coverEntry.setTitle(artistName);
						coverEntry.setArtist(artistName);
						coverEntry.setCoverArt(artistId);
						try {
							bitmap = musicService.getCoverArt(appContext, coverEntry, size, null, null);
						} catch (Throwable ignored) {
							// Server may not expose artist artwork by id.
						}
					}

					if (bitmap == null) {
						// Fall back to the first child's cover art, like the
						// rest of the app does.
						MusicDirectory.Entry firstChild = FileUtil.lookupChild(appContext, artistEntry, true);
						if (firstChild == null || firstChild.getCoverArt() == null) {
							withoutArt.add(artistId);
							return;
						}
						bitmap = musicService.getCoverArt(appContext, firstChild, size, null, null);
					}

					if (bitmap == null) {
						return;
					}

					final Drawable drawable = Util.createDrawableFromBitmap(appContext, bitmap);
					cache.put(artistId, drawable);
					mainHandler.post(new Runnable() {
						@Override
						public void run() {
							callback.onArtwork(drawable);
						}
					});
				} catch (Throwable t) {
					// Keep the placeholder; the next bind retries.
				} finally {
					loading.remove(artistId);
				}
			}
		});
	}
}
