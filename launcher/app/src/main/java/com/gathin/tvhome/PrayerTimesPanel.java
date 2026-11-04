package com.gathin.tvhome;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
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
        // Migrate a previously downloaded schedule to keep existing offline installs usable.
        if (cached.isEmpty()) cached = prefs.getString("schedule_" + d.getYear(), "");
        if (!cached.isEmpty()) parse(cached, d);

        if (!hasValidatedInternet()) {
            if (todayTimes.isEmpty()) status.setText("Offline • menunggu jadwal tersimpan");
            else status.setText("Jadwal tersimpan • offline");
            return;
        }

        long now = System.currentTimeMillis();
        if (fetching || now - lastFetchStartedAt < 10_000L) return;
        long lastSuccess = activity.getSharedPreferences("prayer_times_panel", 0)
                .getLong("last_success_myquran_" + d.getYear(), 0L);
        if (!forceRefresh && lastSuccess > 0L && now - lastSuccess < 24L * 60L * 60L * 1000L) {
            if (!todayTimes.isEmpty()) status.setText("Jadwal tersimpan • diperbarui dari myQuran");
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
                    status.setText("Jadwal diperbarui • myQuran (data Kemenag)");
                });
            } catch (Exception e) {
                final String error = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")");
                activity.runOnUiThread(() -> {
                    fetching = false;
                    if (todayTimes.isEmpty()) status.setText("Jadwal gagal dimuat • " + error);
                    else status.setText("Jadwal tersimpan • pembaruan gagal");
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
        for (String field : new String[]{"date", "fajr", "dhuhr", "asr", "maghrib", "isha"}) fields.put(field);
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
                String[] keys = {"subuh", "dzuhur", "ashar", "maghrib", "isya"};
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
