package com.gathin.tvhome;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
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
    private TextView dateDay, dateNumber, dateMonth, hijriDate, nextPrayerTitle, nextPrayerTime;
    private final boolean modelTwo;
    private ProgressTrack progressTrack;
    private List<String> tomorrowTimes = new ArrayList<>();
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
    static PrayerTimesPanel attach(Activity a, FrameLayout root, boolean dockGlass, int dockOpacity, boolean dockBand, boolean enabled, String position) {
        PrayerTimesPanel p = new PrayerTimesPanel(a, dockGlass, dockOpacity, dockBand);
        p.enabled = enabled;
        p.positionHidden = "hidden".equals(position);
        float d = a.getResources().getDisplayMetrics().density;
        int screenWidth = a.getResources().getDisplayMetrics().widthPixels;
        int panelWidth = Math.min((int)(390*d), (int)(screenWidth * .34f));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(panelWidth, -2,
                Gravity.TOP | ("left".equals(position) ? Gravity.START : Gravity.END));
        lp.topMargin = (int)(28*d);
        if ("left".equals(position)) lp.leftMargin = (int)(28*d);
        else lp.rightMargin = (int)(28*d);
        root.addView(p.panel, lp);
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

    private PrayerTimesPanel(Activity a, boolean dockGlass, int dockOpacity, boolean dockBand) {
        activity = a;
        modelTwo = a.getSharedPreferences(Config.SETTINGS_NAME, Activity.MODE_PRIVATE)
                .getInt("prayer_panel_design", 1) == 2;
        panel = new LinearLayout(a);
        if (modelTwo) {
            buildModelTwo();
            updateDateCard();
            updateCardStyles(-1);
            return;
        }
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xE62B303B, 0xD91B202A, 0xE62A303A});
        bg.setCornerRadius(dp(26));
        bg.setStroke(dp(1), 0x78FFFFFF);
        panel.setBackground(bg);
        panel.setElevation(dp(12));

        LinearLayout header = new LinearLayout(a);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView mosque = text("☾", 28, Color.WHITE, true);
        mosque.setGravity(Gravity.CENTER);
        header.addView(mosque, new LinearLayout.LayoutParams(dp(44), -1));
        View headLine = divider();
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(1), dp(34));
        hlp.setMargins(dp(7), 0, dp(12), 0);
        header.addView(headLine, hlp);
        TextView title = text("Jadwal Sholat", 23, Color.WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        View sep = divider();
        LinearLayout.LayoutParams sepP = new LinearLayout.LayoutParams(-1, dp(1));
        sepP.setMargins(0, dp(8), 0, dp(12));
        panel.addView(sep, sepP);

        LinearLayout date = glassCard();
        date.setGravity(Gravity.CENTER);
        date.setPadding(dp(8), dp(8), dp(8), dp(8));
        panel.addView(date, new LinearLayout.LayoutParams(-1, dp(112)));
        dateDay = text("", 17, Color.WHITE, true);
        date.addView(dateDay, wrap());
        dateNumber = text("", 54, Color.WHITE, true);
        date.addView(dateNumber, wrap());
        dateMonth = text("", 16, 0xFFE5E8ED, true);
        date.addView(dateMonth, wrap());
        hijriDate = text("", 11, 0xFFD6DCE5, true);
        date.addView(hijriDate, wrap());

        LinearLayout countdown = glassCard();
        countdown.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams countP = new LinearLayout.LayoutParams(-1, dp(88));
        countP.topMargin = dp(11);
        panel.addView(countdown, countP);
        countdown.addView(text("◷  Sholat berikutnya", 14, 0xFFE8EAF0, true), wrap());
        next = text("--:--:--", 30, 0xFFFFD21F, true);
        LinearLayout.LayoutParams nextP = wrap();
        nextP.topMargin = dp(4);
        countdown.addView(next, nextP);
        nextName = text("", 1, Color.TRANSPARENT, false);

        LinearLayout nextCard = new LinearLayout(a);
        nextCard.setOrientation(LinearLayout.HORIZONTAL);
        nextCard.setGravity(Gravity.CENTER_VERTICAL);
        nextCard.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable gold = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFFFD83D, 0xFFB98508});
        gold.setCornerRadius(dp(21));
        gold.setStroke(dp(2), 0xFFFFE98A);
        nextCard.setBackground(gold);
        nextCard.setElevation(dp(9));
        LinearLayout.LayoutParams nextCardP = new LinearLayout.LayoutParams(-1, dp(72));
        nextCardP.topMargin = dp(11);
        panel.addView(nextCard, nextCardP);
        TextView nextIcon = text("☾", 27, Color.WHITE, true);
        nextCard.addView(nextIcon, new LinearLayout.LayoutParams(dp(40), -2));
        View goldLine = divider();
        LinearLayout.LayoutParams goldLineP = new LinearLayout.LayoutParams(dp(1), dp(46));
        goldLineP.setMargins(dp(7), 0, dp(10), 0);
        nextCard.addView(goldLine, goldLineP);
        LinearLayout nextLabels = new LinearLayout(a);
        nextLabels.setOrientation(LinearLayout.VERTICAL);
        nextCard.addView(nextLabels, new LinearLayout.LayoutParams(0, -2, 1f));
        nextPrayerTitle = text("Subuh", 19, Color.WHITE, true);
        nextLabels.addView(nextPrayerTitle, wrap());
        nextPrayerTime = text("--:--", 15, 0xFFF9F0D2, true);
        nextLabels.addView(nextPrayerTime, wrap());
        nextCard.addView(text("›", 32, Color.WHITE, true), wrap());

        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listP = new LinearLayout.LayoutParams(-1, -2);
        listP.topMargin = dp(11);
        panel.addView(list, listP);
        String[] icons = {"☼", "☀", "☼", "☀", "☀", "☁", "☼", "☾"};
        for (int i = 0; i < names.length; i++) {
            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(8), dp(4), dp(8), dp(4));
            GradientDrawable itemBg = new GradientDrawable();
            itemBg.setColor(0x00000000);
            itemBg.setCornerRadius(dp(13));
            item.setBackground(itemBg);
            LinearLayout.LayoutParams itemP = new LinearLayout.LayoutParams(-1, dp(42));
            if (i > 0) itemP.topMargin = dp(1);
            list.addView(item, itemP);
            TextView icon = text(icons[i], 21, Color.WHITE, true);
            icon.setGravity(Gravity.CENTER);
            item.addView(icon, new LinearLayout.LayoutParams(dp(40), -1));
            TextView label = text(names[i], 15, 0xFFF7F8FA, true);
            item.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            values[i] = text("--:--", 17, Color.WHITE, true);
            item.addView(values[i], wrap());
            cards[i] = item;
            if (i < names.length - 1) {
                View line = divider();
                LinearLayout.LayoutParams lineP = new LinearLayout.LayoutParams(-1, dp(1));
                lineP.setMargins(dp(8), 0, dp(8), 0);
                list.addView(line, lineP);
            }
        }

        LinearLayout footer = glassCard();
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerP = new LinearLayout.LayoutParams(-1, dp(48));
        footerP.topMargin = dp(11);
        panel.addView(footer, footerP);
        footer.addView(text("●", 16, Color.WHITE, true), wrap());
        footer.addView(text("  BANDUNG, JAWA BARAT", 16, Color.WHITE, true), wrap());

        status = text("Memuat jadwal…", 8, 0xFFDCE3EC, true);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusP = wrap();
        statusP.topMargin = dp(4);
        panel.addView(status, statusP);
        panel.setFocusable(false);
        panel.setClickable(false);
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        updateDateCard();
        updateCardStyles(-1);
    }


    /**
     * Compact portrait layout for Model 2. It deliberately uses the same panel width
     * and upper-corner placement as the original Build 211 panel.
     */
    private void buildModelTwo() {
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        panel.setClipChildren(false);
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xE8EAF2F8, 0xE4DCE6E8, 0xE6D8DCE8});
        glass.setCornerRadius(dp(27));
        glass.setStroke(dp(1), 0x77FFFFFF);
        panel.setBackground(glass);
        panel.setElevation(dp(12));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(86)));
        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(heading, new LinearLayout.LayoutParams(0, -1, 1f));
        TextView title = cleanText("Jadwal Sholat", 22, true);
        heading.addView(title, wrap());
        dateDay = cleanText("--", 12, true);
        LinearLayout.LayoutParams dayP = wrap();
        dayP.topMargin = dp(5);
        heading.addView(dateDay, dayP);
        // Model 2 follows the reference: Gregorian date on one line, Hijri date below it.
        dateDay.setVisibility(View.GONE);
        LinearLayout dateLine = new LinearLayout(activity);
        dateLine.setOrientation(LinearLayout.HORIZONTAL);
        dateLine.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(dateLine, wrap());
        dateNumber = cleanText("--", 20, true);
        dateLine.addView(dateNumber, wrap());
        dateMonth = cleanText("--", 12, false);
        LinearLayout.LayoutParams monthP = wrap();
        monthP.leftMargin = dp(6);
        dateLine.addView(dateMonth, monthP);
        hijriDate = cleanText("--", 10, false);
        LinearLayout.LayoutParams hijriP = wrap();
        hijriP.topMargin = dp(3);
        heading.addView(hijriDate, hijriP);

        LinearLayout mosqueBox = new LinearLayout(activity);
        mosqueBox.setOrientation(LinearLayout.VERTICAL);
        mosqueBox.setGravity(Gravity.CENTER);
        header.addView(mosqueBox, new LinearLayout.LayoutParams(dp(96), -1));
        TextView place = cleanText("BANDUNG &\nSEKITARNYA", 8, true);
        place.setGravity(Gravity.CENTER);
        place.setMaxLines(2);
        place.setLineSpacing(dp(1), 1f);
        mosqueBox.addView(place, wrap());
        MosqueGlyph mosque = new MosqueGlyph(activity);
        LinearLayout.LayoutParams mosqueP = new LinearLayout.LayoutParams(dp(42), dp(42));
        mosqueP.topMargin = dp(2);
        mosqueBox.addView(mosque, mosqueP);

        LinearLayout countdownCard = new LinearLayout(activity);
        countdownCard.setOrientation(LinearLayout.VERTICAL);
        countdownCard.setGravity(Gravity.CENTER);
        countdownCard.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable countBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xDDF7F1EE, 0xD8E8D9D7});
        countBg.setCornerRadius(dp(24));
        countBg.setStroke(dp(1), 0x66FFFFFF);
        countdownCard.setBackground(countBg);
        LinearLayout.LayoutParams countCardP = new LinearLayout.LayoutParams(-1, dp(164));
        countCardP.topMargin = dp(9);
        panel.addView(countdownCard, countCardP);
        nextName = cleanText("Selanjutnya", 15, false);
        countdownCard.addView(nextName, wrap());
        nextPrayerTitle = cleanText("Menuju --", 20, true);
        LinearLayout.LayoutParams targetP = wrap();
        targetP.topMargin = dp(4);
        countdownCard.addView(nextPrayerTitle, targetP);
        next = cleanText("--:--:--", 34, true);
        LinearLayout.LayoutParams countdownP = wrap();
        countdownP.topMargin = dp(2);
        countdownCard.addView(next, countdownP);
        nextPrayerTime = cleanText("--:--", 12, false);
        LinearLayout.LayoutParams timeP = wrap();
        timeP.topMargin = dp(2);
        countdownCard.addView(nextPrayerTime, timeP);
        progressTrack = new ProgressTrack(activity);
        LinearLayout.LayoutParams progressP = new LinearLayout.LayoutParams(-1, dp(12));
        progressP.topMargin = dp(9);
        countdownCard.addView(progressTrack, progressP);

        LinearLayout schedule = new LinearLayout(activity);
        schedule.setOrientation(LinearLayout.VERTICAL);
        schedule.setPadding(dp(8), dp(7), dp(8), dp(7));
        GradientDrawable scheduleBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xDDF4EBE8, 0xD9E5E0E8});
        scheduleBg.setCornerRadius(dp(24));
        scheduleBg.setStroke(dp(1), 0x55FFFFFF);
        schedule.setBackground(scheduleBg);
        LinearLayout.LayoutParams scheduleP = new LinearLayout.LayoutParams(-1, -2);
        scheduleP.topMargin = dp(9);
        panel.addView(schedule, scheduleP);
        for (int i = 0; i < names.length; i++) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(9), dp(2), dp(10), dp(2));
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setColor(Color.TRANSPARENT);
            rowBg.setCornerRadius(dp(18));
            row.setBackground(rowBg);
            LinearLayout.LayoutParams rowP = new LinearLayout.LayoutParams(-1, dp(48));
            if (i > 0) rowP.topMargin = dp(1);
            schedule.addView(row, rowP);
            cards[i] = row;
            Glyph icon = new Glyph(activity, i);
            LinearLayout.LayoutParams iconP = new LinearLayout.LayoutParams(dp(25), dp(25));
            iconP.rightMargin = dp(10);
            row.addView(icon, iconP);
            TextView label = cleanText(names[i], 14, true);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            values[i] = cleanText("--:--", 15, true);
            values[i].setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            row.addView(values[i], new LinearLayout.LayoutParams(dp(62), -2));
        }
        status = cleanText("Memuat jadwal Kemenag…", 8, false);
        status.setGravity(Gravity.CENTER);
        status.setMaxLines(2);
        LinearLayout.LayoutParams statusP = wrap();
        statusP.topMargin = dp(5);
        panel.addView(status, statusP);
        panel.setFocusable(false);
        panel.setClickable(false);
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    private TextView cleanText(String value, int size, boolean bold) {
        TextView view = text(value, size, 0xFF101014, bold);
        view.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
        return view;
    }

    private static final class ProgressTrack extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float progress;
        ProgressTrack(Activity a) { super(a); }
        void setProgress(float value) { progress = Math.max(0f, Math.min(1f, value)); invalidate(); }
        private float d(float value) { return value * getResources().getDisplayMetrics().density; }
        @Override protected void onDraw(Canvas canvas) {
            float y = getHeight() / 2f, left = d(5), right = getWidth() - d(5);
            float x = left + (right - left) * progress;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(d(5));
            paint.setColor(0x44808080);
            canvas.drawLine(left, y, right, y, paint);
            paint.setColor(0xFF151515);
            canvas.drawLine(left, y, x, y, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFF5E7E3);
            canvas.drawCircle(x, y, d(7), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(d(1.5f));
            paint.setColor(0xFF151515);
            canvas.drawCircle(x, y, d(7), paint);
        }
    }

    private static final class MosqueGlyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        MosqueGlyph(Activity a) { super(a); }
        @Override protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            float x = getWidth() / 2f, y = getHeight() / 2f;
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(x - 15*d, y - 1*d, x + 15*d, y + 15*d, paint);
            canvas.drawArc(x - 13*d, y - 20*d, x + 13*d, y + 7*d, 180, 180, true, paint);
            canvas.drawRect(x - 20*d, y - 15*d, x - 17*d, y + 15*d, paint);
            canvas.drawRect(x + 17*d, y - 15*d, x + 20*d, y + 15*d, paint);
            canvas.drawRect(x - 24*d, y - 22*d, x - 21*d, y + 15*d, paint);
            canvas.drawRect(x + 21*d, y - 22*d, x + 24*d, y + 15*d, paint);
            canvas.drawCircle(x, y - 18*d, 3*d, paint);
            paint.setColor(0xFFECE5E3);
            canvas.drawRect(x - 3*d, y + 5*d, x + 3*d, y + 15*d, paint);
        }
    }

    private static final class Glyph extends View {
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Glyph(Activity a, int kind) { super(a); this.kind = kind; }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            paint.setColor(0xFF080808);
            paint.setStyle(Paint.Style.FILL);
            if (kind == 0 || kind == 6 || kind == 7) {
                Path crescent = new Path();
                crescent.moveTo(cx + 5*d, cy - 8*d);
                crescent.cubicTo(cx - 8*d, cy - 9*d, cx - 10*d, cy + 8*d, cx + 3*d, cy + 8*d);
                crescent.cubicTo(cx - 1*d, cy + 3*d, cx + 1*d, cy - 3*d, cx + 5*d, cy - 8*d);
                canvas.drawPath(crescent, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(0.8f*d);
                paint.setColor(0x66000000);
                canvas.drawLine(cx - 10*d, cy + 10*d, cx + 10*d, cy + 10*d, paint);
                canvas.drawLine(cx - 7*d, cy + 13*d, cx + 7*d, cy + 13*d, paint);
                return;
            }
            if (kind == 1 || kind == 2 || kind == 5) {
                canvas.save();
                canvas.clipRect(cx - 12*d, cy - 12*d, cx + 12*d, cy + 2*d);
                canvas.drawCircle(cx, cy + 1*d, 6.2f*d, paint);
                canvas.restore();
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(0.8f*d);
                paint.setColor(0x66000000);
                canvas.drawLine(cx - 12*d, cy + 4*d, cx + 12*d, cy + 4*d, paint);
                canvas.drawLine(cx - 9*d, cy + 7*d, cx + 9*d, cy + 7*d, paint);
                return;
            }
            canvas.drawCircle(cx, cy, 6.2f*d, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.8f*d);
            paint.setColor(0x66000000);
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * i / 4d;
                float x1 = cx + (float)Math.cos(angle) * 9*d;
                float y1 = cy + (float)Math.sin(angle) * 9*d;
                float x2 = cx + (float)Math.cos(angle) * 12*d;
                float y2 = cy + (float)Math.sin(angle) * 12*d;
                canvas.drawLine(x1, y1, x2, y2, paint);
            }
        }
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
        dateDay.setText(today.format(DateTimeFormatter.ofPattern("EEEE", id)));
        dateNumber.setText(String.valueOf(today.getDayOfMonth()));
        dateMonth.setText(today.format(DateTimeFormatter.ofPattern("MMMM yyyy", id)));
        try {
            IslamicCalendar h = new IslamicCalendar(new Date());
            String[] months = {"Muharram", "Safar", "Rabiul Awal", "Rabiul Akhir", "Jumadil Awal",
                    "Jumadil Akhir", "Rajab", "Syaban", "Ramadan", "Syawal", "Zulkaidah", "Zulhijah"};
            hijriDate.setText(h.get(Calendar.DAY_OF_MONTH) + " " +
                    months[Math.max(0, Math.min(11, h.get(Calendar.MONTH)))] + " " +
                    h.get(Calendar.YEAR) + " H");
        } catch (Exception ignored) {
            hijriDate.setText("");
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
                    }
                }
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
            int[] cols = {field(fields,"imsak"), field(fields,"fajr"), field(fields,"terbit"),
                    field(fields,"dhuha"), field(fields,"dhuhr"), field(fields,"asr"),
                    field(fields,"maghrib"), field(fields,"isha")};
            List<String> currentTimes = null;
            List<String> nextTimes = null;
            for (int i = 0; i < days.length(); i++) {
                JSONArray day = days.optJSONArray(i);
                if (day == null || dateCol < 0) continue;
                String dayDate = day.optString(dateCol, "");
                if (!date.toString().equals(dayDate) && !date.plusDays(1).toString().equals(dayDate)) continue;
                List<String> rowTimes = new ArrayList<>();
                for (int p = 0; p < 8; p++) {
                    String t = cols[p] >= 0 ? day.optString(cols[p], "--:--") : "--:--";
                    rowTimes.add(t);
                }
                if (date.toString().equals(dayDate)) currentTimes = rowTimes;
                else nextTimes = rowTimes;
            }
            if (currentTimes == null || currentTimes.isEmpty()) {
                status.setText("Jadwal tanggal ini belum tersedia");
                return;
            }
            todayTimes.clear();
            todayTimes.addAll(currentTimes);
            tomorrowTimes.clear();
            if (nextTimes != null) tomorrowTimes.addAll(nextTimes);
            for (int p = 0; p < 8; p++) values[p].setText(todayTimes.get(p));
            loadedDate = date;
            status.setText("Kementerian Agama Republik Indonesia • "
                    + date.format(DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy", new Locale("id","ID"))));
            updateNext();
        } catch (Exception e) {
            status.setText("Jadwal belum dapat dibaca");
        }
    }

    private int field(JSONArray a, String key) {
        for (int i=0;i<a.length();i++) if (key.equalsIgnoreCase(a.optString(i))) return i;
        return -1;
    }

    private void updateCardStyles(int nextIndex) {
        for (int i = 0; i < cards.length; i++) {
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setCornerRadius(dp(13));
            cardBg.setColor(i == nextIndex ? 0x24FFD43B : 0x00000000);
            cardBg.setStroke(dp(i == nextIndex ? 2 : 0), i == nextIndex ? 0xFFFFD52D : Color.TRANSPARENT);
            cards[i].setBackground(cardBg);
        }
    }

    private void updateNext() {
        if (todayTimes.isEmpty()) return;
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        int nextIndex = -1;
        ZonedDateTime nextAt = null;
        for (int i = modelTwo ? 0 : 1; i < todayTimes.size(); i++) {
            try {
                String[] hm = todayTimes.get(i).split(":");
                ZonedDateTime candidate = now.toLocalDate()
                        .atTime(Integer.parseInt(hm[0]), Integer.parseInt(hm[1])).atZone(ZONE);
                if (candidate.isAfter(now)) {
                    nextIndex = i;
                    nextAt = candidate;
                    break;
                }
            } catch (Exception ignored) { }
        }
        int tomorrowIndex = modelTwo ? 0 : 1;
        if (nextAt == null && tomorrowTimes.size() > tomorrowIndex) {
            try {
                String[] hm = tomorrowTimes.get(tomorrowIndex).split(":");
                nextIndex = tomorrowIndex;
                nextAt = now.toLocalDate().plusDays(1)
                        .atTime(Integer.parseInt(hm[0]), Integer.parseInt(hm[1])).atZone(ZONE);
            } catch (Exception ignored) { }
        }
        updateCardStyles(nextIndex);
        if (nextAt == null) {
            nextName.setText(modelTwo ? "Selanjutnya" : "Sholat Berikutnya");
            nextPrayerTitle.setText("Jadwal berikutnya belum tersedia");
            nextPrayerTime.setText("--:--");
            next.setText("--:--:--");
            if (progressTrack != null) progressTrack.setProgress(0f);
            return;
        }
        long seconds = Math.max(0, Duration.between(now, nextAt).getSeconds());
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;
        nextName.setText(modelTwo ? "Selanjutnya" : "Sholat Berikutnya");
        nextPrayerTitle.setText(modelTwo ? "Menuju " + names[nextIndex] : names[nextIndex]);
        nextPrayerTime.setText(nextAt.toLocalDate().isAfter(now.toLocalDate())
                ? tomorrowTimes.get(nextIndex) : todayTimes.get(nextIndex));
        next.setText(String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, remainingSeconds));
        if (progressTrack != null) {
            ZonedDateTime previous = null;
            if (nextIndex == 0 && nextAt.toLocalDate().isAfter(now.toLocalDate())) {
                try {
                    String[] hm = todayTimes.get(7).split(":");
                    previous = now.toLocalDate().atTime(Integer.parseInt(hm[0]), Integer.parseInt(hm[1])).atZone(ZONE);
                } catch (Exception ignored) { }
            } else if (nextIndex > 0) {
                try {
                    String[] hm = todayTimes.get(nextIndex - 1).split(":");
                    previous = now.toLocalDate().atTime(Integer.parseInt(hm[0]), Integer.parseInt(hm[1])).atZone(ZONE);
                } catch (Exception ignored) { }
            }
            if (previous == null || !previous.isBefore(nextAt)) previous = now;
            long total = Math.max(1L, Duration.between(previous, nextAt).getSeconds());
            long elapsed = Math.max(0L, Duration.between(previous, now).getSeconds());
            progressTrack.setProgress(Math.min(1f, elapsed / (float) total));
        }
    }

    private TextView text(String s, int size, int color, boolean medium) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-2,-2); }
    private int dp(float v) { return (int)(v*activity.getResources().getDisplayMetrics().density + .5f); }
}
