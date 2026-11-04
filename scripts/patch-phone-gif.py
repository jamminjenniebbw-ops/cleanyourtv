#!/usr/bin/env python3
"""Apply byte-preserving GIF and MP4 wallpaper support to pinned v0.4.7."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "phone"

def replace(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text()
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"GIF patch context changed; refusing partial patch: {path}")
    p.write_text(text.replace(old, new, 1))
    print(f"patched {path}")

replace(
    "ui/design/src/main/kotlin/tv/cleanyour/ui/design/component/OwnPhoto.kt",
    '''    const val MAX_BYTES = 280_000''',
    '''    const val MAX_BYTES = 280_000
    const val GIF_MAX_BYTES = 1_048_576'''
)

replace(
    "core/flows/src/main/kotlin/tv/cleanyour/core/flows/OwnPicture.kt",
    '''    const val MAX_BYTES = 280_000''',
    '''    const val MAX_BYTES = 280_000
    const val GIF_MAX_BYTES = 1_048_576'''
)

replace(
    "core/flows/src/main/kotlin/tv/cleanyour/core/flows/OwnPicture.kt",
    '''    private fun check(jpeg: ByteArray) =
        require(jpeg.size in 4..MAX_BYTES && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "not a JPEG of at most $MAX_BYTES bytes" }''',
    '''    private fun check(image: ByteArray) {
        val jpeg = image.size >= 4 && image[0] == 0xFF.toByte() && image[1] == 0xD8.toByte()
        val gif = image.size >= 6 && image[0] == 'G'.code.toByte() && image[1] == 'I'.code.toByte() &&
            image[2] == 'F'.code.toByte() && image[3] == '8'.code.toByte() &&
            (image[4] == '7'.code.toByte() || image[4] == '9'.code.toByte()) && image[5] == 'a'.code.toByte()
        require((jpeg && image.size in 4..MAX_BYTES) || (gif && image.size in 6..GIF_MAX_BYTES)) {
            "not a JPEG of at most $MAX_BYTES bytes or a GIF of at most $GIF_MAX_BYTES bytes"
        }
    }'''
)

replace(
    "ui/design/src/main/kotlin/tv/cleanyour/ui/design/component/OwnPhoto.kt",
    '''    // The first size and quality, in order, whose bytes fit `max`.''',
    '''    // GIFs are sent byte-for-byte: no crop, frame flattening or JPEG re-encoding.
    fun isGif(bytes: ByteArray): Boolean =
        bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte() &&
            (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) && bytes[5] == 'a'.code.toByte()

    // The first size and quality, in order, whose bytes fit `max`.'''
)

replace(
    "ui/design/src/main/kotlin/tv/cleanyour/ui/design/component/PhotoCrop.kt",
    '''    LaunchedEffect(uri) {
        val b = withContext(Dispatchers.IO) { OwnPhoto.decode(context.contentResolver, uri) }
        if (b == null) failed = true else photo = b
    }''',
    '''    LaunchedEffect(uri) {
        // The GIF route deliberately bypasses the still-photo cropper. Read at most one byte
        // over the TV's wallpaper limit, validate the signature, then hand the original bytes
        // to the same Look flow; it stores and transfers them unchanged.
        val picked = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (out.size() <= OwnPhoto.GIF_MAX_BYTES) {
                        val count = input.read(buffer, 0, minOf(buffer.size, OwnPhoto.GIF_MAX_BYTES + 1 - out.size()))
                        if (count < 0) break
                        if (count == 0) {
                            val singleByte = input.read()
                            if (singleByte < 0) break
                            out.write(singleByte)
                        } else {
                            out.write(buffer, 0, count)
                        }
                    }
                    out.toByteArray()
                }
            }.getOrNull()
        }
        if (picked != null && OwnPhoto.isGif(picked)) {
            if (picked.size <= OwnPhoto.GIF_MAX_BYTES) onDone(picked, startDim) else failed = true
            return@LaunchedEffect
        }
        val b = withContext(Dispatchers.IO) { OwnPhoto.decode(context.contentResolver, uri) }
        if (b == null) failed = true else photo = b
    }'''
)

# The upstream transport has its own, independent 300 KiB limit. Raise it to the
# GIF limit while retaining the existing wallpaper.jpg wire name and provider route.
replace(
    "core/engine/src/main/kotlin/tv/cleanyour/core/engine/FileOverlayTransport.kt",
    "const val MAX_WALLPAPER_BYTES = 300L * 1024",
    "const val MAX_WALLPAPER_BYTES = 1_048_576L"
)

# Permit the existing provider to write the same wallpaper payload under its legacy
# filename; the launcher identifies GIF data by signature, not by the filename.
replace(
    "core/ops/src/main/kotlin/tv/cleanyour/core/ops/ThemeProviderLines.kt",
    'val FILES: Set<String> = setOf("config.json", "wallpaper.jpg")',
    'val FILES: Set<String> = setOf("config.json", "wallpaper.jpg", "wallpaper.gif")'
)

# MP4 wallpaper: original bytes, capped at 20 MiB. The legacy wallpaper.jpg
# transport name is retained for compatibility; the TV detects MP4 by its ftyp
# signature, just as it detects GIF by its magic bytes.
replace(
    "ui/design/src/main/kotlin/tv/cleanyour/ui/design/component/OwnPhoto.kt",
    "const val GIF_MAX_BYTES = 1_048_576",
    "const val GIF_MAX_BYTES = 1_048_576\n    const val MP4_MAX_BYTES = 20_971_520"
)

replace(
    "ui/design/src/main/kotlin/tv/cleanyour/ui/design/component/OwnPhoto.kt",
    '''    // The first size and quality, in order, whose bytes fit `max`.''',
    '''    // MP4s are sent byte-for-byte, without transcoding.
    fun isMp4(bytes: ByteArray): Boolean =
        bytes.size >= 12 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()

    // The first size and quality, in order, whose bytes fit `max`.'''
)

replace(
    "ui/design/src/main/kotlin/tv/cleanyour/ui/design/component/PhotoCrop.kt",
    "while (out.size() <= OwnPhoto.GIF_MAX_BYTES) {",
    "while (out.size() <= OwnPhoto.MP4_MAX_BYTES) {"
)
replace(
    "ui/design/src/main/kotlin/tv/cleanyour/ui/design/component/PhotoCrop.kt",
    "OwnPhoto.GIF_MAX_BYTES + 1 - out.size()",
    "OwnPhoto.MP4_MAX_BYTES + 1 - out.size()"
)
replace(
    "ui/design/src/main/kotlin/tv/cleanyour/ui/design/component/PhotoCrop.kt",
    '''        if (picked != null && OwnPhoto.isGif(picked)) {
            if (picked.size <= OwnPhoto.GIF_MAX_BYTES) onDone(picked, startDim) else failed = true
            return@LaunchedEffect
        }''',
    '''        if (picked != null && (OwnPhoto.isGif(picked) || OwnPhoto.isMp4(picked))) {
            val limit = if (OwnPhoto.isMp4(picked)) OwnPhoto.MP4_MAX_BYTES else OwnPhoto.GIF_MAX_BYTES
            if (picked.size <= limit) onDone(picked, startDim) else failed = true
            return@LaunchedEffect
        }'''
)

replace(
    "core/flows/src/main/kotlin/tv/cleanyour/core/flows/OwnPicture.kt",
    "const val GIF_MAX_BYTES = 1_048_576",
    "const val GIF_MAX_BYTES = 1_048_576\n    const val MP4_MAX_BYTES = 20_971_520"
)
replace(
    "core/flows/src/main/kotlin/tv/cleanyour/core/flows/OwnPicture.kt",
    '''        require((jpeg && image.size in 4..MAX_BYTES) || (gif && image.size in 6..GIF_MAX_BYTES)) {
            "not a JPEG of at most $MAX_BYTES bytes or a GIF of at most $GIF_MAX_BYTES bytes"
        }''',
    '''        val mp4 = image.size >= 12 && image[4] == 'f'.code.toByte() && image[5] == 't'.code.toByte() &&
            image[6] == 'y'.code.toByte() && image[7] == 'p'.code.toByte()
        require((jpeg && image.size in 4..MAX_BYTES) || (gif && image.size in 6..GIF_MAX_BYTES) ||
            (mp4 && image.size in 12..MP4_MAX_BYTES)) {
            "not a JPEG, GIF (max $GIF_MAX_BYTES bytes), or MP4 (max $MP4_MAX_BYTES bytes)"
        }'''
)
replace(
    "core/engine/src/main/kotlin/tv/cleanyour/core/engine/FileOverlayTransport.kt",
    "const val MAX_WALLPAPER_BYTES = 1_048_576L",
    "const val MAX_WALLPAPER_BYTES = 20_971_520L"
)

# The system picker must expose videos as well as still images. PhotoCropDialog
# recognises MP4 by the ISO-BMFF ftyp signature and passes its bytes unchanged.
replace(
    "app/src/main/kotlin/tv/cleanyour/app/AppRoot.kt",
    "PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)",
    "PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)"
)




# Large wallpaper uploads can legitimately pause while Android TV's adbd drains
# the 64 KiB sync stream. The default 15 s idle deadline is too aggressive for
# these writes on some Android 14 TV firmware. Keep the 10 min total cap, but
# allow up to 60 s without a sync response before declaring the push dead.
replace(
    "adb/session/src/main/kotlin/tv/cleanyour/adb/session/Support.kt",
    "const val PUSH_IDLE_MS = 15_000L",
    "const val PUSH_IDLE_MS = 60_000L"
)


# MP4 preview in the phone Look editor: BitmapFactory cannot decode a video file.
# Decode its first sync frame so the selected custom video is visible in the
# preview card instead of appearing as a black rectangle.
replace(
    "ui/design/src/main/kotlin/tv/cleanyour/ui/design/component/LookEditor.kt",
    "import android.graphics.BitmapFactory",
    """import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever"""
)
replace(
    "ui/design/src/main/kotlin/tv/cleanyour/ui/design/component/LookEditor.kt",
    """runCatching { BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap() }.getOrNull()""",
    """runCatching {
                    val mp4 = java.io.RandomAccessFile(p, "r").use { file ->
                        if (file.length() < 12) false else {
                            file.seek(4)
                            file.readByte() == 'f'.code.toByte() &&
                                file.readByte() == 't'.code.toByte() &&
                                file.readByte() == 'y'.code.toByte() &&
                                file.readByte() == 'p'.code.toByte()
                        }
                    }
                    if (mp4) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(p)
                            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            if (frame == null) null else {
                                val scale = minOf(1f, 1280f / maxOf(frame.width, frame.height))
                                val thumb = if (scale < 1f) Bitmap.createScaledBitmap(
                                    frame, (frame.width * scale).toInt().coerceAtLeast(1),
                                    (frame.height * scale).toInt().coerceAtLeast(1), true
                                ) else frame
                                thumb.asImageBitmap()
                            }
                        } finally {
                            retriever.release()
                        }
                    } else {
                        BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                    }
                }.getOrNull()"""
)


# Scheduled wallpapers use the launcher's existing wallpapers/ provider namespace.
# Keep this allowlist narrow: a manifest plus image/video files only. The staging
# filename is flattened so the shell path remains a single safe path segment.
replace(
    "core/ops/src/main/kotlin/tv/cleanyour/core/ops/ThemeProviderLines.kt",
    "import tv.cleanyour.core.model.RemotePath",
    '''import tv.cleanyour.core.model.RemotePath
import java.util.regex.Pattern'''
)
replace(
    "core/ops/src/main/kotlin/tv/cleanyour/core/ops/ThemeProviderLines.kt",
    '''    val FILES: Set<String> = setOf("config.json", "wallpaper.jpg", "wallpaper.gif")

    // The write:''',
    '''    val FILES: Set<String> = setOf("config.json", "wallpaper.jpg")
    private val SCHEDULE_IMAGE = Pattern.compile("[A-Za-z0-9_.-]+\\\\.(jpg|jpeg|png|webp|gif|mp4)", Pattern.CASE_INSENSITIVE)

    // The write:'''
)
replace(
    "core/ops/src/main/kotlin/tv/cleanyour/core/ops/ThemeProviderLines.kt",
    '''    fun isThemeFile(path: RemotePath): Boolean = path.dir == RemoteDir.OVERLAY && path.segments.size == 1 && path.segments[0] in FILES

    fun uri(path: RemotePath): String {
        require(isThemeFile(path)) { "the launcher's provider takes only ${FILES.joinToString()} in ${RemoteDir.OVERLAY.path}" }
        return "content://$AUTHORITY/" + path.segments[0]
    }

    // Where a file is staged before the provider reads it: theme-config.json, theme-wallpaper.jpg in TMP.
    fun stage(path: RemotePath): RemotePath {
        require(isThemeFile(path)) { "only a theme file is staged" }
        return RemotePath(RemoteDir.TMP, listOf(STAGE_PREFIX + path.segments[0]))
    }''',
    '''    fun isThemeFile(path: RemotePath): Boolean {
        if (path.dir != RemoteDir.OVERLAY) return false
        if (path.segments.size == 1) return path.segments[0] in FILES
        if (path.segments.size != 2 || path.segments[0] != "wallpapers") return false
        val name = path.segments[1]
        return name == "wallpaper_schedule.json" || SCHEDULE_IMAGE.matcher(name).matches()
    }

    fun uri(path: RemotePath): String {
        require(isThemeFile(path)) { "the launcher's provider refuses this theme path" }
        return "content://$AUTHORITY/" + path.segments.joinToString("/")
    }

    // Flatten nested destinations into a single safe temporary filename.
    fun stage(path: RemotePath): RemotePath {
        require(isThemeFile(path)) { "only an allowlisted theme file is staged" }
        return RemotePath(RemoteDir.TMP, listOf(STAGE_PREFIX + path.segments.joinToString("-")))
    }'''
)
replace(
    "core/ops/src/test/kotlin/tv/cleanyour/core/ops/ThemeProviderLinesTest.kt",
    '''            RemotePath(RemoteDir.OVERLAY, listOf("wallpapers", "a.jpg")))) {''',
    '''            RemotePath(RemoteDir.OVERLAY, listOf("wallpapers", "notes.txt")))) {'''
)
replace(
    "core/ops/src/test/kotlin/tv/cleanyour/core/ops/ThemeProviderLinesTest.kt",
    '''    @Test fun aPushThroughTheProviderStagesThenWritesThenReadsBackAsTheLauncher() {''',
    '''    @Test fun scheduledWallpaperAssetsUseOnlyTheNestedWallpapersAllowlist() {
        val asset = RemotePath(RemoteDir.OVERLAY, listOf("wallpapers", "dawn-loop.MP4"))
        val manifest = RemotePath(RemoteDir.OVERLAY, listOf("wallpapers", "wallpaper_schedule.json"))
        assertTrue(ThemeProviderLines.isThemeFile(asset))
        assertTrue(ThemeProviderLines.isThemeFile(manifest))
        assertEquals("content://com.gathin.tvhome.theme/wallpapers/dawn-loop.MP4", ThemeProviderLines.uri(asset))
        assertEquals(RemotePath(RemoteDir.TMP, listOf("theme-wallpapers-dawn-loop.MP4")), ThemeProviderLines.stage(asset))
        assertEquals("content://com.gathin.tvhome.theme/wallpapers/wallpaper_schedule.json", ThemeProviderLines.uri(manifest))
        assertFalse(ThemeProviderLines.isThemeFile(RemotePath(RemoteDir.OVERLAY, listOf("wallpapers", "notes.txt"))))
        assertFalse(ThemeProviderLines.isThemeFile(RemotePath(RemoteDir.OVERLAY, listOf("wallpapers", "sub", "dawn.jpg"))))
    }

    @Test fun aPushThroughTheProviderStagesThenWritesThenReadsBackAsTheLauncher() {'''
)
replace(
    "core/engine/src/main/kotlin/tv/cleanyour/core/engine/FileOverlayTransport.kt",
    '''    override fun resetPlanSteps(route: FileRoute): List<Op> =''',
    '''    replace(
    "core/engine/src/main/kotlin/tv/cleanyour/core/engine/FileOverlayTransport.kt",
    "import tv.cleanyour.core.ops.ConfigTransportKind",
    """import tv.cleanyour.core.ops.ConfigTransportKind
import tv.cleanyour.core.ops.ThemeProviderLines"""
)

// Schedule transfer is deliberately separate from the legacy one-wallpaper plan. The manifest
    // is pushed last, so the TV cannot activate a new schedule until every referenced asset arrived.
    fun scheduledWallpaperPlanSteps(assets: List<Pair<String, BlobRef>>, route: FileRoute): List<Op> {
        require(assets.size in 2..33) { "a schedule needs one manifest and 1 to 32 assets" }
        val names = assets.map { it.first }
        require(names.distinct().size == names.size) { "scheduled wallpaper filenames must be unique" }
        require(names.count { it == "wallpaper_schedule.json" } == 1) { "exactly one wallpaper_schedule.json is required" }
        var total = 0L
        assets.forEach { (name, blob) ->
            val path = RemotePath(RemoteDir.OVERLAY, listOf("wallpapers", name))
            require(ThemeProviderLines.isThemeFile(path)) { "unsupported scheduled wallpaper file: $name" }
            val limit = if (name == "wallpaper_schedule.json") MAX_SCHEDULE_MANIFEST_BYTES else MAX_SCHEDULE_ASSET_BYTES
            require(blob.size in 1..limit) { "$name must be 1 to $limit bytes" }
            total = Math.addExact(total, blob.size)
        }
        require(total <= MAX_SCHEDULE_TOTAL_BYTES) { "scheduled wallpaper package exceeds $MAX_SCHEDULE_TOTAL_BYTES bytes" }
        val pushes = assets.sortedBy { it.first == "wallpaper_schedule.json" }.map { (name, blob) ->
            Op.PushAsset(blob, RemotePath(RemoteDir.OVERLAY, listOf("wallpapers", name)), blob.sha256, route)
        }
        return buildList {
            add(Op.StartActivity(launcher, wait = true))
            add(Op.Mkdir(RemoteDir.TMP))
            if (route == FileRoute.SYNC) add(Op.Mkdir(RemoteDir.OVERLAY))
            addAll(pushes)
            add(Op.RestartHome(launcher))
        }
    }

    override fun resetPlanSteps(route: FileRoute): List<Op> ='''
)
replace(
    "core/engine/src/main/kotlin/tv/cleanyour/core/engine/FileOverlayTransport.kt",
    '''        const val MAX_WALLPAPER_BYTES = 20_971_520L''',
    '''        const val MAX_WALLPAPER_BYTES = 20_971_520L
        const val MAX_SCHEDULE_MANIFEST_BYTES = 64L * 1024
        const val MAX_SCHEDULE_ASSET_BYTES = 20L * 1024 * 1024
        const val MAX_SCHEDULE_TOTAL_BYTES = 100L * 1024 * 1024'''
)
