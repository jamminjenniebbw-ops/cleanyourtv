package com.gathin.tvhome;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.graphics.Color;
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
    private final TextView status, next, nextName;
    private final TextView[] values = new TextView[8];
    private final View[] cards = new View[8];
    private final String[] names = {"Imsak", "Subuh", "Terbit", "Dhuha", "Dzuhur", "Ashar", "Maghrib", "Isya"};
    private final TextView dateDay, dateNumber, dateMonth, hijriDate, nextPrayerTitle, nextPrayerTime;
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
        android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
        int screenWidth = dm.widthPixels;
        int screenHeight = dm.heightPixels;
        // Keep the panel in the free vertical band: below the clock/date and above the Home dock.
        int panelWidth = Math.min((int)(330f * dm.density), (int)(screenWidth * .32f));
        int panelHeight = (int)(screenHeight * .68f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(panelWidth, panelHeight,
                Gravity.TOP | ("left".equals(position) ? Gravity.START : Gravity.END));
        lp.topMargin = (int)(screenHeight * .18f);
        if ("left".equals(position)) lp.leftMargin = (int)(screenWidth * .035f);
        else lp.rightMargin = (int)(screenWidth * .035f);
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
        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(9), dp(7), dp(9), dp(7));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xD9273447, 0xD51A2940, 0xE61B3556});
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), 0x80FFFFFF);
        panel.setBackground(bg);
        panel.setElevation(dp(10));

        LinearLayout header = new LinearLayout(a);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(34)));
        TextView mosque = text("☾", 23, Color.WHITE, true);
        mosque.setGravity(Gravity.CENTER);
        header.addView(mosque, new LinearLayout.LayoutParams(dp(34), -1));
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

        // Reference composition: prayer timetable on the LEFT, date/countdown/next prayer on the RIGHT.
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.TOP);
        panel.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listP = new LinearLayout.LayoutParams(0, -1, 1.04f);
        listP.setMargins(0, dp(1), dp(6), 0);
        content.addView(list, listP);
        String[] icons = {"☼", "☀", "☼", "☀", "☀", "☁", "☼", "☾"};
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
            TextView icon = text(icons[i], 15, Color.WHITE, true);
            icon.setGravity(Gravity.CENTER);
            item.addView(icon, new LinearLayout.LayoutParams(dp(23), -1));
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
        rightP.setMargins(dp(3), 0, 0, 0);
        content.addView(right, rightP);

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
        date.addView(hijriDate, wrap());

        LinearLayout countdown = glassCard();
        countdown.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams countP = new LinearLayout.LayoutParams(-1, 0, .88f);
        countP.topMargin = dp(6);
        right.addView(countdown, countP);
        countdown.addView(text("Sholat berikutnya", 10, 0xFFE8EAF0, true), wrap());
        next = text("--:--:--", 20, 0xFFFFD21F, true);
        LinearLayout.LayoutParams nextP = wrap();
        nextP.topMargin = dp(3);
        countdown.addView(next, nextP);
        nextName = text("", 1, Color.TRANSPARENT, false);

        LinearLayout nextCard = new LinearLayout(a);
        nextCard.setOrientation(LinearLayout.HORIZONTAL);
        nextCard.setGravity(Gravity.CENTER_VERTICAL);
        nextCard.setPadding(dp(5), dp(4), dp(5), dp(4));
        GradientDrawable gold = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFFFE88A, 0xFFFFC52E, 0xFFB98508});
        gold.setCornerRadius(dp(14));
        gold.setStroke(dp(1), 0xFFFFF0B0);
        nextCard.setBackground(gold);
        nextCard.setElevation(dp(6));
        LinearLayout.LayoutParams nextCardP = new LinearLayout.LayoutParams(-1, 0, .78f);
        nextCardP.topMargin = dp(6);
        right.addView(nextCard, nextCardP);
        TextView nextIcon = text("☾", 19, Color.WHITE, true);
        nextIcon.setGravity(Gravity.CENTER);
        nextCard.addView(nextIcon, new LinearLayout.LayoutParams(dp(23), -1));
        View goldLine = divider();
        LinearLayout.LayoutParams goldLineP = new LinearLayout.LayoutParams(dp(1), dp(30));
        goldLineP.setMargins(dp(3), 0, dp(4), 0);
        nextCard.addView(goldLine, goldLineP);
        LinearLayout nextLabels = new LinearLayout(a);
        nextLabels.setOrientation(LinearLayout.VERTICAL);
        nextLabels.setGravity(Gravity.CENTER_VERTICAL);
        nextCard.addView(nextLabels, new LinearLayout.LayoutParams(0, -1, 1f));
        nextPrayerTitle = text("Subuh", 12, Color.WHITE, true);
        nextLabels.addView(nextPrayerTitle, wrap());
        nextPrayerTime = text("--:--", 11, 0xFFF9F0D2, true);
        nextLabels.addView(nextPrayerTime, wrap());
        nextCard.addView(text("›", 20, Color.WHITE, true), wrap());

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
        for (int i : new int[]{1, 4, 5, 6, 7}) {
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
                String[] hm = todayTimes.get(1).split(":");
                nextIndex = 1;
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
        nextName.setText("Sholat Berikutnya");
        nextPrayerTitle.setText(names[nextIndex]);
        nextPrayerTime.setText(todayTimes.get(nextIndex));
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
