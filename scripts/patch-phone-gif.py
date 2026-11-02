#!/usr/bin/env python3
"""Apply the small, auditable GIF pass-through patch to the pinned v0.4.7 phone source."""
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
    "core/flows/src/main/kotlin/tv/cleanyour/core/flows/OwnPicture.kt",
    '''    private fun check(jpeg: ByteArray) =
        require(jpeg.size in 4..MAX_BYTES && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "not a JPEG of at most $MAX_BYTES bytes" }''',
    '''    private fun check(image: ByteArray) {
        val jpeg = image.size >= 4 && image[0] == 0xFF.toByte() && image[1] == 0xD8.toByte()
        val gif = image.size >= 6 && image[0] == 'G'.code.toByte() && image[1] == 'I'.code.toByte() &&
            image[2] == 'F'.code.toByte() && image[3] == '8'.code.toByte() &&
            (image[4] == '7'.code.toByte() || image[4] == '9'.code.toByte()) && image[5] == 'a'.code.toByte()
        require(image.size in 4..MAX_BYTES && (jpeg || gif)) { "not a JPEG or GIF of at most $MAX_BYTES bytes" }
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
                    while (out.size() <= OwnPhoto.MAX_BYTES) {
                        val count = input.read(buffer, 0, minOf(buffer.size, OwnPhoto.MAX_BYTES + 1 - out.size()))
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
            if (picked.size <= OwnPhoto.MAX_BYTES) onDone(picked, startDim) else failed = true
            return@LaunchedEffect
        }
        val b = withContext(Dispatchers.IO) { OwnPhoto.decode(context.contentResolver, uri) }
        if (b == null) failed = true else photo = b
    }'''
)

