package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final ImageView image;
    private TextureView videoTexture;
    private MediaPlayer videoPlayer;
    private Surface videoSurface;
    private boolean videoPrepared;
    private final Handler videoHandler = new Handler(Looper.getMainLooper());
    private final Runnable scheduleBoundary = new Runnable() {
        @Override public void run() {
            if (!active || closed || !scheduledMode) return;
            update();
            scheduleNextBoundary();
        }
    };
    private Runnable videoWatchdog;
    private Runnable videoRetry;
    private int videoRetryCount;
    private int videoLastPosition = -1;
    private int videoStallChecks;
    private File videoFile;
    private final int backgroundColor;
    private final List<File> files;
    private final List<File> scheduledFiles;
    private final boolean scheduledMode;
    private final File scheduleDirectory;
    private WallpaperSchedule schedule;
    private long scheduleModified = Long.MIN_VALUE;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    private AnimatedImageDrawable currentAnimation;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    private String displayedPath = "";
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private final Map<String, String> names;
    /** wallpaper_moods: the collection's groups by time of day; empty rotates through all of it. */
    private final Map<String, Set<String>> moods;
    /** config.xml latitude and longitude for the sun's times, and today's {sunrise, sunset} once worked out. */
    private final String latitude, longitude;
    private LocalDate sunDay;
    private int[] sun;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        backgroundColor = config.backgroundColor;
        files = new ArrayList<>(config.wallpapers);
        scheduledMode = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getBoolean("scheduled_wallpaper_enabled", false);
        File themeDirectory = Config.dir(context);
        scheduleDirectory = themeDirectory == null ? null : new File(themeDirectory, "wallpapers");
        scheduledFiles = scheduledMode
                ? new ArrayList<>(WallpaperRotation.images(scheduleDirectory)) : new ArrayList<>();
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        moods = config.wallpaperMoods;
        latitude = moods.isEmpty() ? "" : context.getString(R.string.latitude);
        longitude = moods.isEmpty() ? "" : context.getString(R.string.longitude);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        names = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        caption = names.isEmpty() ? null : makeCaption(context, root);
        if ((config.wallpaperEnabled && !files.isEmpty()) || !scheduledFiles.isEmpty()) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            showFirst();
        }
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        File first = scheduledWallpaper();
        boolean fromSchedule = first != null;
        if (!fromSchedule) {
            List<File> playable = playable();
            first = WallpaperRotation.find(playable, previous);
            if (first == null) first = WallpaperRotation.pick(playable, previous, order, random);
        }
        while (first != null) {
            if (isMp4(first)) {
                showVideo(first);
                return;
            }
            if (isGif(first)) {
                AnimatedImageDrawable gif = decodeGif(first);
                if (gif != null) {
                    show(gif);
                    displayed = first.getName();
                    displayedPath = first.getAbsolutePath();
                    showCaption(false);
                    if (!fromSchedule && !displayed.equals(previous)) {
                        preferences.edit().putString("image", displayed)
                                .putLong("changed", System.currentTimeMillis()).apply();
                    }
                    return;
                }
            } else {
                Bitmap bitmap = decode(first);
                if (bitmap != null) {
                    show(bitmap);
                    root.setBackground(null);
                    displayed = first.getName();
                    displayedPath = first.getAbsolutePath();
                    showCaption(false);
                    if (!fromSchedule && !displayed.equals(previous)) {
                        preferences.edit().putString("image", displayed)
                                .putLong("changed", System.currentTimeMillis()).apply();
                    }
                    return;
                }
            }
            if (fromSchedule) {
                scheduledFiles.remove(first);
                fromSchedule = false;
                List<File> playable = playable();
                first = WallpaperRotation.find(playable, previous);
                if (first == null) first = WallpaperRotation.pick(playable, previous, order, random);
            } else {
                files.remove(first);
                List<File> playable = playable();
                first = WallpaperRotation.pick(playable, previous, order, random);
            }
        }
    }

    void start() {
        active = true;
        if (scheduledMode) scheduleNextBoundary();
        startAnimation(currentAnimation);
        if (videoPlayer != null && videoPrepared) {
            try {
                if (!videoPlayer.isPlaying()) videoPlayer.start();
                if (videoTexture != null && videoFile != null) scheduleVideoWatchdog(videoTexture, videoFile);
            } catch (Exception failure) {
                if (videoTexture != null && videoFile != null)
                    scheduleVideoRetry(videoTexture, videoFile, "resume failure");
            }
        } else if (videoTexture != null && videoFile != null && videoTexture.isAvailable()) {
            prepareVideo(videoTexture, videoTexture.getSurfaceTexture(), videoFile);
        }
        update();
    }

    void stop() {
        active = false;
        videoHandler.removeCallbacks(scheduleBoundary);
        cancelVideoWatchdog();
        stopAnimation(currentAnimation);
        if (videoPlayer != null && videoPrepared && videoPlayer.isPlaying()) videoPlayer.pause();
    }

    /** Drops the wallpaper texture when Home is hidden and Android asks the process to free memory. */
    void releaseForMemoryPressure() {
        if (current == null && currentAnimation == null && videoTexture == null && videoPlayer == null) return;
        releaseVideo();
        stopAnimation(currentAnimation);
        currentAnimation = null;
        image.setImageDrawable(null);
        // The renderer may still have a queued frame that references this hardware bitmap. Drop our references and
        // let Android reclaim its native buffer after rendering releases it instead of recycling it mid-frame.
        current = null;
        displayed = "";
        displayedPath = "";
        root.setBackgroundColor(backgroundColor);
    }

    /** The pictures the rotation picks from now: wallpaper_moods' group for the time of day, or the whole collection. */
    private File scheduledWallpaper() {
        if (!scheduledMode || scheduleDirectory == null) return null;
        File manifest = new File(scheduleDirectory, WallpaperSchedule.FILE_NAME);
        long modified = manifest.isFile() ? manifest.lastModified() : -1L;
        if (schedule == null || modified != scheduleModified) {
            schedule = WallpaperSchedule.load(scheduleDirectory);
            scheduleModified = modified;
        }
        java.time.LocalTime now = java.time.LocalTime.now();
        File selected = schedule.at(now.getHour() * 60 + now.getMinute());
        return selected != null && scheduledFiles.contains(selected) ? selected : null;
    }

    private List<File> playable() {
        if (moods.isEmpty()) return files;
        LocalDateTime now = LocalDateTime.now();
        if (!now.toLocalDate().equals(sunDay)) {
            sunDay = now.toLocalDate();
            sun = WallpaperMood.sun(sunDay, latitude, longitude, ZoneId.systemDefault());
        }
        return WallpaperMood.select(files, moods, WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    void close() {
        active = false;
        closed = true;
        videoHandler.removeCallbacks(scheduleBoundary);
        stopAnimation(currentAnimation);
        currentAnimation = null;
        releaseVideo();
        decoder.shutdownNow();
    }

    void onTimeChanged() {
        update();
        if (scheduledMode && active) scheduleNextBoundary();
    }

    private void scheduleNextBoundary() {
        videoHandler.removeCallbacks(scheduleBoundary);
        if (!active || closed || !scheduledMode) return;
        long remainder = System.currentTimeMillis() % 60_000L;
        videoHandler.postDelayed(scheduleBoundary, 60_000L - remainder + 25L);
    }

    void update() {
        if (!active || closed || loading || (files.isEmpty() && scheduledFiles.isEmpty())) return;
        String previous = preferences.getString("image", "");
        File scheduled = scheduledWallpaper();
        boolean fromSchedule = scheduled != null;
        File selected = scheduled;
        long changed = preferences.getLong("changed", 0);
        if (!fromSchedule) {
            // A picture outside the time of day's group (the group changed) is replaced at once, not after the interval.
            List<File> playable = playable();
            selected = WallpaperRotation.find(playable, previous);
            if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
                selected = WallpaperRotation.pick(playable, previous, order, random);
            }
        }
        if (selected == null || selected.getAbsolutePath().equals(displayedPath)) return;
        File next = selected;
        final boolean scheduledSelection = fromSchedule;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = (isGif(next) || isMp4(next)) ? null : decode(next);
            AnimatedImageDrawable gif = isGif(next) ? decodeGif(next) : null;
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    stopAnimation(gif);
                    return;
                }
                if (isMp4(next)) { showVideo(next); displayed = next.getName(); return; }
                if (bitmap == null && gif == null) {
                    if (scheduledSelection) scheduledFiles.remove(next); else files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                if (gif != null) show(gif); else show(bitmap);
                displayed = next.getName();
                displayedPath = next.getAbsolutePath();
                showCaption(true);
                if (!scheduledSelection && (!displayed.equals(previous) || changed == 0)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits where the
     * top bar ends in every layout, so it does not cover the clock, the stats line or the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    // AnimatedImageDrawable exists only from API 28; keep the launcher compatible with API 26/27.
    private static void startAnimation(AnimatedImageDrawable drawable) {
        if (Build.VERSION.SDK_INT >= 28 && drawable != null) drawable.start();
    }

    private static void stopAnimation(AnimatedImageDrawable drawable) {
        if (Build.VERSION.SDK_INT >= 28 && drawable != null) drawable.stop();
    }

    private static boolean isMp4(File file) {
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".mp4")) return true;
        try (java.io.InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(file))) {
            byte[] h = new byte[12];
            int n = in.read(h);
            return n == 12 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p';
        } catch (Exception ignored) { return false; }
    }

    private void showVideo(File file) {
        displayed = file.getName();
        displayedPath = file.getAbsolutePath();
        releaseVideo();
        stopAnimation(currentAnimation);
        currentAnimation = null;
        if (current != null) { current.recycle(); current = null; }
        image.setImageDrawable(null);
        videoPrepared = false;
        videoFile = file;

        // TextureView participates in normal View z-order. SurfaceView/VideoView can be
        // composited above the launcher window on some TV firmwares, hiding all Home UI.
        TextureView texture = new TextureView(root.getContext());
        texture.setFocusable(false);
        videoTexture = texture;
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                prepareVideo(texture, surfaceTexture, file);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                if (videoTexture == texture) { cancelVideoTasks(); releaseVideoPlayerOnly(); }
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) { }
        });
        root.addView(texture, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackground(null);
        image.setForeground(dimColor == 0 ? null : new ColorDrawable(dimColor));
    }

    private void prepareVideo(TextureView texture, SurfaceTexture surfaceTexture, File file) {
        if (closed || videoTexture != texture) return;
        try {
            Surface surface = new Surface(surfaceTexture);
            MediaPlayer player = new MediaPlayer();
            videoSurface = surface;
            videoPlayer = player;
            player.setSurface(surface);
            player.setDataSource(file.getAbsolutePath());
            player.setVolume(0f, 0f);
            player.setLooping(true);
            player.setOnPreparedListener(mp -> {
                if (videoPlayer != player || closed) { mp.release(); return; }
                videoPrepared = true;
                videoRetryCount = 0;
                videoStallChecks = 0;
                videoLastPosition = -1;
                texture.setVisibility(View.VISIBLE);
                if (active) mp.start();
                Log.i("TvHome.Wallpaper", "MP4 wallpaper prepared: " + file.getName());
                scheduleVideoWatchdog(texture, file);
            });
            player.setOnErrorListener((mp, what, extra) -> {
                if (videoPlayer == player) {
                    Log.e("TvHome.Wallpaper", "MP4 playback error for " + file.getName()
                            + " (what=" + what + ", extra=" + extra + ")");
                    scheduleVideoRetry(texture, file, "error " + what + "/" + extra);
                }
                return true;
            });
            player.setOnCompletionListener(mp -> {
                if (videoPlayer == player) scheduleVideoRetry(texture, file, "unexpected completion");
            });
            player.prepareAsync();
        } catch (Exception failure) {
            Log.e("TvHome.Wallpaper", "Could not prepare MP4 wallpaper: " + file.getName(), failure);
            scheduleVideoRetry(texture, file, "prepare exception");
        }
    }

    // Some TV decoders silently stop advancing after long playback. Periodically check
    // the playback clock and rebuild MediaPlayer if it stops or stalls.
    private void scheduleVideoWatchdog(TextureView texture, File file) {
        cancelVideoWatchdog();
        videoWatchdog = new Runnable() {
            @Override public void run() {
                if (closed || videoTexture != texture || !active || !videoPrepared || videoPlayer == null) return;
                try {
                    MediaPlayer player = videoPlayer;
                    if (!player.isPlaying()) {
                        scheduleVideoRetry(texture, file, "watchdog: not playing");
                        return;
                    }
                    int position = player.getCurrentPosition();
                    if (position == videoLastPosition) videoStallChecks++;
                    else videoStallChecks = 0;
                    videoLastPosition = position;
                    if (videoStallChecks >= 3) {
                        scheduleVideoRetry(texture, file, "watchdog: playback position stalled");
                        return;
                    }
                } catch (Exception failure) {
                    scheduleVideoRetry(texture, file, "watchdog exception");
                    return;
                }
                videoHandler.postDelayed(this, 30_000L);
            }
        };
        videoHandler.postDelayed(videoWatchdog, 30_000L);
    }

    private void cancelVideoWatchdog() {
        if (videoWatchdog != null) videoHandler.removeCallbacks(videoWatchdog);
        videoWatchdog = null;
    }

    private void cancelVideoTasks() {
        cancelVideoWatchdog();
        if (videoRetry != null) videoHandler.removeCallbacks(videoRetry);
        videoRetry = null;
    }

    private void scheduleVideoRetry(TextureView texture, File file, String reason) {
        if (closed || videoTexture != texture) return;
        cancelVideoWatchdog();
        if (videoRetry != null) return;
        videoPrepared = false;
        texture.setVisibility(View.VISIBLE);
        root.setBackgroundColor(backgroundColor);
        releaseVideoPlayerOnly();
        int attempt = ++videoRetryCount;
        long delay = Math.min(30_000L, 2_000L * (1L << Math.min(attempt - 1, 4)));
        Log.w("TvHome.Wallpaper", "Restarting MP4 wallpaper in " + delay
                + "ms (attempt " + attempt + "): " + file.getName() + ", " + reason);
        videoRetry = () -> {
            videoRetry = null;
            if (!closed && active && videoTexture == texture && texture.isAvailable()) {
                prepareVideo(texture, texture.getSurfaceTexture(), file);
            }
        };
        videoHandler.postDelayed(videoRetry, delay);
    }

    private void releaseVideoPlayerOnly() {
        videoPrepared = false;
        MediaPlayer player = videoPlayer;
        videoPlayer = null;
        if (player != null) {
            try { player.setOnPreparedListener(null); player.setOnErrorListener(null); player.release(); }
            catch (Exception ignored) { }
        }
        Surface surface = videoSurface;
        videoSurface = null;
        if (surface != null) surface.release();
    }

    private void releaseVideo() {
        cancelVideoTasks();
        videoRetryCount = 0;
        videoStallChecks = 0;
        videoLastPosition = -1;
        videoFile = null;
        TextureView texture = videoTexture;
        videoTexture = null;
        releaseVideoPlayerOnly();
        if (texture != null && texture.getParent() == root) root.removeView(texture);
    }

    private static boolean isGif(File file) {
        // The phone's existing theme transport names its single custom picture wallpaper.jpg.
        // Detect the actual bytes so an original GIF can pass through that backwards-compatible path.
        try (java.io.InputStream input = new java.io.BufferedInputStream(new java.io.FileInputStream(file))) {
            byte[] header = new byte[6];
            int read = input.read(header);
            return read == 6
                    && header[0] == 'G' && header[1] == 'I' && header[2] == 'F'
                    && header[3] == '8' && (header[4] == '7' || header[4] == '9') && header[5] == 'a';
        } catch (Exception ignored) {
            return file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".gif");
        }
    }

    private AnimatedImageDrawable decodeGif(File file) {
        if (android.os.Build.VERSION.SDK_INT < 28) return null;
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(file);
            Drawable drawable = ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                if (w <= 0 || h <= 0 || !WallpaperSize.accepted(w, h)) {
                    throw new IllegalArgumentException("Unsupported GIF dimensions");
                }
                float scale = Math.max((float) width / w, (float) height / h);
                decoder.setTargetSize(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
            });
            if (!(drawable instanceof AnimatedImageDrawable)) return null;
            AnimatedImageDrawable gif = (AnimatedImageDrawable) drawable;
            gif.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
            return gif;
        } catch (Exception | OutOfMemoryError failure) {
            Log.w("TvHome.Wallpaper", "Could not decode GIF: " + file.getName(), failure);
            return null;
        }
    }

    /** Puts a picture on screen and releases the previous one after a frame has drawn the new one. */
    private void show(Bitmap bitmap) {
        AnimatedImageDrawable oldAnimation = currentAnimation;
        currentAnimation = null;
        stopAnimation(oldAnimation);
        Bitmap old = current;
        current = bitmap;
        root.setBackground(null);
        image.setForeground(dimColor == 0 ? null : new ColorDrawable(dimColor));
        image.setImageBitmap(bitmap);
        if (old != null && old != bitmap) image.postDelayed(old::recycle, 500);
    }

    @android.annotation.TargetApi(28)
    private void show(AnimatedImageDrawable gif) {
        Bitmap old = current;
        current = null;
        if (old != null) image.postDelayed(old::recycle, 500);
        stopAnimation(currentAnimation);
        currentAnimation = gif;
        root.setBackground(null);
        image.setForeground(dimColor == 0 ? null : new ColorDrawable(dimColor));
        image.setImageDrawable(gif);
        if (active) startAnimation(gif);
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            options.inMutable = dimColor != 0;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            if (dimColor != 0) new Canvas(decoded).drawColor(dimColor);
            // One copy in GPU memory instead of a software bitmap plus its texture; the decode stays off the UI
            // thread for rotations, and Home's return no longer uploads 4-8 MB to the GPU.
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
