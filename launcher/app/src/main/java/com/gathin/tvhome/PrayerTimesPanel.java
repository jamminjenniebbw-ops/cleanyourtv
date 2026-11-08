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
    private final TextView countdown;
    private final TextView nextLabel;
    private final TextView locationDate;
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
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                widget.dp(690), widget.dp(274), Gravity.TOP | Gravity.END);
        params.topMargin = widget.dp(116);
        params.setMarginEnd(widget.dp(34));
        root.addView(widget.panel, params);
        widget.load();
        widget.handler.post(widget.tick);
    }

    private PrayerTimesPanel(Activity activity, Config config) {
        this.activity = activity;
        panel = new LinearLayout(activity);
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
        nextLabel.setText("Menuju waktu " + NAMES[targetIndex]);
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
                bg.setColor(0x1EFFFFFF);
                bg.setStroke(dp(2), 0x75FFFFFF);
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
        String formatted = date.format(DateTimeFormatter.ofPattern("dd MMM",
                new Locale("id", "ID")));
        locationDate.setText("Bandung • " + formatted);
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
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Glyph(Activity activity, int kind) {
            super(activity);
            this.kind = kind;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = 6.2f * d;
            int gold = (kind == 0 || kind == 6 || kind == 7) ? 0xFFC7A7FF : 0xFFFFD34E;
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
                paint.setStrokeWidth(1.7f*d);
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
                paint.setStrokeWidth(1.6f*d);
                canvas.drawLine(cx - 12*d, cy + 4*d, cx + 12*d, cy + 4*d, paint);
                canvas.drawLine(cx - 9*d, cy + 7*d, cx + 9*d, cy + 7*d, paint);
            } else {
                canvas.drawCircle(cx, cy, radius, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.7f*d);
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