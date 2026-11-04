package com.gathin.tvhome;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class WallpaperScheduleTest {
    private File manifest(File dir, String json) throws Exception {
        Files.write(new File(dir, WallpaperSchedule.FILE_NAME).toPath(), json.getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    private static String q() { return "\\\""; }

    @Test public void selectsRegularAndOvernightSlots() throws Exception {
        File dir = Files.createTempDirectory("schedule").toFile();
        Files.write(new File(dir, "night.jpg").toPath(), new byte[]{1});
        Files.write(new File(dir, "day.mp4").toPath(), new byte[]{1});
        String q = q();
        String json = "{" + q + "version" + q + ":1," + q + "slots" + q + ":["
                + "{" + q + "start" + q + ":" + q + "22:00" + q + "," + q + "end" + q + ":" + q + "05:00" + q + "," + q + "file" + q + ":" + q + "night.jpg" + q + "},"
                + "{" + q + "start" + q + ":" + q + "05:00" + q + "," + q + "end" + q + ":" + q + "22:00" + q + "," + q + "file" + q + ":" + q + "day.mp4" + q + "}]}";
        WallpaperSchedule s = WallpaperSchedule.load(manifest(dir, json));
        assertEquals("night.jpg", s.at(23 * 60).getName());
        assertEquals("night.jpg", s.at(2 * 60).getName());
        assertEquals("day.mp4", s.at(5 * 60).getName());
        assertEquals("night.jpg", s.at(22 * 60).getName());
    }

    @Test public void rejectsOverlappingSlotsAndMissingFiles() throws Exception {
        File dir = Files.createTempDirectory("schedule").toFile();
        Files.write(new File(dir, "a.jpg").toPath(), new byte[]{1});
        String q = q();
        String overlap = "{" + q + "version" + q + ":1," + q + "slots" + q + ":["
                + "{" + q + "start" + q + ":" + q + "02:00" + q + "," + q + "end" + q + ":" + q + "06:00" + q + "," + q + "file" + q + ":" + q + "a.jpg" + q + "},"
                + "{" + q + "start" + q + ":" + q + "05:00" + q + "," + q + "end" + q + ":" + q + "08:00" + q + "," + q + "file" + q + ":" + q + "a.jpg" + q + "}]}";
        assertTrue(WallpaperSchedule.load(manifest(dir, overlap)).isEmpty());
        String missing = "{" + q + "version" + q + ":1," + q + "slots" + q + ":["
                + "{" + q + "start" + q + ":" + q + "02:00" + q + "," + q + "end" + q + ":" + q + "06:00" + q + "," + q + "file" + q + ":" + q + "missing.jpg" + q + "}]}";
        assertTrue(WallpaperSchedule.load(manifest(dir, missing)).isEmpty());
    }
}
