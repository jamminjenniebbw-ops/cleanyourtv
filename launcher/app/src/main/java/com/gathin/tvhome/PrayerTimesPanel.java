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
import android.widget.FrameLayout;
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
    private final TextView status, next, nextName;
    private final TextView[] values = new TextView[5];
    private final View[] cards = new View[5];
    private final String[] names = {"Subuh", "Zuhur", "Asar", "Magrib", "Isya"};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<String> todayTimes = new ArrayList<>();
    private LocalDate loadedDate;    /**
     * Insert the panel into the Home column immediately above the app grid. This reserves real layout
     * space: it cannot cover app labels and it never moves when RecyclerView scrolls.
     */
    static PrayerTimesPanel attach(Activity a, LinearLayout homeColumn, View gridFrame) {
        PrayerTimesPanel p = new PrayerTimesPanel(a);
        float d = a.getResources().getDisplayMetrics().density;
        int screenWidth = a.getResources().getDisplayMetrics().widthPixels;
        int panelWidth = Math.min((int)(760*d), (int)(screenWidth * .70f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(panelWidth, -2);
        lp.gravity = Gravity.END;
        lp.rightMargin = (int)(8*d);
        lp.bottomMargin = (int)(8*d);
        int gridIndex = homeColumn.indexOfChild(gridFrame);
        if (gridIndex < 0) gridIndex = homeColumn.getChildCount();
        homeColumn.addView(p.panel, gridIndex, lp);
        p.load();
        p.handler.postDelayed(p.tick, 1_000L);
        return p;
    }

    /** Kept for compatibility with callers; the panel is now part of the vertical layout, not an overlay. */
    void bringToFront() {
        panel.bringToFront();
        panel.invalidate();
    }

    /** The panel is outside RecyclerView, so scrolling the app grid must never move it. */
    void onGridScrolled(int verticalOffsetPx) {
        panel.setTranslationY(0f);
        panel.setVisibility(View.VISIBLE);
    }



    private PrayerTimesPanel(Activity a) {
        activity = a;
        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(15), dp(11), dp(15), dp(11));
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xD92A4055, 0xD91B2B3E});
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), 0x557FA9C8);
        panel.setBackground(bg);
        panel.setElevation(dp(8));

        LinearLayout header = new LinearLayout(a);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout identity = new LinearLayout(a);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView title = text("Jadwal Sholat", 16, Color.WHITE, true);
        identity.addView(title, wrap());
        TextView location = text("Bandung, Jawa Barat", 10, 0xD6E4EDF5, false);
        LinearLayout.LayoutParams locp = wrap();
        locp.topMargin = dp(5);
        identity.addView(location, locp);

        LinearLayout upcoming = new LinearLayout(a);
        upcoming.setOrientation(LinearLayout.VERTICAL);
        upcoming.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        upcoming.setPadding(dp(18), 0, 0, 0);
        header.addView(upcoming, wrap());

        nextName = text("Sholat Berikutnya", 10, 0xFFFFD98A, true);
        nextName.setGravity(Gravity.END);
        upcoming.addView(nextName, wrap());
        next = text("--:--:--", 18, 0xFFFFE3A6, true);
        next.setGravity(Gravity.END);
        LinearLayout.LayoutParams np = wrap();
        np.topMargin = dp(4);
        upcoming.addView(next, np);

        View divider = new View(a);
        divider.setBackgroundColor(0x557FA9C8);
        LinearLayout.LayoutParams dpv = new LinearLayout.LayoutParams(-1, dp(1));
        dpv.topMargin = dp(8);
        dpv.bottomMargin = dp(8);
        panel.addView(divider, dpv);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(row, new LinearLayout.LayoutParams(-1, -2));

        String[] labels = {"Subuh", "Zuhur", "Asar", "Magrib", "Isya"};
        for (int i = 0; i < 5; i++) {
            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(5), dp(6), dp(5), dp(6));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) cp.leftMargin = dp(6);
            row.addView(card, cp);

            TextView label = text(labels[i], 10, 0xDCEAF3FC, true);
            label.setGravity(Gravity.CENTER);
            card.addView(label, new LinearLayout.LayoutParams(-1, -2));
            values[i] = text("--:--", 16, Color.WHITE, true);
            values[i].setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams vp = wrap();
            vp.topMargin = dp(3);
            card.addView(values[i], vp);
            cards[i] = card;
        }

        status = text("Memuat jadwal…", 8, 0xAFCBD9E7, false);
        LinearLayout.LayoutParams sp = wrap();
        sp.topMargin = dp(6);
        panel.addView(status, sp);

        panel.setFocusable(false);
        panel.setClickable(false);
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        updateCardStyles(-1);
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
                status.setText("Jadwal diperbarui • " + date.format(DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy", new Locale("id","ID"))));
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

    private void updateCardStyles(int nextIndex) {
        for (int i = 0; i < cards.length; i++) {
            boolean highlighted = i == nextIndex;
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setCornerRadius(dp(13));
            cardBg.setColor(highlighted ? 0xDD1677D2 : 0x263E5D78);
            cardBg.setStroke(dp(1), highlighted ? 0xFF75C5FF : 0x557FA9C8);
            cards[i].setBackground(cardBg);
        }
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
        updateCardStyles(nextIndex);
        if (nextAt == null) {
            nextName.setText("Sholat Berikutnya");
            next.setText("--:--:--");
            return;
        }
        long seconds = Math.max(0, Duration.between(now, nextAt).getSeconds());
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;
        nextName.setText("Sholat Berikutnya • " + names[nextIndex]);
        next.setText(String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, remainingSeconds));
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
