#!/usr/bin/env python3
"""Apply byte-preserving GIF wallpaper support (GIF <= 1,048,576 bytes; JPEG limit unchanged) to pinned v0.4.7."""
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
