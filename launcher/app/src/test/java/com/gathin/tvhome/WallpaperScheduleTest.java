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

    @Test public void selectsRegularAndOvernightSlots() throws Exception {
        File dir = Files.createTempDirectory("schedule").toFile();
        Files.write(new File(dir, "night.jpg").toPath(), new byte[]{1});
        Files.write(new File(dir, "day.mp4").toPath(), new byte[]{1});
        WallpaperSchedule s = WallpaperSchedule.load(manifest(dir,
                "{\\"version\\":1,\\"slots\\":["
                + "{\\"start\\":\\"22:00\\",\\"end\\":\\"05:00\\",\\"file\\":\\"night.jpg\\"},"
                + "{\\"start\\":\\"05:00\\",\\"end\\":\\"22:00\\",\\"file\\":\\"day.mp4\\"}]}"));
        assertEquals("night.jpg", s.at(23 * 60).getName());
        assertEquals("night.jpg", s.at(2 * 60).getName());
        assertEquals("day.mp4", s.at(5 * 60).getName());
        assertNull(s.at(22 * 60));
    }

    @Test public void rejectsOverlappingSlotsAndMissingFiles() throws Exception {
        File dir = Files.createTempDirectory("schedule").toFile();
        Files.write(new File(dir, "a.jpg").toPath(), new byte[]{1});
        String overlap = "{\\"version\\":1,\\"slots\\":["
                + "{\\"start\\":\\"02:00\\",\\"end\\":\\"06:00\\",\\"file\\":\\"a.jpg\\"},"
                + "{\\"start\\":\\"05:00\\",\\"end\\":\\"08:00\\",\\"file\\":\\"a.jpg\\"}]}";
        assertTrue(WallpaperSchedule.load(manifest(dir, overlap)).isEmpty());
        String missing = "{\\"version\\":1,\\"slots\\":["
                + "{\\"start\\":\\"02:00\\",\\"end\\":\\"06:00\\",\\"file\\":\\"missing.jpg\\"}]}";
        assertTrue(WallpaperSchedule.load(manifest(dir, missing)).isEmpty());
    }
}
