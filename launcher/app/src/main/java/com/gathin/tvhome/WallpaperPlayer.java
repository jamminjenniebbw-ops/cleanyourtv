package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.os.Build;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.widget.VideoView;
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
    private VideoView video;
    private boolean videoPrepared;
    private final int backgroundColor;
    private final List<File> files;
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
        if (config.wallpaperEnabled && !files.isEmpty()) {
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
        List<File> playable = playable();
        File first = WallpaperRotation.find(playable, previous);
        if (first == null) first = WallpaperRotation.pick(playable, previous, order, random);
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
                    showCaption(false);
                    if (!displayed.equals(previous)) {
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
                    showCaption(false);
                    if (!displayed.equals(previous)) {
                        preferences.edit().putString("image", displayed)
                                .putLong("changed", System.currentTimeMillis()).apply();
                    }
                    return;
                }
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            playable = playable();
            first = WallpaperRotation.pick(playable, previous, order, random);
        }
    }

    void start() {
        active = true;
        startAnimation(currentAnimation);
        if (video != null && videoPrepared) video.start();
        update();
    }

    void stop() {
        active = false;
        stopAnimation(currentAnimation);
        if (video != null && videoPrepared) video.pause();
    }

    /** Drops the wallpaper texture when Home is hidden and Android asks the process to free memory. */
    void releaseForMemoryPressure() {
        if (current == null && currentAnimation == null && video == null) return;
        if (video != null) { video.stopPlayback(); video = null; videoPrepared = false; }
        stopAnimation(currentAnimation);
        currentAnimation = null;
        image.setImageDrawable(null);
        // The renderer may still have a queued frame that references this hardware bitmap. Drop our references and
        // let Android reclaim its native buffer after rendering releases it instead of recycling it mid-frame.
        current = null;
        displayed = "";
        root.setBackgroundColor(backgroundColor);
    }

    /** The pictures the rotation picks from now: wallpaper_moods' group for the time of day, or the whole collection. */
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
        stopAnimation(currentAnimation);
        currentAnimation = null;
        if (video != null) { video.stopPlayback(); video = null; videoPrepared = false; }
        decoder.shutdownNow();
    }

    void update() {
        if (!active || closed || loading || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        // A picture outside the time of day's group (the group changed) is replaced at once, not after the interval.
        List<File> playable = playable();
        File selected = WallpaperRotation.find(playable, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(playable, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
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
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                if (gif != null) show(gif); else show(bitmap);
                displayed = next.getName();
                showCaption(true);
                if (!displayed.equals(previous) || changed == 0) {
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
        if (video != null) { video.stopPlayback(); root.removeView(video); }
        stopAnimation(currentAnimation); currentAnimation = null;
        if (current != null) { current.recycle(); current = null; }
        image.setImageDrawable(null);
        videoPrepared = false;
        VideoView player = new VideoView(root.getContext());
        video = player;
        player.setFocusable(false);
        player.setOnPreparedListener(mp -> {
            if (video != player || closed) { player.stopPlayback(); return; }
            mp.setVolume(0f, 0f);
            mp.setLooping(true);
            videoPrepared = true;
            if (active) player.start();
        });
        player.setOnErrorListener((mp, what, extra) -> {
            if (video == player) {
                videoPrepared = false;
                player.setVisibility(View.GONE);
                root.setBackgroundColor(backgroundColor);
                Log.w("TvHome.Wallpaper", "Unsupported or unreadable MP4 wallpaper: " + file.getName());
            }
            return true;
        });
        root.addView(player, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        player.setVideoPath(file.getAbsolutePath());
        root.setBackground(null);
        image.setForeground(dimColor == 0 ? null : new ColorDrawable(dimColor));
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
