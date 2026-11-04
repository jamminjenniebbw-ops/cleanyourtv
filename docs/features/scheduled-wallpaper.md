# Scheduled wallpapers

## Product behaviour

CleanYourTV has two independent wallpaper modes:

- **Single wallpaper mode (default):** preserves the existing theme/collection selection and rotation behaviour.
- **Scheduled wallpaper mode:** when enabled in Android TV > Launcher Settings, the TV selects the wallpaper assigned to the current local-time interval.

The phone app remains the only place where wallpaper files and time slots are selected and edited. The TV settings screen exposes only the master ON/OFF switch. Turning the switch OFF immediately restores the existing single-wallpaper behaviour; it does not erase the schedule or its selected files.

## Schedule format

The phone app must transfer a schedule manifest alongside the selected wallpaper assets in the active theme's wallpaper directory. The TV reads it locally; no network or phone connection is needed after transfer.

Proposed manifest: `wallpaper_schedule.json`

```json
{
  "version": 1,
  "slots": [
    { "start": "02:00", "end": "05:00", "file": "background-1.jpg" },
    { "start": "05:00", "end": "10:00", "file": "background-2.gif" },
    { "start": "10:00", "end": "15:00", "file": "background-3.mp4" },
    { "start": "15:00", "end": "02:00", "file": "background-4.jpg" }
  ]
}
```

Intervals are start-inclusive and end-exclusive. An end earlier than a start means the slot crosses midnight. Slots must not overlap; gaps are permitted and fall back to the currently selected single wallpaper. Times use the Android TV's local 24-hour clock.

## TV implementation requirements

- Persist the master switch in launcher settings, default OFF, so existing installations retain today's behaviour.
- Keep the existing wallpaper renderer and its JPG/JPEG, GIF, and MP4 code paths. A schedule change only chooses the next file; it must not introduce another media renderer.
- Evaluate the active slot on Home startup/resume and after local-time/time-zone/date changes, and check at minute boundaries while Home is visible.
- On boot or Home resume, select the slot matching the current time immediately (do not replay missed slots).
- Preserve the existing single-wallpaper choice and rotation state while scheduled mode is enabled.
- If the manifest is missing/invalid, a slot references a missing/unsupported file, or no slot covers the current time, retain/fall back to the normal single-wallpaper selection without crashing or showing a blank screen.
- Keep schedule parsing and slot matching isolated and unit-testable. Do not alter prayer-time code or the existing TextureView/MediaPlayer watchdog implementation.

## Phone app integration requirements

The phone app's wallpaper picker must allow assigning an existing selected JPG/JPEG, GIF, or MP4 file to a start/end time slot, editing/removing slots, and previewing each selection. It must validate overlap and export the manifest plus referenced assets in the same theme transfer. The TV toggle is not duplicated in the phone app.

## Acceptance tests

1. Default installation and OFF mode behave exactly as before.
2. ON mode changes wallpaper at each configured boundary, including a slot crossing midnight.
3. Restarting TV during a slot immediately displays that slot.
4. Schedule works without internet and with the phone disconnected.
5. JPG/JPEG, GIF, and MP4 use the existing renderers; MP4 remains muted, looping, and watchdog-protected.
6. OFF restores the single selected wallpaper without deleting the schedule.
7. Invalid/missing manifest or media safely falls back to the existing wallpaper.
8. Prayer widget visibility, cached prayer times, and scrolling behaviour are unchanged.
