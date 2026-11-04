package com.gathin.tvhome;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict, local-only parser for the phone app's wallpaper_schedule.json manifest. */
final class WallpaperSchedule {
    static final String FILE_NAME = "wallpaper_schedule.json";
    private static final int MAX_BYTES = 64 * 1024;
    private static final Pattern SLOT = Pattern.compile(
            "\\{\\s*\\"start\\"\\s*:\\s*\\"([0-9]{2}:[0-9]{2})\\"\\s*,"
          + "\\s*\\"end\\"\\s*:\\s*\\"([0-9]{2}:[0-9]{2})\\"\\s*,"
          + "\\s*\\"file\\"\\s*:\\s*\\"([A-Za-z0-9_.-]+)\\"\\s*\\}");
    private static final Pattern NAME = Pattern.compile(
            "[A-Za-z0-9_.-]+\\.(jpg|jpeg|png|webp|gif|mp4)", Pattern.CASE_INSENSITIVE);

    static final class Slot {
        final int startMinute;
        final int endMinute;
        final File file;

        Slot(int startMinute, int endMinute, File file) {
            this.startMinute = startMinute;
            this.endMinute = endMinute;
            this.file = file;
        }

        boolean contains(int minute) {
            return startMinute < endMinute
                    ? minute >= startMinute && minute < endMinute
                    : minute >= startMinute || minute < endMinute;
        }
    }

    private final List<Slot> slots;

    private WallpaperSchedule(List<Slot> slots) {
        this.slots = slots;
    }

    static WallpaperSchedule empty() {
        return new WallpaperSchedule(new ArrayList<>());
    }

    static WallpaperSchedule load(File directory) {
        File manifest = directory == null ? null : new File(directory, FILE_NAME);
        if (manifest == null || !manifest.isFile() || manifest.length() <= 0
                || manifest.length() > MAX_BYTES) return empty();

        try {
            byte[] bytes = new byte[(int) manifest.length()];
            try (FileInputStream input = new FileInputStream(manifest)) {
                int offset = 0;
                while (offset < bytes.length) {
                    int count = input.read(bytes, offset, bytes.length - offset);
                    if (count < 0) return empty();
                    offset += count;
                }
            }
            String json = new String(bytes, StandardCharsets.UTF_8);
            if (!json.matches("(?s).*\\"version\\"\\s*:\\s*1\\b.*")
                    || !json.contains("\\"slots\\"")) return empty();

            Matcher matcher = SLOT.matcher(json);
            List<Slot> parsed = new ArrayList<>();
            boolean[] occupied = new boolean[1440];
            int count = 0;
            while (matcher.find()) {
                count++;
                int start = minute(matcher.group(1));
                int end = minute(matcher.group(2));
                String name = matcher.group(3);
                if (start < 0 || end < 0 || start == end || !NAME.matcher(name).matches()) return empty();
                File file = new File(directory, name);
                if (!file.isFile() || file.length() <= 0) return empty();
                int cursor = start;
                while (cursor != end) {
                    if (occupied[cursor]) return empty();
                    occupied[cursor] = true;
                    cursor = (cursor + 1) % 1440;
                }
                parsed.add(new Slot(start, end, file));
            }
            // Reject malformed slot objects instead of silently accepting a partial schedule.
            String slotsPart = json.substring(json.indexOf("\\"slots\\""));
            int objectCount = 0;
            Matcher objects = Pattern.compile("\\{").matcher(slotsPart);
            while (objects.find()) objectCount++;
            if (count == 0 || objectCount != count) return empty();
            return new WallpaperSchedule(parsed);
        } catch (Exception ignored) {
            return empty();
        }
    }

    File at(int minuteOfDay) {
        int minute = Math.max(0, Math.min(1439, minuteOfDay));
        for (Slot slot : slots) if (slot.contains(minute)) return slot.file;
        return null;
    }

    boolean isEmpty() {
        return slots.isEmpty();
    }

    private static int minute(String value) {
        if (value == null || !value.matches("[0-9]{2}:[0-9]{2}")) return -1;
        int hour = Integer.parseInt(value.substring(0, 2));
        int minute = Integer.parseInt(value.substring(3, 5));
        return hour < 24 && minute < 60 ? hour * 60 + minute : -1;
    }
}
