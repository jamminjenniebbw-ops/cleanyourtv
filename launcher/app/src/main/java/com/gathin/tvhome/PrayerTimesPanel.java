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
    private final TextView status, next, nextName;
    private final TextView[] values = new TextView[5];
    private final View[] cards = new View[5];
    private final String[] names = {"Subuh", "Dzuhur", "Ashar", "Maghrib", "Isya"};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<String> todayTimes = new ArrayList<>();
    private LocalDate loadedDate;
    private boolean enabled;
    /**
     * Overlay in the same upper-right Home position as the original version.
     * It is deliberately not part of the scrolling app column.
     */
    static PrayerTimesPanel attach(Activity a, FrameLayout root, boolean dockGlass, int dockOpacity, boolean dockBand, boolean enabled) {
        PrayerTimesPanel p = new PrayerTimesPanel(a, dockGlass, dockOpacity, dockBand);
        p.enabled = enabled;
        float d = a.getResources().getDisplayMetrics().density;
        int screenWidth = a.getResources().getDisplayMetrics().widthPixels;
        int panelWidth = Math.min((int)(650*d), (int)(screenWidth * .54f));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(panelWidth, -2,
                Gravity.TOP | Gravity.END);
        lp.topMargin = (int)(142*d);
        // Align the panel's right edge with the header controls and dock's visual edge.
        lp.rightMargin = (int)(56*d);
        root.addView(p.panel, lp);
        p.panel.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (enabled) {
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
        if (enabled) panel.setVisibility(View.VISIBLE);
    }

    void onGridScrolled(int verticalOffsetPx) {
        // Scrolling must never override the user's master ON/OFF preference.
        if (!enabled) {
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
        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(8));
        // Match the dock band's appearance from the launcher's live theme/settings.
        GradientDrawable bg;
        if (!dockBand) {
            bg = new GradientDrawable();
            bg.setColor(Color.TRANSPARENT);
        } else if (dockGlass) {
            bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x24FFFFFF, 0x0DFFFFFF});
        } else {
            int alpha = Math.max(30, Math.min(100, dockOpacity));
            int color = (Math.round(alpha * 2.55f) << 24) | 0x00141416;
            bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{color, color});
        }
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), dockBand ? (dockGlass ? 0x38FFFFFF : 0x26FFFFFF) : Color.TRANSPARENT);
        panel.setBackground(bg);
        panel.setElevation(dp(5));

        LinearLayout header = new LinearLayout(a);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout identity = new LinearLayout(a);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView title = text("Jadwal Sholat", 15, Color.WHITE, true);
        identity.addView(title, wrap());
        TextView location = text("Bandung, Jawa Barat", 10, 0xEAF4FCFF, true);
        LinearLayout.LayoutParams locp = wrap();
        locp.topMargin = dp(5);
        identity.addView(location, locp);

        LinearLayout upcoming = new LinearLayout(a);
        upcoming.setOrientation(LinearLayout.VERTICAL);
        upcoming.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        upcoming.setPadding(dp(18), 0, 0, 0);
        header.addView(upcoming, wrap());

        nextName = text("Sholat Berikutnya", 9, 0xFFFFD98A, true);
        nextName.setGravity(Gravity.END);
        upcoming.addView(nextName, wrap());
        next = text("--:--:--", 16, 0xFFFFE3A6, true);
        next.setGravity(Gravity.END);
        LinearLayout.LayoutParams np = wrap();
        np.topMargin = dp(4);
        upcoming.addView(next, np);

        View divider = new View(a);
        divider.setBackgroundColor(0x557FA9C8);
        LinearLayout.LayoutParams dpv = new LinearLayout.LayoutParams(-1, dp(1));
        dpv.topMargin = dp(6);
        dpv.bottomMargin = dp(6);
        panel.addView(divider, dpv);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(row, new LinearLayout.LayoutParams(-1, -2));

        String[] labels = {"Subuh", "Dzuhur", "Ashar", "Maghrib", "Isya"};
        for (int i = 0; i < 5; i++) {
            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(4), dp(4), dp(4), dp(4));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) cp.leftMargin = dp(6);
            row.addView(card, cp);

            TextView label = text(labels[i], 10, 0xF0F7FFFF, true);
            label.setGravity(Gravity.CENTER);
            card.addView(label, new LinearLayout.LayoutParams(-1, -2));
            values[i] = text("--:--", 15, Color.WHITE, true);
            values[i].setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams vp = wrap();
            vp.topMargin = dp(2);
            card.addView(values[i], vp);
            cards[i] = card;
        }

        status = text("Memuat jadwal…", 8, 0xDCEAF3FC, true);
        LinearLayout.LayoutParams sp = wrap();
        sp.topMargin = dp(4);
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

    private int fetchAttempt = 0;

    private void load() {
        LocalDate d = LocalDate.now(ZONE);
        String cached = activity.getSharedPreferences("prayer_times_panel", 0)
                .getString("schedule_" + d.getYear(), "");
        if (!cached.isEmpty()) parse(cached, d);

        // Retry through the canonical host and its www alias. Some TV firmwares/networks
        // fail the first DNS/TLS connection even while the rest of the launcher is online.
        new Thread(() -> {
            String target = "https://sajadah.org/api/v1/times/id/kota-bandung/" + d.getYear() + ".json";
            String[] urls = {
                    target,
                    target.replace("https://sajadah.org", "https://www.sajadah.org"),
                    "https://api.allorigins.win/raw?url=" + android.net.Uri.encode(target),
                    "https://corsproxy.io/?url=" + android.net.Uri.encode(target)
            };
            Exception lastError = null;
            for (String endpoint : urls) {
                HttpURLConnection c = null;
                try {
                    URL url = new URL(endpoint);
                    c = (HttpURLConnection) url.openConnection();
                    c.setRequestMethod("GET");
                    c.setConnectTimeout(18000);
                    c.setReadTimeout(18000);
                    c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("Accept", "application/json");
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; CleanYourTV) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
                    c.setRequestProperty("Connection", "close");
                    int code = c.getResponseCode();
                    if (code != HttpURLConnection.HTTP_OK)
                        throw new java.io.IOException("HTTP " + code);
                    StringBuilder b = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) b.append(line);
                    }
                    String json = b.toString();
                    // Validate the response before replacing a previously good cache.
                    JSONObject check = new JSONObject(json);
                    if (!check.has("fields") || !check.has("days"))
                        throw new java.io.IOException("Format data jadwal tidak valid");
                    activity.getSharedPreferences("prayer_times_panel", 0).edit()
                            .putString("schedule_" + d.getYear(), json).apply();
                    activity.runOnUiThread(() -> {
                        fetchAttempt = 0;
                        parse(json, LocalDate.now(ZONE));
                    });
                    return;
                } catch (Exception e) {
                    lastError = e;
                } finally {
                    if (c != null) c.disconnect();
                }
            }

            final String error = lastError == null ? "koneksi gagal" :
                    (lastError.getMessage() == null ? lastError.getClass().getSimpleName() : lastError.getMessage());
            activity.runOnUiThread(() -> {
                if (todayTimes.isEmpty()) status.setText("Jadwal gagal dimuat • " + error);
                else status.setText("Jadwal tersimpan • menunggu koneksi");
                updateNext();
                // Retry periodically; do not leave the panel permanently blank after a temporary outage.
                fetchAttempt++;
                long delay = Math.min(15 * 60_000L, 30_000L * (1L << Math.min(fetchAttempt - 1, 4)));
                handler.removeCallbacks(retryFetch);
                handler.postDelayed(retryFetch, delay);
            });
        }, "PrayerTimesFetch").start();
    }

    private final Runnable retryFetch = this::load;

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
