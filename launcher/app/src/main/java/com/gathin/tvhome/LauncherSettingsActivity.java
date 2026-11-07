package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private TextView wallpaperChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView openOnStartChoice;
    private TextView prayerTimesChoice;
    private TextView tvStorageWallpaperChoice;
    private static final int REQUEST_TV_WALLPAPER = 7401;
    private static final int REQUEST_MEDIA_PERMISSION = 7402;
    private boolean showPrayerTimes;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        addGreetingSettings();
        View reorderChoice = findViewById(R.id.reorderChoice);
        reorderChoice.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorderChoice.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] dockIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < dockIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            dockChoices[i] = findViewById(dockIds[i]);
            dockChoices[i].setOnClickListener(v -> {
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                changed();
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
            reverseRow(dockChoices);
        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showUnifiedWallpaperPicker());
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        prayerTimesChoice = findViewById(R.id.prayerTimesChoice);
        showPrayerTimes = choices.getBoolean("show_prayer_times", true);
        prayerTimesChoice.setOnClickListener(v -> {
            showPrayerTimes = !showPrayerTimes;
            choices.edit().putBoolean("show_prayer_times", showPrayerTimes).apply();
            changed();
            refresh();
        });
        tvStorageWallpaperChoice = findViewById(R.id.tvStorageWallpaperChoice);
        tvStorageWallpaperChoice.setOnClickListener(v -> chooseWallpaperFromTv());
        // Remove the unfinished scheduled-wallpaper preference left by older builds.
        choices.edit().remove("scheduled_wallpaper_enabled").apply();
        openOnStartChoice = findViewById(R.id.openOnStartChoice);
        if (config.openOnStartChoice) {
            openOnStartChoice.setVisibility(View.VISIBLE);
            openOnStartChoice.setOnClickListener(v -> showOpenOnStartPicker());
        }
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY).remove("builtin_wallpaper_choice")
                    .remove("tv_storage_wallpaper_choice")
                    .remove(Config.DOCK_OPACITY_KEY).remove(OpenOnStart.KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = current.dockOpacity;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            changed();
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        refresh();
        // Accept a wallpaper shared from a TV file manager such as FX File Explorer.
        if (Intent.ACTION_SEND.equals(getIntent().getAction())) {
            Uri shared = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            if (shared == null && getIntent().getClipData() != null
                    && getIntent().getClipData().getItemCount() > 0) {
                shared = getIntent().getClipData().getItemAt(0).getUri();
            }
            if (shared != null) importTvWallpaper(shared);
            else Toast.makeText(this, R.string.wallpaper_import_failed, Toast.LENGTH_LONG).show();
        }
        if (config.launcherSettingsButton) reorderChoice.requestFocus();
        else columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }


    private TextView greetingSettingsRow;

    /** Three independently configurable home greeting modes, stored locally on the TV. */
    private void addGreetingSettings() {
        android.widget.ScrollView scroll = (android.widget.ScrollView) ((ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
        if (!(scroll.getChildAt(0) instanceof LinearLayout)) return;
        LinearLayout content = (LinearLayout) scroll.getChildAt(0);
        greetingSettingsRow = new TextView(this);
        TextView row = greetingSettingsRow;
        updateGreetingSettingsRow();
        row.setTextColor(Color.WHITE);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), 0, dp(20), 0);
        row.setMinHeight(dp(58));
        row.setBackgroundResource(R.drawable.settings_choice_bg);
        row.setFocusable(true);
        row.setOnClickListener(v -> chooseGreetingMode());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        content.addView(row, 1, lp);
    }

    private void updateGreetingSettingsRow() {
        if (greetingSettingsRow == null) return;
        int mode = choices.getInt("home_greeting_mode", 0);
        String[] labels = {"Ucapan waktu", "Ucapan waktu + teks kustom", "Teks kustom saja"};
        int safeMode = Math.max(0, Math.min(2, mode));
        greetingSettingsRow.setText("Sapaan homescreen  ·  " + labels[safeMode] + "   ✓");
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void chooseGreetingMode() {
        String[] modes = {"1. Ucapan waktu", "2. Ucapan waktu + teks kustom", "3. Teks kustom saja"};
        int selected = choices.getInt("home_greeting_mode", 0);
        new AlertDialog.Builder(this)
                .setTitle("Tampilan sapaan")
                .setSingleChoiceItems(modes, selected, (dialog, which) -> {
                    choices.edit().putInt("home_greeting_mode", which).apply();
                    updateGreetingSettingsRow();
                    changed();
                    dialog.dismiss();
                    if (which == 1) editGreetingText("home_greeting_custom_time", "Teks kustom untuk mode 2");
                    else if (which == 2) editGreetingText("home_greeting_custom_only", "Teks kustom untuk mode 3");
                    else Toast.makeText(this, "Mode ucapan waktu aktif", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void editGreetingText(String key, String title) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(choices.getString(key, ""));
        input.setSelection(input.getText().length());
        input.setHint("Contoh: ASNA");
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("Simpan", (dialog, which) -> {
                    choices.edit().putString(key, input.getText().toString().trim()).apply();
                    changed();
                    Toast.makeText(this, "Teks sapaan disimpan", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }


    private void chooseWallpaperFromTv() {
        // Use our own TV-focused browser instead of handing off to the phone-oriented
        // system photo picker. This guarantees visible D-pad focus and a clear preview.
        showIndexedMediaPicker();
    }

    /**
     * Some Android TV firmware has no DocumentsUI/file-picker app. Fall back to Android's
     * indexed media collections so users can still choose local photos and MP4s with no
     * additional app or network connection.
     */
    private void showIndexedMediaPicker() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            boolean imagesGranted = checkSelfPermission("android.permission.READ_MEDIA_IMAGES") == PackageManager.PERMISSION_GRANTED;
            boolean videosGranted = checkSelfPermission("android.permission.READ_MEDIA_VIDEO") == PackageManager.PERMISSION_GRANTED;
            boolean selectedGranted = checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED;
            if ((!imagesGranted || !videosGranted) && !selectedGranted) {
                requestPermissions(new String[]{"android.permission.READ_MEDIA_IMAGES",
                        "android.permission.READ_MEDIA_VIDEO",
                        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"}, REQUEST_MEDIA_PERMISSION);
                return;
            }
        } else if (android.os.Build.VERSION.SDK_INT >= 33) {
            boolean imagesGranted = checkSelfPermission("android.permission.READ_MEDIA_IMAGES") == PackageManager.PERMISSION_GRANTED;
            boolean videosGranted = checkSelfPermission("android.permission.READ_MEDIA_VIDEO") == PackageManager.PERMISSION_GRANTED;
            if (!imagesGranted || !videosGranted) {
                requestPermissions(new String[]{"android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO"}, REQUEST_MEDIA_PERMISSION);
                return;
            }
        } else if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.READ_EXTERNAL_STORAGE"}, REQUEST_MEDIA_PERMISSION);
            return;
        }
        final ArrayList<Uri> uris = new ArrayList<>();
        final ArrayList<String> labels = new ArrayList<>();
        queryWallpaperMedia(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, uris, labels);
        queryWallpaperMedia(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, uris, labels);
        if (labels.isEmpty()) {
            Toast.makeText(this, R.string.wallpaper_picker_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        GridView grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setHorizontalSpacing(dp(8));
        grid.setVerticalSpacing(dp(10));
        grid.setPadding(dp(12), dp(12), dp(12), dp(12));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(android.R.color.transparent);
        grid.setChoiceMode(GridView.CHOICE_MODE_NONE);
        // Keep focus on GridView itself: focusable child cards can steal D-pad events
        // inconsistently across Android TV vendors. GridView then owns selection and OK.
        grid.setFocusable(true);
        grid.setFocusableInTouchMode(false);
        WallpaperMediaAdapter mediaAdapter = new WallpaperMediaAdapter(uris, labels);
        grid.setAdapter(mediaAdapter);
        grid.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                    int position, long id) {
                mediaAdapter.setSelectedPosition(position);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {
                mediaAdapter.setSelectedPosition(-1);
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_from_tv)
                .setView(grid)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        grid.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            importTvWallpaper(uris.get(position));
        });
        dialog.setOnShowListener(ignored -> grid.post(() -> {
            grid.requestFocus();
            grid.setSelection(0);
            mediaAdapter.setSelectedPosition(0);
        }));
        dialog.show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Thumbnail grid with an unmistakable focus frame for Android TV remotes. */
    private final class WallpaperMediaAdapter extends BaseAdapter {
        private final ArrayList<Uri> items;
        private final ArrayList<String> names;
        private int selectedPosition = -1;
        WallpaperMediaAdapter(ArrayList<Uri> items, ArrayList<String> names) {
            this.items = items;
            this.names = names;
        }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        void setSelectedPosition(int position) {
            if (selectedPosition == position) return;
            selectedPosition = position;
            notifyDataSetChanged();
        }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout cell;
            ImageView preview;
            TextView caption;
            if (recycled instanceof LinearLayout) {
                cell = (LinearLayout) recycled;
                preview = (ImageView) cell.getChildAt(0);
                caption = (TextView) cell.getChildAt(1);
            } else {
                cell = new LinearLayout(LauncherSettingsActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(android.view.Gravity.CENTER);
                // Cards are deliberately not focusable. The GridView owns D-pad focus,
                // so directional movement and DPAD_CENTER behave consistently on TV firmware.
                cell.setFocusable(false);
                cell.setFocusableInTouchMode(false);
                cell.setPadding(dp(5), dp(5), dp(5), dp(5));
                GradientDrawable normal = new GradientDrawable();
                normal.setColor(Color.rgb(35, 42, 70));
                normal.setCornerRadius(dp(7));
                normal.setStroke(dp(1), Color.rgb(90, 100, 130));
                GradientDrawable selected = new GradientDrawable();
                selected.setColor(Color.rgb(65, 105, 190));
                selected.setCornerRadius(dp(7));
                selected.setStroke(dp(3), Color.WHITE);
                StateListDrawable states = new StateListDrawable();
                states.addState(new int[]{android.R.attr.state_selected}, selected);
                states.addState(new int[]{android.R.attr.state_focused}, selected);
                states.addState(new int[]{}, normal);
                cell.setBackground(states);
                preview = new ImageView(LauncherSettingsActivity.this);
                preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cell.addView(preview, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(86)));
                caption = new TextView(LauncherSettingsActivity.this);
                caption.setTextColor(Color.WHITE);
                caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                caption.setSingleLine(true);
                caption.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                caption.setGravity(android.view.Gravity.CENTER);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(27));
                cell.addView(caption, cp);

            }
            Uri uri = items.get(position);
            try {
                Bitmap bitmap;
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    bitmap = getContentResolver().loadThumbnail(uri,
                            new android.util.Size(320, 200), null);
                } else {
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        bitmap = BitmapFactory.decodeStream(in);
                    }
                }
                preview.setImageBitmap(bitmap);
            } catch (Exception e) {
                preview.setImageResource(android.R.drawable.ic_menu_gallery);
            }
            caption.setText(names.get(position));
            cell.setSelected(position == selectedPosition);
            return cell;
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MEDIA_PERMISSION) {
            boolean granted = grantResults.length > 0;
            for (int result : grantResults) granted &= result == PackageManager.PERMISSION_GRANTED;
            if (granted) showIndexedMediaPicker();
            else Toast.makeText(this, R.string.wallpaper_picker_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void queryWallpaperMedia(Uri collection, boolean video, ArrayList<Uri> uris, ArrayList<String> labels) {
        String[] projection = video
                ? new String[]{android.provider.MediaStore.Video.Media._ID, android.provider.MediaStore.Video.Media.DISPLAY_NAME}
                : new String[]{android.provider.MediaStore.Images.Media._ID, android.provider.MediaStore.Images.Media.DISPLAY_NAME, android.provider.MediaStore.Images.Media.MIME_TYPE};
        String selection = video ? null : android.provider.MediaStore.Images.Media.MIME_TYPE + " IN (?,?,?)";
        String[] args = video ? null : new String[]{"image/jpeg", "image/png", "image/gif"};
        String sort = video ? android.provider.MediaStore.Video.Media.DATE_ADDED + " DESC"
                : android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC";
        try (android.database.Cursor cursor = getContentResolver().query(collection, projection, selection, args, sort)) {
            if (cursor == null) return;
            int idColumn = cursor.getColumnIndexOrThrow(video ? android.provider.MediaStore.Video.Media._ID : android.provider.MediaStore.Images.Media._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(video ? android.provider.MediaStore.Video.Media.DISPLAY_NAME : android.provider.MediaStore.Images.Media.DISPLAY_NAME);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                Uri item = android.content.ContentUris.withAppendedId(collection, id);
                String name = cursor.getString(nameColumn);
                uris.add(item);
                labels.add((video ? "🎬 " : "🖼 ") + (name == null ? item.getLastPathSegment() : name));
            }
        } catch (Exception ignored) { }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TV_WALLPAPER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        importTvWallpaper(data.getData());
    }

    private void importTvWallpaper(Uri uri) {
        String mime = getContentResolver().getType(uri);
        String ext = wallpaperExtension(uri, mime);
        if (ext == null) {
            Toast.makeText(this, R.string.wallpaper_unsupported, Toast.LENGTH_LONG).show();
            return;
        }
        // Store TV-picked media in app-private storage. Theme directories may contain
        // rotating wallpaper collections that otherwise override a newly imported file.
        File dir = getFilesDir();
        File temp = new File(dir, "tv_wallpaper.importing");
        File target = new File(dir, "tv_wallpaper." + ext);
        File backup = new File(dir, "tv_wallpaper.previous");
        try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) throw new java.io.IOException("No stream");
            byte[] buffer = new byte[32768]; int n; long total = 0;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > 100L * 1024 * 1024) throw new java.io.IOException("Too large");
                out.write(buffer, 0, n);
            }
            out.getFD().sync();
            if (total == 0) throw new java.io.IOException("Empty wallpaper");
            if (backup.exists() && !backup.delete()) throw new java.io.IOException("Cannot clear backup");
            if (target.exists() && !target.renameTo(backup)) throw new java.io.IOException("Cannot stage old wallpaper");
            if (!temp.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target);
                throw new java.io.IOException("Save failed");
            }
            backup.delete();
            for (String other : new String[]{"jpg", "jpeg", "png", "gif", "mp4"}) {
                File f = new File(dir, "tv_wallpaper." + other);
                if (!f.equals(target) && f.exists()) f.delete();
            }
            choices.edit().remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove("builtin_wallpaper_choice")
                    .putString("tv_storage_wallpaper_choice", ext).apply();
            changed();
            Toast.makeText(this, R.string.wallpaper_imported, Toast.LENGTH_LONG).show();
            // Leave settings so the home activity resumes and reloads the new wallpaper.
            setResult(RESULT_OK);
            finish();
        } catch (Exception e) {
            temp.delete();
            if (backup.exists() && !target.exists()) backup.renameTo(target);
            Toast.makeText(this, R.string.wallpaper_import_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** Some TV document providers report application/octet-stream; use the visible filename as fallback. */
    private String wallpaperExtension(Uri uri, String mime) {
        if ("video/mp4".equalsIgnoreCase(mime)) return "mp4";
        if ("image/gif".equalsIgnoreCase(mime)) return "gif";
        if ("image/png".equalsIgnoreCase(mime)) return "png";
        if ("image/jpeg".equalsIgnoreCase(mime) || "image/jpg".equalsIgnoreCase(mime)) return "jpg";
        String name = null;
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception ignored) { }
        if (name == null) return null;
        name = name.toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".mp4")) return "mp4";
        if (name.endsWith(".gif")) return "gif";
        if (name.endsWith(".png")) return "png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "jpg";
        return null;
    }

    private static final String[] BUILTIN_WALLPAPERS = {
            "IMG_20261107_021316.jpg", "IMG_20261107_021332.jpg",
            "IMG_20261107_021438.jpg", "IMG_20261107_021503.jpg",
            "IMG_20261107_021518.jpg", "IMG_20261107_021534.jpg",
            "IMG_20261107_021553.jpg", "IMG_20261107_021613.jpg",
            "IMG_20261107_021638.jpg", "IMG_20261107_021706.jpg"
    };

    private void showUnifiedWallpaperPicker() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setItems(new CharSequence[]{
                        getString(R.string.builtin_wallpaper_title),
                        getString(R.string.wallpaper_theme_default),
                        getString(R.string.wallpaper_from_theme_collection)
                }, (dialog, which) -> {
                    if (which == 0) showBuiltinWallpaperPicker();
                    else if (which == 1) {
                        choices.edit().remove(Config.WALLPAPER_CHOICE_KEY)
                                .remove("builtin_wallpaper_choice")
                                .remove("tv_storage_wallpaper_choice").apply();
                        changed();
                        refresh();
                        Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    } else showWallpaperPicker();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showBuiltinWallpaperPicker() {
        GridView grid = new GridView(this);
        grid.setNumColumns(2);
        grid.setHorizontalSpacing(dp(12));
        grid.setVerticalSpacing(dp(12));
        grid.setPadding(dp(12), dp(12), dp(12), dp(12));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(android.R.color.transparent);
        grid.setFocusable(true);
        grid.setFocusableInTouchMode(false);
        final int selected = java.util.Arrays.asList(BUILTIN_WALLPAPERS)
                .indexOf(choices.getString("builtin_wallpaper_choice", null));
        final int[] focusedPosition = {Math.max(0, selected)};
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return BUILTIN_WALLPAPERS.length; }
            @Override public Object getItem(int position) { return BUILTIN_WALLPAPERS[position]; }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                LinearLayout card;
                ImageView preview;
                TextView caption;
                if (recycled instanceof LinearLayout) {
                    card = (LinearLayout) recycled;
                    preview = (ImageView) card.getChildAt(0);
                    caption = (TextView) card.getChildAt(1);
                } else {
                    card = new LinearLayout(LauncherSettingsActivity.this);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setGravity(android.view.Gravity.CENTER);
                    card.setFocusable(false);
                    card.setPadding(dp(6), dp(6), dp(6), dp(6));
                    GradientDrawable normal = new GradientDrawable();
                    normal.setColor(Color.rgb(35, 42, 70));
                    normal.setCornerRadius(dp(8));
                    normal.setStroke(dp(1), Color.rgb(90, 100, 130));
                    GradientDrawable focused = new GradientDrawable();
                    focused.setColor(Color.rgb(65, 105, 190));
                    focused.setCornerRadius(dp(8));
                    focused.setStroke(dp(3), Color.WHITE);
                    StateListDrawable states = new StateListDrawable();
                    states.addState(new int[]{android.R.attr.state_selected}, focused);
                    states.addState(new int[]{android.R.attr.state_focused}, focused);
                    states.addState(new int[]{}, normal);
                    card.setBackground(states);
                    preview = new ImageView(LauncherSettingsActivity.this);
                    preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    card.addView(preview, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(112)));
                    caption = new TextView(LauncherSettingsActivity.this);
                    caption.setTextColor(Color.WHITE);
                    caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                    caption.setGravity(android.view.Gravity.CENTER);
                    caption.setPadding(0, dp(7), 0, dp(4));
                    card.addView(caption);
                }
                try (InputStream in = getAssets().open("builtin_wallpapers/" + BUILTIN_WALLPAPERS[position])) {
                    Bitmap bitmap = BitmapFactory.decodeStream(in);
                    preview.setImageBitmap(bitmap);
                } catch (Exception ignored) {
                    preview.setImageDrawable(null);
                }
                caption.setText(getString(R.string.builtin_wallpaper_item, position + 1));
                card.setSelected(position == focusedPosition[0]);
                return card;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                focusedPosition[0] = position;
                adapter.notifyDataSetChanged();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.builtin_wallpaper_title)
                .setView(grid)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        grid.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            File target = new File(getFilesDir(), "builtin_wallpaper.jpg");
            File temp = new File(getFilesDir(), "builtin_wallpaper.importing");
            try (InputStream in = getAssets().open("builtin_wallpapers/" + BUILTIN_WALLPAPERS[position]);
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.getFD().sync();
                if (target.exists() && !target.delete()) throw new java.io.IOException("Cannot replace wallpaper");
                if (!temp.renameTo(target)) throw new java.io.IOException("Cannot install wallpaper");
                choices.edit().putString("builtin_wallpaper_choice", BUILTIN_WALLPAPERS[position])
                        .remove(Config.WALLPAPER_CHOICE_KEY).remove("tv_storage_wallpaper_choice").apply();
                changed();
                refresh();
                Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                temp.delete();
                Toast.makeText(this, R.string.wallpaper_import_failed, Toast.LENGTH_LONG).show();
            }
        });
        dialog.setOnShowListener(ignored -> grid.post(() -> {
            grid.requestFocus();
            grid.setSelection(Math.max(0, selected));
        }));
        dialog.show();
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 1];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size()
                : indexOf(images, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    choices.edit().remove("builtin_wallpaper_choice").remove("tv_storage_wallpaper_choice").apply();
                    if (which == images.size()) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                images.get(which).getName()).apply();
                    }
                    changed();
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).remove("builtin_wallpaper_choice")
                            .remove("tv_storage_wallpaper_choice").apply();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Off, then every app with a TV entry point by name. Saved at once; it is read on the next boot. */
    private void showOpenOnStartPicker() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        String current = choices.getString(OpenOnStart.KEY, "");
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.open_on_start_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) choices.edit().remove(OpenOnStart.KEY).apply();
                    else {
                        OpenOnStart.skipThisBoot(this);
                        choices.edit().putString(OpenOnStart.KEY, apps.get(which - 1)[1]).apply();
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The picked app's name, its package when it is gone, or Off. */
    private String openOnStartLabel() {
        String pkg = choices.getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return getString(R.string.off);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        for (int i = 0; i < dockChoices.length; i++) {
            dockChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        openOnStartChoice.setText(getString(R.string.open_on_start_state, openOnStartLabel()));
        prayerTimesChoice.setText(getString(R.string.prayer_times_state, getString(showPrayerTimes ? R.string.on : R.string.off)));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains("builtin_wallpaper_choice")
                || choices.contains(Config.DOCK_OPACITY_KEY)
                || choices.contains(OpenOnStart.KEY) || choices.contains("show_prayer_times")
;
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
