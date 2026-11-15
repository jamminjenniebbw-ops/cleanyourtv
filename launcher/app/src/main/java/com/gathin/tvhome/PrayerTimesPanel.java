package com.gathin.tvhome;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.icu.util.IslamicCalendar;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

final class PrayerTimesPanel {
    private static final ZoneId ZONE = ZoneId.of("Asia/Jakarta");
    private final Activity activity;
    private final LinearLayout panel;
    private TextView status, next, nextName;
    private final TextView[] values = new TextView[8];
    private final View[] cards = new View[8];
    private final String[] names = {"Imsak", "Subuh", "Terbit", "Dhuha", "Dzuhur", "Ashar", "Maghrib", "Isya"};
    private TextView dateDay, dateNumber, dateMonth, hijriDate, dateHijri, nextPrayerTitle, nextPrayerTime;
    private final TextView[] rowLabels = new TextView[8];
    private final boolean modelTwo;
    private ProgressTrack progressTrack;
    private SolarIconView nextPrayerIcon;
    private boolean positionHidden;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<String> todayTimes = new ArrayList<>();
    private LocalDate loadedDate;
    private LocalDate lastLoadAttemptDate;
    private boolean enabled;
    private boolean fetching;
    private long lastFetchStartedAt;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    /**
     * Overlay in the same upper-right Home position as the original version.
     * It is deliberately not part of the scrolling app column.
     */
    static PrayerTimesPanel attach(Activity a, FrameLayout root, boolean dockGlass, int dockOpacity, boolean dockBand, boolean cinematic, boolean enabled, String position) {
        PrayerTimesPanel p = new PrayerTimesPanel(a, dockGlass, dockOpacity, dockBand, cinematic);
        p.enabled = enabled;
        p.positionHidden = "hidden".equals(position);
        android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
        int screenWidth = dm.widthPixels;
        int screenHeight = dm.heightPixels;
        boolean modelTwo = a.getSharedPreferences(Config.SETTINGS_NAME, Activity.MODE_PRIVATE)
                .getInt("prayer_panel_design", 1) == 2;
        // Model 2 is a compact landscape card; Model 1 keeps its original footprint unchanged.
        int panelWidth = modelTwo
                ? Math.min((int)(258f * dm.density), (int)(screenWidth * .24f))
                : Math.min((int)(330f * dm.density), (int)(screenWidth * .32f));
        int panelHeight = modelTwo
                ? Math.min((int)(468f * dm.density), (int)(screenHeight * .67f))
                : (int)(screenHeight * .58f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(panelWidth, panelHeight,
                ("left".equals(position) ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL);
        // Match the small inset used by the side settings surface rather than leaving a wide screen-edge gap.
        // Align with the Home clock/settings safe margins (rather than the physical screen edge).
        if ("left".equals(position)) lp.leftMargin = (int)(56f * dm.density);
        else lp.rightMargin = (int)(56f * dm.density);
        root.addView(p.panel, lp);
        if (modelTwo) p.panel.setTranslationY((int)(28f * dm.density));
        p.panel.setVisibility(enabled && !p.positionHidden ? View.VISIBLE : View.GONE);
        if (enabled) {
            p.registerNetworkCallback();
            p.load();
            p.handler.postDelayed(p.tick, 1_000L);
        }
        return p;
    }

    void bringToFront() {
        panel.bringToFront();
        panel.invalidate();
    }

    void hideForNavigation() {
        if (enabled) panel.setVisibility(View.GONE);
    }

    void showAtHomeTop() {
        if (enabled && !positionHidden) panel.setVisibility(View.VISIBLE);
    }

    void onGridScrolled(int verticalOffsetPx) {
        // Scrolling must never override the user's master ON/OFF preference.
        if (!enabled || positionHidden) {
            panel.setVisibility(View.GONE);
            return;
        }
        // Visibility follows the actual Home scroll position, not the remote key event.
        // At the top (dock/first shelf) the panel is visible; once the app grid moves down,
        // hide it so it cannot cover the header or the rows below. Returning to offset 0 restores it.
        // A small non-zero offset can occur when focus moves from the top controls to the dock,
        // even though the Home composition has not visibly scrolled. Keep the panel visible
        // until the app grid has actually moved upward by a meaningful amount.
        if (verticalOffsetPx > dp(96)) hideForNavigation();
        else showAtHomeTop();
    }

    private PrayerTimesPanel(Activity a, boolean dockGlass, int dockOpacity, boolean dockBand, boolean cinematic) {
        activity = a;
        modelTwo = a.getSharedPreferences(Config.SETTINGS_NAME, Activity.MODE_PRIVATE)
                .getInt("prayer_panel_design", 1) == 2;
        panel = new LinearLayout(a);
        if (modelTwo) {
            buildModelTwo();
            panel.setBackground(createDockBackground(dockGlass, dockOpacity, dockBand, cinematic));
            panel.setElevation(dp(10));
            updateDateCard();
            updateCardStyles(-1);
            return;
        }
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(9), dp(7), dp(9), dp(7));
        // Match the dock's actual fill, glass gradient, opacity and edge treatment.
        panel.setBackground(createDockBackground(dockGlass, dockOpacity, dockBand, cinematic));
        panel.setElevation(dp(10));

        LinearLayout header = new LinearLayout(a);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(34)));
        ImageView mosque = new ImageView(a);
        mosque.setImageResource(R.drawable.prayer_icon_mosque);
        mosque.setColorFilter(0xFF48D597);
        mosque.setScaleType(ImageView.ScaleType.FIT_CENTER);
        header.addView(mosque, new LinearLayout.LayoutParams(dp(27), dp(27)));
        View headLine = divider();
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(1), dp(25));
        hlp.setMargins(dp(4), 0, dp(8), 0);
        header.addView(headLine, hlp);
        TextView title = text("Jadwal Sholat", 18, Color.WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        View sep = divider();
        LinearLayout.LayoutParams sepP = new LinearLayout.LayoutParams(-1, dp(1));
        sepP.setMargins(0, dp(4), 0, dp(7));
        panel.addView(sep, sepP);

        // Reference composition: date/countdown/next prayer on the LEFT, prayer timetable on the RIGHT.
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.TOP);
        panel.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listP = new LinearLayout.LayoutParams(0, -1, 1.04f);
        listP.setMargins(dp(6), dp(1), 0, 0);
        // Use monochrome text glyphs from the same icon family to avoid emoji bitmap/box rendering.
        for (int i = 0; i < names.length; i++) {
            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(3), 0, dp(3), 0);
            GradientDrawable itemBg = new GradientDrawable();
            itemBg.setColor(0x00000000);
            itemBg.setCornerRadius(dp(10));
            item.setBackground(itemBg);
            LinearLayout.LayoutParams itemP = new LinearLayout.LayoutParams(-1, 0, 1f);
            list.addView(item, itemP);
            SolarIconView icon = new SolarIconView(a, i, 0xFF48D597);
            item.addView(icon, new LinearLayout.LayoutParams(dp(21), dp(21)));
            TextView label = text(names[i], 11, 0xFFF7F8FA, true);
            item.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            values[i] = text("--:--", 12, Color.WHITE, true);
            item.addView(values[i], wrap());
            cards[i] = item;
            if (i < names.length - 1) {
                View line = divider();
                LinearLayout.LayoutParams lineP = new LinearLayout.LayoutParams(-1, dp(1));
                lineP.setMargins(dp(3), 0, dp(3), 0);
                list.addView(line, lineP);
            }
        }

        LinearLayout right = new LinearLayout(a);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rightP = new LinearLayout.LayoutParams(0, -1, .96f);
        rightP.setMargins(0, 0, dp(3), 0);
        content.addView(right, rightP);
        content.addView(list, listP);

        LinearLayout date = glassCard();
        date.setGravity(Gravity.CENTER);
        date.setPadding(dp(3), dp(3), dp(3), dp(3));
        right.addView(date, new LinearLayout.LayoutParams(-1, 0, 1.08f));
        dateDay = text("", 12, Color.WHITE, true);
        date.addView(dateDay, wrap());
        dateNumber = text("", 38, Color.WHITE, true);
        date.addView(dateNumber, wrap());
        dateMonth = text("", 11, 0xFFE5E8ED, true);
        date.addView(dateMonth, wrap());
        hijriDate = text("", 9, 0xFFD6DCE5, true);
        LinearLayout countdown = glassCard();
        countdown.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams countP = new LinearLayout.LayoutParams(-1, 0, .88f);
        countP.topMargin = dp(6);
        right.addView(countdown, countP);
        countdown.addView(text("Sholat berikutnya", 12, 0xFFE8EAF0, true), wrap());
        next = text("--:--:--", 23, 0xFF48D597, true);
        LinearLayout.LayoutParams nextP = wrap();
        nextP.topMargin = dp(3);
        countdown.addView(next, nextP);
        nextName = text("", 1, Color.TRANSPARENT, false);

        LinearLayout nextCard = new LinearLayout(a);
        nextCard.setOrientation(LinearLayout.HORIZONTAL);
        nextCard.setGravity(Gravity.CENTER_VERTICAL);
        nextCard.setPadding(dp(5), dp(4), dp(5), dp(4));
        GradientDrawable nextGreen = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xD9268055, 0xD9186044, 0xD9124937});
        nextGreen.setCornerRadius(dp(14));
        nextGreen.setStroke(dp(1), 0xFF36C987);
        nextCard.setBackground(nextGreen);
        nextCard.setElevation(dp(6));
        LinearLayout.LayoutParams nextCardP = new LinearLayout.LayoutParams(-1, 0, .78f);
        nextCardP.topMargin = dp(6);
        right.addView(nextCard, nextCardP);
        nextPrayerIcon = new SolarIconView(a, 7, Color.WHITE);
        nextCard.addView(nextPrayerIcon, new LinearLayout.LayoutParams(dp(35), dp(35)));
        View goldLine = divider();
        goldLine.setBackgroundColor(0xFF36C987);
        LinearLayout.LayoutParams goldLineP = new LinearLayout.LayoutParams(dp(1), dp(30));
        goldLineP.setMargins(dp(3), 0, dp(4), 0);
        nextCard.addView(goldLine, goldLineP);
        LinearLayout nextLabels = new LinearLayout(a);
        nextLabels.setOrientation(LinearLayout.VERTICAL);
        nextLabels.setGravity(Gravity.CENTER_VERTICAL);
        nextCard.addView(nextLabels, new LinearLayout.LayoutParams(0, -1, 1f));
        nextPrayerTitle = text("Subuh", 16, Color.WHITE, true);
        nextLabels.addView(nextPrayerTitle, wrap());
        nextPrayerTime = text("--:--", 14, 0xFFDDF8EA, true);
        nextLabels.addView(nextPrayerTime, wrap());

        LinearLayout footer = new LinearLayout(a);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerP = new LinearLayout.LayoutParams(-1, dp(21));
        footerP.topMargin = dp(5);
        panel.addView(footer, footerP);
        footer.addView(text("●", 10, Color.WHITE, true), wrap());
        footer.addView(text("  BANDUNG, JAWA BARAT", 10, Color.WHITE, true), wrap());

        status = text("Memuat jadwal…", 7, 0xFFDCE3EC, true);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusP = new LinearLayout.LayoutParams(-1, dp(10));
        panel.addView(status, statusP);
        panel.setFocusable(false);
        panel.setClickable(false);
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        updateDateCard();
        updateCardStyles(-1);
    }


    /** Portrait glass layout matching the supplied Model 2 reference. */
    private void buildModelTwo() {
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(13), dp(12), dp(13), dp(12));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        panel.setBackground(liquidGlass(
                new int[]{0x20DCEAF1, 0x18F4DCCF, 0x20D9C8DE}, dp(28), 0x55FFFFFF));
        panel.setElevation(dp(8));

        FrameLayout header = new FrameLayout(activity);
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(66)));
        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams headingP = new FrameLayout.LayoutParams(-1, -1, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        headingP.rightMargin = dp(42);
        header.addView(heading, headingP);

        // Keep the title and location independent so the location never gets squeezed by the title.
        heading.addView(cleanText("Jadwal Sholat", 16, true), wrap());
        TextView place = cleanText("Bandung", 11, true);
        place.setSingleLine(true);
        place.setMaxLines(1);
        place.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        place.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
        FrameLayout.LayoutParams placeP = new FrameLayout.LayoutParams(dp(184), dp(26),
                Gravity.TOP | Gravity.RIGHT);
        placeP.rightMargin = dp(8);
        placeP.topMargin = dp(5);
        header.addView(place, placeP);

        dateDay = cleanText("--", 10, false);
        LinearLayout.LayoutParams gregorianP = wrap();
        gregorianP.topMargin = dp(3);
        heading.addView(dateDay, gregorianP);
        dateHijri = cleanText("--", 9, false);
        LinearLayout.LayoutParams hijriP = wrap();
        hijriP.topMargin = dp(1);
        heading.addView(dateHijri, hijriP);

        ImageView mosque = new ImageView(activity);
        setMosqueFromReference(mosque);
        mosque.setColorFilter(Color.WHITE);
        mosque.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams mosqueP = new FrameLayout.LayoutParams(dp(39), dp(39),
                Gravity.RIGHT | Gravity.BOTTOM);
        mosqueP.rightMargin = dp(2);
        mosqueP.bottomMargin = dp(3);
        header.addView(mosque, mosqueP);

        LinearLayout countdownCard = new LinearLayout(activity);
        countdownCard.setOrientation(LinearLayout.VERTICAL);
        countdownCard.setGravity(Gravity.CENTER);
        countdownCard.setPadding(dp(8), dp(11), dp(8), dp(8));
        // Keep inner surfaces transparent so the entire panel inherits the dock appearance.
        countdownCard.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams countdownP = new LinearLayout.LayoutParams(-1, 0, 0.94f);
        countdownP.topMargin = dp(5);
        panel.addView(countdownCard, countdownP);
        nextName = cleanText("Selanjutnya", 13, true);
        nextName.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nextNameP = wrap();
        nextNameP.topMargin = dp(7);
        countdownCard.addView(nextName, nextNameP);
        nextPrayerTitle = cleanText("Menuju --", 18, false);
        nextPrayerTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams targetP = wrap();
        targetP.topMargin = dp(3);
        countdownCard.addView(nextPrayerTitle, targetP);
        next = cleanText("--:--:--", 27, false);
        next.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams timeP = wrap();
        timeP.topMargin = 0;
        countdownCard.addView(next, timeP);
        nextPrayerTime = cleanText("--:--", 1, false);
        nextPrayerTime.setVisibility(View.GONE);
        progressTrack = new ProgressTrack(activity);
        LinearLayout.LayoutParams progressP = new LinearLayout.LayoutParams(-1, dp(20));
        progressP.topMargin = 0;
        countdownCard.addView(progressTrack, progressP);
        progressTrack.setTranslationY(-dp(7));

        LinearLayout schedule = new LinearLayout(activity);
        schedule.setOrientation(LinearLayout.VERTICAL);
        schedule.setPadding(dp(8), dp(7), dp(8), dp(7));
        // Remove the independent tinted glass layer; match the dock's fill and transparency.
        schedule.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams scheduleP = new LinearLayout.LayoutParams(-1, 0, 1.35f);
        scheduleP.topMargin = dp(6);
        panel.addView(schedule, scheduleP);
        for (int i = 0; i < names.length; i++) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(7), dp(1), dp(7), dp(1));
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setColor(Color.TRANSPARENT);
            rowBg.setCornerRadius(dp(24));
            row.setBackground(rowBg);
            schedule.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
            cards[i] = row;
            SolarIconView rowIcon = new SolarIconView(activity, i, Color.WHITE);
            LinearLayout.LayoutParams rowIconP = new LinearLayout.LayoutParams(dp(18), dp(18));
            rowIconP.rightMargin = dp(7);
            row.addView(rowIcon, rowIconP);
            rowLabels[i] = cleanText(names[i], 13, false);
            row.addView(rowLabels[i], new LinearLayout.LayoutParams(0, -2, 1f));
            values[i] = cleanText("--:--", 13, false);
            values[i].setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            row.addView(values[i], new LinearLayout.LayoutParams(dp(58), -2));
        }
        status = cleanText("", 1, false);
        status.setVisibility(View.GONE);
        panel.setFocusable(false);
        panel.setClickable(false);
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    private Typeface sfProRoundedTypeface;

    private Typeface loadSfProRoundedTypeface() {
        if (sfProRoundedTypeface != null) return sfProRoundedTypeface;
        File fontFile = new File(activity.getCacheDir(), "SF-Pro-Rounded-Bold.ttf");
        if (!fontFile.isFile() || fontFile.length() == 0L) {
            try (ZipInputStream zip = new ZipInputStream(activity.getAssets().open("font_sf.zip"))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.getName().toLowerCase(Locale.ROOT).endsWith(".ttf")) {
                        try (FileOutputStream out = new FileOutputStream(fontFile)) {
                            byte[] buffer = new byte[8192];
                            int count;
                            while ((count = zip.read(buffer)) != -1) out.write(buffer, 0, count);
                        }
                        break;
                    }
                }
            } catch (Exception ignored) { }
        }
        try {
            if (fontFile.isFile() && fontFile.length() > 0L)
                sfProRoundedTypeface = Typeface.createFromFile(fontFile);
        } catch (Exception ignored) { }
        if (sfProRoundedTypeface == null)
            sfProRoundedTypeface = Typeface.create("sans-serif", Typeface.BOLD);
        return sfProRoundedTypeface;
    }

    private void setMosqueFromReference(ImageView target) {
        try (java.io.InputStream in = activity.getAssets().open("prayer-panel-model-2.jpg")) {
            Bitmap source = BitmapFactory.decodeStream(in);
            if (source == null) return;
            // Crop the mosque mark directly from the supplied reference image (840x1300 composition).
            int left = Math.round(source.getWidth() * (610f / 840f));
            int top = Math.round(source.getHeight() * (130f / 1300f));
            int width = Math.min(Math.round(source.getWidth() * (82f / 840f)), source.getWidth() - left);
            int height = Math.min(Math.round(source.getHeight() * (82f / 1300f)), source.getHeight() - top);
            Bitmap crop = Bitmap.createBitmap(source, left, top, width, height);
            Bitmap transparent = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = crop.getPixel(x, y);
                    int luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587
                            + Color.blue(pixel) * 114) / 1000;
                    int alpha = Math.max(0, Math.min(255, (190 - luminance) * 255 / 190));
                    transparent.setPixel(x, y, Color.argb(alpha, 0, 0, 0));
                }
            }
            target.setImageBitmap(transparent);
            if (crop != source) crop.recycle();
            source.recycle();
        } catch (Exception ignored) { }
    }

    private TextView cleanText(String value, int size, boolean bold) {
        TextView view = text(value, size, Color.WHITE, true);
        view.setTypeface(loadSfProRoundedTypeface());
        view.setPadding(dp(3), dp(1), dp(4), dp(1));
        view.setShadowLayer(dp(2), 0f, dp(1), 0xB3000000);
        return view;
    }

    private static final class ProgressTrack extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float progress;
        ProgressTrack(Activity a) { super(a); }
        void setProgress(float value) {
            progress = Math.max(0f, Math.min(1f, value));
            invalidate();
        }
        private float d(float value) { return value * getResources().getDisplayMetrics().density; }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float y = getHeight() / 2f;
            float left = d(6), right = getWidth() - d(6);
            float x = left + (right - left) * progress;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(d(4));
            paint.setColor(0x66808080);
            canvas.drawLine(left, y, right, y, paint);
            paint.setColor(Color.WHITE);
            canvas.drawLine(left, y, x, y, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x, y, d(5), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(d(1.2f));
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x, y, d(5), paint);        }
    }

    /** Reference-matched icons extracted from the user's supplied NU-style schedule image. */
    private final class SolarIconView extends ImageView {
        private static Bitmap referenceSheet;
        private int stage;
        private int tint;
        SolarIconView(Activity a, int stage, int tint) {
            super(a);
            this.tint = tint;
            setScaleType(ImageView.ScaleType.FIT_CENTER);
            setStage(stage);
        }
        // Keep the gray artwork from the supplied reference; never recolor it per row state.
        void setTint(int ignored) { clearColorFilter(); }
        void setStage(int value) {
            stage = Math.max(0, Math.min(7, value));
            setContentDescription(names[stage]);
            try {
                if (referenceSheet == null) {
                    java.io.InputStream input = activity.getResources().openRawResource(R.raw.prayer_icons_reference);
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    input.close();
                    byte[] png = android.util.Base64.decode(bytes.toByteArray(), android.util.Base64.DEFAULT);
                    referenceSheet = BitmapFactory.decodeByteArray(png, 0, png.length);
                }
                if (referenceSheet != null && referenceSheet.getWidth() >= 8 && referenceSheet.getHeight() > 0) {
                    // The supplied reference strip contains the eight original schedule symbols in order.
                    // Divide the actual source width instead of assuming a fixed 74px export size.
                    int cellWidth = referenceSheet.getWidth() / 8;
                    int left = stage * cellWidth;
                    int width = stage == 7 ? referenceSheet.getWidth() - left : cellWidth;
                    Bitmap icon = Bitmap.createBitmap(referenceSheet, left, 0, width,
                            referenceSheet.getHeight());
                    setImageBitmap(icon);
                    clearColorFilter();
                }
            } catch (Exception ignored) { }
        }
    }

    /** Layered translucent surface with a soft top reflection for a liquid-glass finish. */
    private android.graphics.drawable.Drawable liquidGlass(int[] colors, int radiusPx, int strokeColor) {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        base.setCornerRadius(radiusPx);
        base.setStroke(dp(1), strokeColor);
        GradientDrawable reflection = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x48FFFFFF, 0x1CFFFFFF, 0x05FFFFFF, 0x12C8E8FF});
        reflection.setCornerRadius(radiusPx);
        reflection.setStroke(dp(1), 0x22FFFFFF);
        return new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{base, reflection});
    }

    private LinearLayout glassCard() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x3CFFFFFF, 0x16FFFFFF});
        shape.setCornerRadius(dp(22));
        shape.setStroke(dp(1), 0x4DFFFFFF);
        card.setBackground(shape);
        card.setElevation(dp(4));
        return card;
    }

    private View divider() {
        View v = new View(activity);
        v.setBackgroundColor(0x55FFFFFF);
        return v;
    }

    private void updateDateCard() {
        LocalDate today = LocalDate.now(ZONE);
        Locale id = new Locale("id", "ID");
        if (modelTwo) {
            String hijri = "Tanggal Hijriah tidak tersedia";
            try {
                IslamicCalendar h = new IslamicCalendar(new Date());
                h.setTimeZone(android.icu.util.TimeZone.getTimeZone(ZONE.getId()));
                String[] months = {"Muharram", "Safar", "Rabiul Awal", "Rabiul Akhir", "Jumadil Awal",
                        "Jumadil Akhir", "Rajab", "Syaban", "Ramadan", "Syawal", "Zulkaidah", "Zulhijah"};
                int day = h.get(IslamicCalendar.DAY_OF_MONTH);
                int month = h.get(IslamicCalendar.MONTH);
                int year = h.get(IslamicCalendar.YEAR);
                hijri = day + " " + months[Math.max(0, Math.min(months.length - 1, month))] + " " + year + " H";
            } catch (Exception ignored) { }
            if (dateDay != null) {
                dateDay.setText(today.format(DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy", id)));
                if (dateHijri != null) dateHijri.setText(hijri);
            }
            return;
        }
        dateDay.setText(today.format(DateTimeFormatter.ofPattern("EEEE", id)));
        dateNumber.setText(String.valueOf(today.getDayOfMonth()));
        dateMonth.setText(today.format(DateTimeFormatter.ofPattern("MMMM yyyy", id)));
        try {
            IslamicCalendar h = new IslamicCalendar(new Date());
            h.setTimeZone(android.icu.util.TimeZone.getTimeZone(ZONE.getId()));
            String[] months = {"Muharram", "Safar", "Rabiul Awal", "Rabiul Akhir", "Jumadil Awal",
                    "Jumadil Akhir", "Rajab", "Syaban", "Ramadan", "Syawal", "Zulkaidah", "Zulhijah"};
            int day = h.get(IslamicCalendar.DAY_OF_MONTH);
            int month = h.get(IslamicCalendar.MONTH);
            int year = h.get(IslamicCalendar.YEAR);
            hijriDate.setText(day + " " + months[Math.max(0, Math.min(months.length - 1, month))] + " " + year + " H");
        } catch (Exception ignored) {
            hijriDate.setText("Tanggal Hijriah tidak tersedia");
        }
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            LocalDate d = LocalDate.now(ZONE);
            updateDateCard();
            if (!d.equals(loadedDate) && (lastLoadAttemptDate == null || !d.equals(lastLoadAttemptDate))) load();
            else updateNext();
            handler.postDelayed(this, 1_000L);
        }
    };

    private int fetchAttempt = 0;
    private static final String API_BASE = "https://api.myquran.com/v3/sholat";
    private static final String CITY_NAME = "KOTA BANDUNG";

    private boolean hasValidatedInternet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Activity.CONNECTIVITY_SERVICE);
            Network active = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception ignored) { return false; }
    }

    private void registerNetworkCallback() {
        try {
            connectivityManager = (ConnectivityManager) activity.getSystemService(Activity.CONNECTIVITY_SERVICE);
            if (connectivityManager == null || networkCallback != null) return;
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    handler.postDelayed(() -> {
                        if (enabled && hasValidatedInternet()) load(true);
                    }, 1500L);
                }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    if (enabled && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        handler.postDelayed(() -> {
                            if (enabled && hasValidatedInternet()) load(true);
                        }, 1500L);
                    }
                }
            };
            connectivityManager.registerNetworkCallback(
                    new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                    networkCallback);
        } catch (Exception ignored) { }
    }

    private void load() { load(false); }

    private void load(boolean forceRefresh) {
        if (!enabled) return;
        LocalDate d = LocalDate.now(ZONE);
        lastLoadAttemptDate = d;
        String cacheKey = "schedule_myquran_" + d.getYear();
        android.content.SharedPreferences prefs = activity.getSharedPreferences("prayer_times_panel", 0);
        String cached = prefs.getString(cacheKey, "");
        boolean fromMyQuranCache = !cached.isEmpty();
        // Migrate a previously downloaded schedule to keep existing offline installs usable.
        if (cached.isEmpty()) cached = prefs.getString("schedule_" + d.getYear(), "");
        if (!cached.isEmpty()) parse(cached, d);

        if (!hasValidatedInternet()) {
            if (todayTimes.isEmpty()) status.setText("Offline • menunggu jadwal tersimpan");
            else status.setText(fromMyQuranCache
                    ? "Offline • data Kementerian Agama Republik Indonesia tersimpan"
                    : "Offline • jadwal lama tersimpan");
            return;
        }

        long now = System.currentTimeMillis();
        if (fetching || now - lastFetchStartedAt < 10_000L) return;
        long lastSuccess = activity.getSharedPreferences("prayer_times_panel", 0)
                .getLong("last_success_myquran_" + d.getYear(), 0L);
        if (!forceRefresh && lastSuccess > 0L && now - lastSuccess < 24L * 60L * 60L * 1000L) {
            if (!todayTimes.isEmpty()) status.setText("Sumber: Kementerian Agama Republik Indonesia • jadwal tersimpan");
            return;
        }
        fetching = true;
        lastFetchStartedAt = now;
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                String cityJson = request(API_BASE + "/kabkota/cari/bandung");
                JSONObject cityRoot = new JSONObject(cityJson);
                if (!cityRoot.optBoolean("status")) throw new java.io.IOException("Pencarian Kota Bandung gagal");
                JSONArray cities = cityRoot.optJSONArray("data");
                String cityId = null;
                if (cities != null) {
                    for (int i = 0; i < cities.length(); i++) {
                        JSONObject city = cities.optJSONObject(i);
                        if (city != null && CITY_NAME.equalsIgnoreCase(city.optString("lokasi").trim())) {
                            cityId = city.optString("id", "");
                            break;
                        }
                    }
                }
                if (cityId == null || cityId.isEmpty()) throw new java.io.IOException("ID Kota Bandung tidak ditemukan");

                String json = fetchFullYear(cityId, d.getYear());
                // Commit this year's fully validated schedule independently; next-year prefetch is best-effort.
                prefs.edit().putString(cacheKey, json)
                        .putLong("last_success_myquran_" + d.getYear(), System.currentTimeMillis()).apply();
                // In the last quarter, pre-cache next year's schedule so New Year's Day also works offline.
                if (d.getMonthValue() >= 10) {
                    try {
                        String nextYearJson = fetchFullYear(cityId, d.getYear() + 1);
                        prefs.edit().putString("schedule_myquran_" + (d.getYear() + 1), nextYearJson)
                                .putLong("last_success_myquran_" + (d.getYear() + 1), System.currentTimeMillis())
                                .apply();
                    } catch (Exception ignored) {
                        // A not-yet-published next-year schedule must not invalidate this year's fresh cache.
                    }                }
                activity.runOnUiThread(() -> {
                    fetching = false;
                    fetchAttempt = 0;
                    parse(json, LocalDate.now(ZONE));
                    status.setText("Sumber: Kementerian Agama Republik Indonesia • jadwal diperbarui");
                });
            } catch (Exception e) {
                final String error = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")");
                activity.runOnUiThread(() -> {
                    fetching = false;
                    if (todayTimes.isEmpty()) status.setText("Jadwal gagal dimuat • " + error);
                    else status.setText("Jadwal tersimpan • pembaruan gagal • Kementerian Agama Republik Indonesia");
                    updateNext();
                    fetchAttempt++;
                    long delay = Math.min(15 * 60_000L, 30_000L * (1L << Math.min(fetchAttempt - 1, 4)));
                    handler.removeCallbacks(retryFetch);
                    handler.postDelayed(retryFetch, delay);
                });
            }
        }, "PrayerTimesMyQuranFetch").start();
    }

    private String fetchFullYear(String cityId, int year) throws Exception {
        JSONObject normalized = new JSONObject();
        JSONArray fields = new JSONArray();
        for (String field : new String[]{"date", "imsak", "fajr", "terbit", "dhuha", "dhuhr", "asr", "maghrib", "isha"}) fields.put(field);
        normalized.put("fields", fields);
        JSONArray days = new JSONArray();
        HashSet<String> seenDates = new HashSet<>();
        for (int month = 1; month <= 12; month++) {
            String period = String.format(Locale.ROOT, "%04d-%02d", year, month);
            JSONObject monthRoot = new JSONObject(request(API_BASE + "/jadwal/" + cityId + "/" + period));
            if (!monthRoot.optBoolean("status")) throw new java.io.IOException("Jadwal " + period + " tidak tersedia");
            JSONObject data = monthRoot.optJSONObject("data");
            JSONObject schedule = data == null ? null : data.optJSONObject("jadwal");
            if (schedule == null || schedule.length() < 27)
                throw new java.io.IOException("Format jadwal " + period + " tidak lengkap");
            Iterator<String> dates = schedule.keys();
            while (dates.hasNext()) {
                String dateKey = dates.next();
                JSONObject item = schedule.optJSONObject(dateKey);
                if (item == null) continue;
                try {
                    if (LocalDate.parse(dateKey).getYear() != year || !seenDates.add(dateKey)) continue;
                } catch (Exception invalidDate) { continue; }
                String[] keys = {"imsak", "subuh", "terbit", "dhuha", "dzuhur", "ashar", "maghrib", "isya"};
                JSONArray row = new JSONArray();
                row.put(dateKey);
                boolean valid = true;
                for (String key : keys) {
                    String time = item.optString(key, "");
                    if (!time.matches("\\d{2}:\\d{2}")) { valid = false; break; }
                    row.put(time);
                }
                if (valid) days.put(row);
            }
        }
        int expected = Year.isLeap(year) ? 366 : 365;
        if (days.length() != expected || seenDates.size() != expected)
            throw new java.io.IOException("Data tahun " + year + " tidak lengkap (" + days.length() + "/" + expected + " hari)");
        normalized.put("days", days);
        return normalized.toString();
    }

    private String request(String endpoint) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(endpoint).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "CleanYourTV-PrayerEdition/1.0 (Android TV)");
            c.setRequestProperty("Connection", "close");
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new java.io.IOException("HTTP " + code);
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            return body.toString();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private final Runnable retryFetch = this::load;

    private void parse(String json, LocalDate date) {
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray fields = obj.getJSONArray("fields"), days = obj.getJSONArray("days");
            int dateCol = field(fields, "date");
            int[] cols = {field(fields,"imsak"), field(fields,"fajr"), field(fields,"terbit"), field(fields,"dhuha"), field(fields,"dhuhr"), field(fields,"asr"), field(fields,"maghrib"), field(fields,"isha")};
            for (int i=0;i<days.length();i++) {
                JSONArray day = days.optJSONArray(i);
                if (day == null || dateCol < 0 || !date.toString().equals(day.optString(dateCol))) continue;
                todayTimes.clear();
                for (int p=0;p<8;p++) {
                    String t = cols[p] >= 0 ? day.optString(cols[p], "--:--") : "--:--";
                    todayTimes.add(t); values[p].setText(t);
                }
                loadedDate = date;
                status.setText("Kementerian Agama Republik Indonesia • " + date.format(DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy", new Locale("id","ID"))));
                updateNext();
                return;
            }
            status.setText("Jadwal tanggal ini belum tersedia");
        } catch (Exception e) { status.setText("Jadwal belum dapat dibaca"); }
    }

    private int field(JSONArray a, String key) {
        for (int i=0;i<a.length();i++) if (key.equalsIgnoreCase(a.optString(i))) return i;
        return -1;
    }

    private void updateNextPrayerIcon(int nextIndex) {
        if (nextPrayerIcon != null) nextPrayerIcon.setStage(nextIndex);
    }

    private void updateCardStyles(int nextIndex) {
        for (int i = 0; i < cards.length; i++) {
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setCornerRadius(dp(modelTwo ? 18 : 7));
            if (modelTwo) {
                cardBg.setColor(i == nextIndex ? Color.WHITE : Color.TRANSPARENT);
                cardBg.setStroke(dp(i == nextIndex ? 1 : 0),
                        i == nextIndex ? 0xCCFFFFFF : Color.TRANSPARENT);
                int foreground = i == nextIndex ? Color.BLACK : Color.WHITE;
                if (rowLabels[i] != null) {
                    rowLabels[i].setTextColor(foreground);
                    if (i == nextIndex) rowLabels[i].setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
                    else rowLabels[i].setShadowLayer(dp(2), 0f, dp(1), 0xB3000000);
                }
                if (values[i] != null) {
                    values[i].setTextColor(foreground);
                    if (i == nextIndex) values[i].setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
                    else values[i].setShadowLayer(dp(2), 0f, dp(1), 0xB3000000);
                }
                if (cards[i] instanceof LinearLayout) {
                    LinearLayout row = (LinearLayout) cards[i];
                    if (row.getChildAt(0) instanceof SolarIconView)
                        ((SolarIconView) row.getChildAt(0)).setTint(foreground);
                }
            } else {
                cardBg.setColor(i == nextIndex ? 0x2436C987 : 0x00000000);
                cardBg.setStroke(dp(i == nextIndex ? 1 : 0),
                        i == nextIndex ? 0xFF36C987 : Color.TRANSPARENT);
            }
            cards[i].setBackground(cardBg);
        }
    }

    private void updateNext() {
        if (todayTimes.isEmpty()) return;
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        int nextIndex = -1;
        ZonedDateTime nextAt = null;
        for (int i = 0; i < names.length; i++) {
            try {
                String[] hm = todayTimes.get(i).split(":");
                ZonedDateTime candidate = now.toLocalDate()
                        .atTime(Integer.parseInt(hm[0]), Integer.parseInt(hm[1]))
                        .atZone(ZONE);
                if (candidate.isAfter(now)) {
                    nextIndex = i;
                    nextAt = candidate;
                    break;
                }
            } catch (Exception ignored) { }
        }
        if (nextAt == null) {
            try {
                String[] hm = todayTimes.get(0).split(":");
                nextIndex = 0;
                nextAt = now.toLocalDate().plusDays(1)
                        .atTime(Integer.parseInt(hm[0]), Integer.parseInt(hm[1]))
                        .atZone(ZONE);
            } catch (Exception ignored) { }
        }
        updateCardStyles(nextIndex);
        updateNextPrayerIcon(nextIndex);
        if (nextAt == null) {
            nextName.setText("Selanjutnya");
            nextPrayerTitle.setText(modelTwo ? "Menuju --" : "--");
            nextPrayerTime.setText("--:--");
            next.setText("--:--:--");
            if (progressTrack != null) progressTrack.setProgress(0f);
            return;
        }
        long seconds = Math.max(0, Duration.between(now, nextAt).getSeconds());
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;
        nextName.setText("Selanjutnya");
        // Model 2 uses the "Menuju" prefix; the legacy panel shows only the prayer name.
        nextPrayerTitle.setText(modelTwo ? "Menuju " + names[nextIndex] : names[nextIndex]);
        nextPrayerTime.setText(todayTimes.get(nextIndex));
        next.setText(String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, remainingSeconds));
        if (progressTrack != null) {
            ZonedDateTime previous = null;
            int previousIndex = -1;
            previousIndex = nextIndex == 0 ? 7 : nextIndex - 1;
            if (previousIndex >= 0) {
                try {
                    String[] hm = todayTimes.get(previousIndex).split(":");
                    previous = now.toLocalDate()
                            .atTime(Integer.parseInt(hm[0]), Integer.parseInt(hm[1])).atZone(ZONE);
                } catch (Exception ignored) { }
            }
            if (previous == null || !previous.isBefore(nextAt)) {
                progressTrack.setProgress(0f);
            } else {
                long total = Math.max(1L, Duration.between(previous, nextAt).getSeconds());
                long elapsed = Math.max(0L, Duration.between(previous, now).getSeconds());
                progressTrack.setProgress(Math.min(1f, elapsed / (float) total));
            }
        }
    }

    private GradientDrawable createDockBackground(boolean lightGlass, int opacity, boolean bandVisible, boolean cinematic) {
        GradientDrawable bg = new GradientDrawable();
        if (!bandVisible) {
            bg.setColor(Color.TRANSPARENT);
            return bg;
        }
        if (lightGlass) {
            bg.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            bg.setColors(new int[]{0x24FFFFFF, 0x0DFFFFFF});
        } else {
            int fill = cinematic ? 0x8C121014 : ((Math.round(opacity * 2.55f) << 24) | 0x00141416);
            bg.setColor(fill);
        }
        bg.setCornerRadius(dp(26));
        int edge = lightGlass ? 0x38FFFFFF : cinematic ? 0x26FFFFFF : 0x1FFFFFFF;
        bg.setStroke(dp(1), edge);
        return bg;
    }

    private TextView text(String s, int size, int color, boolean medium) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setShadowLayer(dp(2), 0f, dp(1), 0xB3000000);
        return t;
    }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-2,-2); }
    private int dp(float v) { return (int)(v*activity.getResources().getDisplayMetrics().density + .5f); }
}