package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.chrono.HijrahDate;
import java.time.temporal.ChronoField;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class PrayerTimesPanel {
    private static final ZoneId ZONE = ZoneId.of("Asia/Jakarta");
    private static final String[] NAMES = {
            "Imsak", "Subuh", "Terbit", "Dhuha", "Dzuhur", "Ashar", "Maghrib", "Isya"
    };
    private static final String[] FIELD_NAMES = {
            "imsak", "fajr", "sunrise", "dhuha", "dhuhr", "asr", "maghrib", "isha"
    };

    private final Activity activity;
    private final LinearLayout panel;
    private TextView countdown;
    private TextView nextLabel;
    private TextView locationDate;
    private TextView dateGregorian, dateHijri;
    private ProgressTrack progressTrack;
    private final boolean modelTwo;
    private final TextView[] timeValues = new TextView[8];
    private final View[] rows = new View[8];
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String[] todayTimes;
    private String[] tomorrowTimes;
    private LocalDate loadedDate;
    private boolean loading;

    static void attach(Activity activity, FrameLayout root, Config config) {
        PrayerTimesPanel widget = new PrayerTimesPanel(activity, config);
        float density = activity.getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams params = widget.modelTwo
                ? new FrameLayout.LayoutParams(widget.dp(440), widget.dp(760), Gravity.TOP | Gravity.END)
                : new FrameLayout.LayoutParams(widget.dp(540), widget.dp(274), Gravity.TOP | Gravity.END);
        params.topMargin = widget.modelTwo ? widget.dp(42) : widget.dp(116);
        params.setMarginEnd(widget.modelTwo ? widget.dp(42) : widget.dp(34));
        root.addView(widget.panel, params);
        widget.load();
        widget.handler.post(widget.tick);
    }

    private PrayerTimesPanel(Activity activity, Config config) {
        this.activity = activity;
        modelTwo = activity.getSharedPreferences(Config.SETTINGS_NAME, 0).getInt("prayer_panel_design", 1) == 2;
        panel = new LinearLayout(activity);
        if (modelTwo) {
            buildModelTwo();
            updateLocationDate(LocalDate.now(ZONE));
            return;
        }
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(20), dp(15), dp(18), dp(15));
        panel.setClipToPadding(false);
        panel.setClipChildren(false);
        panel.setBackground(makePanelBackground(config));
        panel.setElevation(dp(3));

        LinearLayout left = new LinearLayout(activity);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, -1, 1.05f);
        leftParams.rightMargin = dp(16);
        panel.addView(left, leftParams);

        TextView title = text("Jadwal Sholat", 18, Color.WHITE, true);
        title.setLetterSpacing(.015f);
        left.addView(title, wrap());

        countdown = text("--:--:--", 43, Color.WHITE, true);
        countdown.setLetterSpacing(-.025f);
        LinearLayout.LayoutParams countParams = wrap();
        countParams.topMargin = dp(16);
        left.addView(countdown, countParams);

        LinearLayout units = new LinearLayout(activity);
        units.setOrientation(LinearLayout.HORIZONTAL);
        String[] unitNames = {"Jam", "Menit", "Detik"};
        for (String unit : unitNames) {
            TextView label = text(unit, 13, 0xE6FFFFFF, true);
            LinearLayout.LayoutParams unitParams = new LinearLayout.LayoutParams(0, -2, 1f);
            unitParams.leftMargin = dp(3);
            units.addView(label, unitParams);
        }
        LinearLayout.LayoutParams unitsParams = wrap();
        unitsParams.topMargin = dp(3);
        left.addView(units, unitsParams);

        nextLabel = text("Menuju waktu sholat berikutnya", 18, Color.WHITE, true);
        nextLabel.setMaxLines(2);
        LinearLayout.LayoutParams nextParams = wrap();
        nextParams.topMargin = dp(26);
        left.addView(nextLabel, nextParams);

        locationDate = text("Bandung • --", 15, 0xF2FFFFFF, true);
        LinearLayout.LayoutParams dateParams = wrap();
        dateParams.topMargin = dp(13);
        left.addView(locationDate, dateParams);

        View divider = new View(activity);
        divider.setBackgroundColor(0x35FFFFFF);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(1), -1);
        dividerParams.leftMargin = dp(3);
        dividerParams.rightMargin = dp(15);
        panel.addView(divider, dividerParams);

        LinearLayout right = new LinearLayout(activity);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(right, new LinearLayout.LayoutParams(0, -1, 1f));

        for (int i = 0; i < NAMES.length; i++) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(9), dp(2), dp(10), dp(2));
            GradientDrawable normal = new GradientDrawable();
            normal.setColor(Color.TRANSPARENT);
            normal.setCornerRadius(dp(24));
            row.setBackground(normal);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, 0, 1f);
            rowParams.topMargin = i == 0 ? 0 : dp(1);
            right.addView(row, rowParams);
            rows[i] = row;

            Glyph icon = new Glyph(activity, i);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(25), dp(25));
            iconParams.rightMargin = dp(13);
            row.addView(icon, iconParams);

            TextView name = text(NAMES[i], 17, Color.WHITE, true);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

            timeValues[i] = text("--:--", 18, Color.WHITE, true);
            timeValues[i].setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            row.addView(timeValues[i], new LinearLayout.LayoutParams(dp(76), -2));
        }

        panel.setFocusable(false);
        panel.setClickable(false);
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        updateLocationDate(LocalDate.now(ZONE));
    }

    private GradientDrawable makePanelBackground(Config config) {
        GradientDrawable background;
        if (!config.dockBand) {
            background = new GradientDrawable();
            background.setColor(Color.TRANSPARENT);
            background.setCornerRadius(dp(24));
            return background;
        }
        if (config.dockGlass) {
            background = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x38FFFFFF, 0x1AFFFFFF, 0x12FFFFFF});
        } else {
            int alpha = Math.max(30, Math.min(255, Math.round(config.dockOpacity * 2.55f)));
            background = new GradientDrawable();
            background.setColor((alpha << 24) | 0x00121416);
        }
        background.setCornerRadius(dp(24));
        background.setStroke(dp(1), config.dockGlass ? 0x48FFFFFF : 0x28FFFFFF);
        return background;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            LocalDate today = LocalDate.now(ZONE);
            if (loadedDate == null || !today.equals(loadedDate)) load();
            else updateCountdown();
            handler.postDelayed(this, 1000L);
        }
    };

    private void load() {
        if (loading) return;
        loading = true;
        LocalDate date = LocalDate.now(ZONE);
        String cacheKey = "schedule_" + date.getYear();
        String cached = activity.getSharedPreferences("prayer_times_panel", 0)
                .getString(cacheKey, "");
        if (!cached.isEmpty()) parse(cached, date);

        Thread worker = new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("https://sajadah.org/api/v1/times/id/kota-bandung/"
                        + date.getYear() + ".json");
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("Accept", "application/json");
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
                String json = body.toString();
                activity.getSharedPreferences("prayer_times_panel", 0).edit()
                        .putString(cacheKey, json).apply();
                activity.runOnUiThread(() -> parse(json, LocalDate.now(ZONE)));
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (todayTimes == null) {
                        nextLabel.setText("Jadwal sholat belum tersedia");
                        countdown.setText("--:--:--");
                    }
                });
            } finally {
                if (connection != null) connection.disconnect();
                loading = false;
            }
        }, "PrayerTimesFetch");
        worker.setDaemon(true);
        worker.start();
    }

    private void parse(String json, LocalDate date) {
        try {
            JSONObject object = new JSONObject(json);
            JSONArray fields = object.getJSONArray("fields");
            JSONArray days = object.getJSONArray("days");
            int dateColumn = field(fields, "date");
            if (dateColumn < 0) throw new IllegalStateException("Missing date field");

            String[] current = findDay(days, fields, dateColumn, date);
            String[] next = findDay(days, fields, dateColumn, date.plusDays(1));
            if (current == null) throw new IllegalStateException("Missing today's schedule");

            todayTimes = current;
            tomorrowTimes = next;
            loadedDate = date;
            for (int i = 0; i < timeValues.length; i++) {
                timeValues[i].setText(todayTimes[i] == null ? "--:--" : todayTimes[i]);
            }
            updateLocationDate(date);
            updateCountdown();
        } catch (Exception error) {
            if (todayTimes == null) nextLabel.setText("Jadwal sholat belum dapat dibaca");
        }
    }

    private String[] findDay(JSONArray days, JSONArray fields, int dateColumn, LocalDate date) {
        int[] columns = new int[FIELD_NAMES.length];
        for (int i = 0; i < columns.length; i++) columns[i] = field(fields, FIELD_NAMES[i]);

        for (int i = 0; i < days.length(); i++) {
            JSONArray day = days.optJSONArray(i);
            if (day == null || !date.toString().equals(day.optString(dateColumn))) continue;
            String[] result = new String[FIELD_NAMES.length];
            for (int p = 0; p < result.length; p++) {
                result[p] = columns[p] >= 0 ? cleanTime(day.optString(columns[p], "")) : null;
            }
            if (result[1] == null) return null;
            if (result[0] == null) result[0] = shift(result[1], -10);
            if (result[2] == null) result[2] = shift(result[1], 90);
            if (result[3] == null) result[3] = shift(result[2], 20);
            return result;
        }
        return null;
    }

    private int field(JSONArray fields, String key) {
        for (int i = 0; i < fields.length(); i++) {
            if (key.equalsIgnoreCase(fields.optString(i))) return i;
        }
        return -1;
    }

    private String cleanTime(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.length() < 5 || !v.substring(0, 5).matches("\\d{2}:\\d{2}")) return null;
        return v.substring(0, 5);
    }

    private String shift(String value, int minutes) {
        try {
            return LocalTime.parse(value, DateTimeFormatter.ofPattern("HH:mm"))
                    .plusMinutes(minutes).format(DateTimeFormatter.ofPattern("HH:mm"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void updateCountdown() {
        if (todayTimes == null) return;
        LocalDateTime now = LocalDateTime.now(ZONE);
        LocalDateTime target = null;
        int targetIndex = -1;
        for (int i = 0; i < todayTimes.length; i++) {
            LocalDateTime candidate = at(now.toLocalDate(), todayTimes[i]);
            if (candidate != null && candidate.isAfter(now)) {
                target = candidate;
                targetIndex = i;
                break;
            }
        }
        if (target == null && tomorrowTimes != null) {
            target = at(now.toLocalDate().plusDays(1), tomorrowTimes[0]);
            targetIndex = 0;
        }
        if (target == null) {
            countdown.setText("--:--:--");
            nextLabel.setText("Jadwal berikutnya belum tersedia");
            clearHighlights();
            return;
        }

        long seconds = Math.max(0, Duration.between(now, target).getSeconds());
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainder = seconds % 60;
        countdown.setText(String.format(Locale.ROOT, "%02d:%02d:%02d",
                hours, minutes, remainder));
        nextLabel.setText((modelTwo ? "Menuju " : "Menuju waktu sholat ") + NAMES[targetIndex]);
        if (progressTrack != null) {
            LocalDateTime previous;
            if (targetIndex == 0 && target.toLocalDate().isAfter(now.toLocalDate())) {
                // After today's Isya, the next target is tomorrow's Imsak. Measure progress
                // from today's Isya rather than from yesterday's end-of-day marker.
                previous = at(now.toLocalDate(), todayTimes[todayTimes.length - 1]);
            } else if (targetIndex == 0) {
                // Before today's Imsak, use a safe start-of-day fallback because yesterday's
                // schedule may not be loaded in this panel instance.
                previous = at(now.toLocalDate().minusDays(1), "23:59");
            } else {
                previous = at(now.toLocalDate(), todayTimes[targetIndex - 1]);
            }
            if (previous == null || !previous.isBefore(target)) previous = now;
            long total = Math.max(1L, Duration.between(previous, target).getSeconds());
            long elapsed = Math.max(0L, Duration.between(previous, now).getSeconds());
            progressTrack.setProgress(Math.min(1f, elapsed / (float) total));
        }
        highlight(targetIndex);
    }

    private LocalDateTime at(LocalDate date, String value) {
        try {
            if (value == null) return null;
            return LocalDateTime.of(date, LocalTime.parse(value,
                    DateTimeFormatter.ofPattern("HH:mm")));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void highlight(int selected) {
        for (int i = 0; i < rows.length; i++) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(24));
            if (i == selected) {
                bg.setColor(modelTwo ? 0xD9FFFFFF : 0x1EFFFFFF);
                if (!modelTwo) bg.setStroke(dp(2), 0x75FFFFFF);
            } else {
                bg.setColor(Color.TRANSPARENT);
            }
            rows[i].setBackground(bg);
        }
    }

    private void clearHighlights() {
        for (View row : rows) row.setBackgroundColor(Color.TRANSPARENT);
    }

    private void updateLocationDate(LocalDate date) {
        if (modelTwo) {
            Locale id = new Locale("id", "ID");
            dateGregorian.setText(date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", id)));
            dateHijri.setText(formatHijri(date));
            return;
        }
        String formatted = date.format(DateTimeFormatter.ofPattern("dd MMM", new Locale("id", "ID")));
        locationDate.setText("Bandung • " + formatted);
    }

    private String formatHijri(LocalDate date) {
        try {
            HijrahDate h = HijrahDate.from(date);
            String[] months = {"Muharram","Safar","Rabiul Awal","Rabiul Akhir","Jumadil Awal","Jumadil Akhir","Rajab","Syaban","Ramadhan","Syawal","Dzulkaidah","Dzulhijjah"};
            int m = h.get(ChronoField.MONTH_OF_YEAR);
            return h.get(ChronoField.DAY_OF_MONTH) + " " + months[Math.max(0, Math.min(11, m - 1))]
                    + " " + h.get(ChronoField.YEAR) + " H";
        } catch (Exception ignored) { return "Tanggal Hijriah"; }
    }

    private void buildModelTwo() {
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(18), dp(20), dp(18));
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xD9EAF4FF, 0xCDEBD9D3, 0xC9B8C7DD});
        glass.setCornerRadius(dp(34)); glass.setStroke(dp(2), 0x66FFFFFF);
        panel.setBackground(glass); panel.setElevation(dp(5));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL); header.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(104)));
        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.VERTICAL); heading.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(heading, new LinearLayout.LayoutParams(0, -1, 1f));
        TextView title = cleanText("Jadwal Sholat", 29, true); heading.addView(title, wrap());
        dateGregorian = cleanText("--", 18, false);
        LinearLayout.LayoutParams g = wrap(); g.topMargin = dp(5); heading.addView(dateGregorian, g);
        dateHijri = cleanText("--", 15, false);
        LinearLayout.LayoutParams h = wrap(); h.topMargin = dp(3); heading.addView(dateHijri, h);
        LinearLayout rhs = new LinearLayout(activity); rhs.setGravity(Gravity.CENTER);
        rhs.setOrientation(LinearLayout.VERTICAL);
        header.addView(rhs, new LinearLayout.LayoutParams(dp(100), -1));
        TextView place = cleanText("Bandung & Sekitarnya", 12, true); place.setGravity(Gravity.CENTER);
        rhs.addView(place, wrap());
        MosqueGlyph mosque = new MosqueGlyph(activity);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(54), dp(54)); mp.topMargin = dp(4);
        rhs.addView(mosque, mp);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER);
        card.setPadding(dp(12), dp(15), dp(12), dp(15));
        GradientDrawable cb = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xDDF9F1EF, 0xDDF3D9D5});
        cb.setCornerRadius(dp(30)); cb.setStroke(dp(1), 0x66FFFFFF); card.setBackground(cb);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(205)); cp.topMargin = dp(10);
        panel.addView(card, cp);
        TextView caption = cleanText("Selanjutnya", 19, false); card.addView(caption, wrap());
        nextLabel = cleanText("Menuju --", 28, false);
        LinearLayout.LayoutParams np = wrap(); np.topMargin = dp(7); card.addView(nextLabel, np);
        countdown = cleanText("--:--:--", 42, false);
        LinearLayout.LayoutParams tp = wrap(); tp.topMargin = dp(3); card.addView(countdown, tp);
        progressTrack = new ProgressTrack(activity);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(20)); pp.topMargin = dp(12);
        card.addView(progressTrack, pp);

        LinearLayout schedule = new LinearLayout(activity); schedule.setOrientation(LinearLayout.VERTICAL);
        schedule.setPadding(dp(9), dp(9), dp(9), dp(9));
        GradientDrawable sb = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xDDF7EAE7, 0xCDE5DCE5});
        sb.setCornerRadius(dp(30)); sb.setStroke(dp(1), 0x55FFFFFF); schedule.setBackground(sb);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 0, 1f); sl.topMargin = dp(12);
        panel.addView(schedule, sl);
        for (int i = 0; i < NAMES.length; i++) {
            LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(dp(12), dp(2), dp(12), dp(2));
            GradientDrawable rb = new GradientDrawable(); rb.setCornerRadius(dp(24)); rb.setColor(Color.TRANSPARENT);
            row.setBackground(rb); schedule.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f)); rows[i] = row;
            Glyph icon = new Glyph(activity, i, true);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(27), dp(27)); ip.rightMargin = dp(12);
            row.addView(icon, ip);
            TextView name = cleanText(NAMES[i], 19, false);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            timeValues[i] = cleanText("--:--", 19, false); timeValues[i].setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            row.addView(timeValues[i], new LinearLayout.LayoutParams(dp(82), -2));
        }
        panel.setFocusable(false); panel.setClickable(false);
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    private TextView cleanText(String value, int size, boolean bold) {
        TextView v = text(value, size, 0xFF101014, bold);
        v.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
        return v;
    }

    private static final class ProgressTrack extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float progress;
        ProgressTrack(Activity a) { super(a); }
        void setProgress(float p) { progress = Math.max(0f, Math.min(1f, p)); invalidate(); }
        private float d(float v) { return v * getResources().getDisplayMetrics().density; }
        @Override protected void onDraw(Canvas c) {
            float y = getHeight()/2f, l = d(5), r = getWidth()-d(5), x = l+(r-l)*progress;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeWidth(d(6));
            paint.setColor(0x33808080); c.drawLine(l,y,r,y,paint);
            paint.setColor(0xFF111111); c.drawLine(l,y,x,y,paint);
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xFFF7E9E5); c.drawCircle(x,y,d(9),paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(d(2)); paint.setColor(0xFF111111); c.drawCircle(x,y,d(9),paint);
        }
    }

    private static final class MosqueGlyph extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        MosqueGlyph(Activity a) { super(a); }
        @Override protected void onDraw(Canvas c) {
            float d=getResources().getDisplayMetrics().density, x=getWidth()/2f, y=getHeight()/2f;
            p.setColor(Color.BLACK); p.setStyle(Paint.Style.FILL);
            c.drawRect(x-18*d,y-1*d,x+18*d,y+17*d,p);
            c.drawArc(x-15*d,y-22*d,x+15*d,y+9*d,180,180,true,p);
            c.drawRect(x-23*d,y-17*d,x-19*d,y+17*d,p); c.drawRect(x+19*d,y-17*d,x+23*d,y+17*d,p);
            c.drawRect(x-27*d,y-24*d,x-24*d,y+17*d,p); c.drawRect(x+24*d,y-24*d,x+27*d,y+17*d,p);
            c.drawCircle(x,y-20*d,4*d,p);
            p.setColor(0xFFECE5E3); c.drawRect(x-3*d,y+6*d,x+3*d,y+17*d,p);
        }
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setTypeface(Typeface.create(bold ? "sans-serif-bold" : "sans-serif-medium",
                Typeface.NORMAL));
        view.setShadowLayer(dp(2), 0, dp(1), 0xD9000000);
        return view;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-2, -2);
    }

    private int dp(float value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + .5f);
    }

    private static final class Glyph extends View {
        private final int kind;
        private final boolean referenceStyle;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Glyph(Activity activity, int kind) { this(activity, kind, false); }
        Glyph(Activity activity, int kind, boolean referenceStyle) {
            super(activity);
            this.kind = kind;
            this.referenceStyle = referenceStyle;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = 6.2f * d;
            int gold = referenceStyle ? 0xFF080808 : (kind == 0 || kind == 6 || kind == 7) ? 0xFFC7A7FF : 0xFFFFD34E;
            if (kind == 0 || kind == 6 || kind == 7) {
                paint.setColor(gold);
                paint.setStyle(Paint.Style.FILL);
                Path crescent = new Path();
                crescent.moveTo(cx + 5*d, cy - 8*d);
                crescent.cubicTo(cx - 8*d, cy - 9*d, cx - 10*d, cy + 8*d,
                        cx + 3*d, cy + 8*d);
                crescent.cubicTo(cx - 1*d, cy + 3*d, cx + 1*d, cy - 3*d,
                        cx + 5*d, cy - 8*d);
                canvas.drawPath(crescent, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth((referenceStyle ? 0.85f : 1.7f)*d);
                if (referenceStyle) paint.setColor(0x66000000);
                canvas.drawLine(cx - 10*d, cy + 10*d, cx + 10*d, cy + 10*d, paint);
                canvas.drawLine(cx - 7*d, cy + 13*d, cx + 7*d, cy + 13*d, paint);
                return;
            }

            paint.setColor(gold);
            paint.setStyle(Paint.Style.FILL);
            if (kind == 1 || kind == 2 || kind == 5) {
                canvas.save();
                canvas.clipRect(cx - 12*d, cy - 12*d, cx + 12*d, cy + 2*d);
                canvas.drawCircle(cx, cy + 1*d, radius, paint);
                canvas.restore();
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth((referenceStyle ? 0.85f : 1.6f)*d);
                if (referenceStyle) paint.setColor(0x66000000);
                canvas.drawLine(cx - 12*d, cy + 4*d, cx + 12*d, cy + 4*d, paint);
                canvas.drawLine(cx - 9*d, cy + 7*d, cx + 9*d, cy + 7*d, paint);
            } else {
                canvas.drawCircle(cx, cy, radius, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth((referenceStyle ? 0.85f : 1.7f)*d);
                if (referenceStyle) paint.setColor(0x66000000);
                for (int a = 0; a < 8; a++) {
                    double angle = Math.PI * a / 4d;
                    float x1 = cx + (float)Math.cos(angle) * 9*d;
                    float y1 = cy + (float)Math.sin(angle) * 9*d;
                    float x2 = cx + (float)Math.cos(angle) * 12*d;
                    float y2 = cy + (float)Math.sin(angle) * 12*d;
                    canvas.drawLine(x1, y1, x2, y2, paint);
                }
            }
        }
    }
}