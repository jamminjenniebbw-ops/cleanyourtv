package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
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
    private final TextView status, next;
    private final TextView[] values = new TextView[5];
    private final View[] cards = new View[5];
    private int baseTopMarginPx;
    private final String[] names = {"Subuh", "Zuhur", "Asar", "Magrib", "Isya"};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<String> todayTimes = new ArrayList<>();
    private LocalDate loadedDate;

    static PrayerTimesPanel attach(Activity a, FrameLayout root) {
        PrayerTimesPanel p = new PrayerTimesPanel(a);
        float d = a.getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        p.baseTopMarginPx = (int)(142*d);
        lp.topMargin = p.baseTopMarginPx;
        lp.setMarginEnd((int)(48*d));
        root.addView(p.panel, lp);
        p.load();
        p.handler.postDelayed(p.tick, 1_000L);
        return p;
    }

    /** Keep this panel above the wallpaper and other root-level launcher layers. */
    void bringToFront() {
        panel.bringToFront();
        panel.invalidate();
    }

    /** Move with the scrolling app grid instead of behaving like a floating overlay. */
    void onGridScrolled(int verticalOffsetPx) {
        panel.setTranslationY(-Math.max(0, verticalOffsetPx));
        int height = panel.getHeight();
        if (height > 0 && baseTopMarginPx - verticalOffsetPx + height <= 0) {
            panel.setVisibility(View.GONE);
        } else {
            panel.setVisibility(View.VISIBLE);
        }
    }

    private PrayerTimesPanel(Activity a) {
        activity = a;
        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB8202020);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0x24FFFFFF);
        panel.setBackground(bg);

        TextView title = text("JADWAL SALAT  •  BANDUNG", 11, 0xB3FFFFFF, true);
        title.setLetterSpacing(.12f);
        panel.addView(title, wrap());

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = wrap();
        rp.topMargin = dp(8);
        panel.addView(row, rp);
        String[] labels = {"SUBUH", "ZUHUR", "ASAR", "MAGRIB", "ISYA"};
        for (int i=0;i<5;i++) {
            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(8), dp(4), dp(8), dp(4));
            TextView label = text(labels[i], 10, 0xB3FFFFFF, true);
            label.setGravity(Gravity.CENTER);
            values[i] = text("--:--", 17, Color.WHITE, false);
            values[i].setGravity(Gravity.CENTER);
            card.addView(label, wrap());
            LinearLayout.LayoutParams vp = wrap();
            vp.topMargin = dp(3);
            card.addView(values[i], vp);
            row.addView(card, wrap());
            cards[i] = card;
        }
        status = text("Mengambil jadwal daring…", 10, 0xB3FFFFFF, false);
        LinearLayout.LayoutParams sp = wrap(); sp.topMargin = dp(6);
        panel.addView(status, sp);
        next = text("Salat berikutnya: --", 12, Color.WHITE, true);
        LinearLayout.LayoutParams np = wrap(); np.topMargin = dp(4);
        panel.addView(next, np);
        panel.setFocusable(false);
        panel.setClickable(false);
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            LocalDate d = LocalDate.now(ZONE);
            if (loadedDate == null || !d.equals(loadedDate)) load();
            else updateNext();
            handler.postDelayed(this, 1_000L);
        }
    };

    private void load() {
        LocalDate d = LocalDate.now(ZONE);
        String cached = activity.getSharedPreferences("prayer_times_panel", 0).getString("schedule_" + d.getYear(), "");
        if (!cached.isEmpty()) parse(cached, d);
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection)new URL("https://sajadah.org/api/v1/times/id/kota-bandung/" + d.getYear() + ".json").openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/json");
                StringBuilder b = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = r.readLine()) != null) b.append(line);
                }
                String json = b.toString();
                activity.getSharedPreferences("prayer_times_panel", 0).edit().putString("schedule_" + d.getYear(), json).apply();
                activity.runOnUiThread(() -> parse(json, LocalDate.now(ZONE)));
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    if (todayTimes.isEmpty()) status.setText("Jadwal offline belum tersimpan");
                    updateNext();
                });
            } finally { if (c != null) c.disconnect(); }
        }, "PrayerTimesFetch").start();
    }

    private void parse(String json, LocalDate date) {
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray fields = obj.getJSONArray("fields"), days = obj.getJSONArray("days");
            int dateCol = field(fields, "date");
            int[] cols = {field(fields,"fajr"), field(fields,"dhuhr"), field(fields,"asr"), field(fields,"maghrib"), field(fields,"isha")};
            for (int i=0;i<days.length();i++) {
                JSONArray day = days.optJSONArray(i);
                if (day == null || dateCol < 0 || !date.toString().equals(day.optString(dateCol))) continue;
                todayTimes.clear();
                for (int p=0;p<5;p++) {
                    String t = cols[p] >= 0 ? day.optString(cols[p], "--:--") : "--:--";
                    todayTimes.add(t); values[p].setText(t);
                }
                loadedDate = date;
                status.setText("Jadwal salat • " + date.format(DateTimeFormatter.ofPattern("dd MMM yyyy", new Locale("id","ID"))));
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

    private void updateNext() {
        if (todayTimes.isEmpty()) return;
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        int nextIndex = -1;
        ZonedDateTime nextAt = null;
        for (int i = 0; i < todayTimes.size(); i++) {
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

        for (int i = 0; i < cards.length; i++) {
            boolean highlighted = i == nextIndex;
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setCornerRadius(dp(10));
            cardBg.setColor(highlighted ? 0xCC287A58 : 0x14FFFFFF);
            if (highlighted) cardBg.setStroke(dp(1), 0xCC78E0B0);
            cards[i].setBackground(cardBg);
        }

        if (nextAt == null) {
            next.setText("Salat berikutnya: --");
            return;
        }
        long seconds = Math.max(0, Duration.between(now, nextAt).getSeconds());
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;
        String countdown = String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, remainingSeconds);
        next.setText("Berikutnya: " + names[nextIndex] + " • " + countdown);
    }

    private TextView text(String s, int size, int color, boolean medium) {
        TextView t = new TextView(activity); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setIncludeFontPadding(false); t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif-light", Typeface.NORMAL));
        return t;
    }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-2,-2); }
    private int dp(float v) { return (int)(v*activity.getResources().getDisplayMetrics().density + .5f); }
}