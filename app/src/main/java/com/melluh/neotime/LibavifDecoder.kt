package com.melluh.neotime

import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import java.nio.ByteBuffer
import okio.ByteString.Companion.encodeUtf8
import org.aomedia.avif.android.AvifDecoder

/**
 * Decodes AVIF with libavif rather than the platform. The people pages' CDN stores some photos as
 * RGB (the identity matrix), which the platform reads as YUV anyway, scrambling their colours
 * beyond repair. It also can't decode AVIF at all below API 31.
 */
class LibavifDecoder(private val source: ImageSource) : Decoder {
    override suspend fun decode(): DecodeResult {
        val encoded = source.source().readByteArray()
        val buffer = ByteBuffer.allocateDirect(encoded.size).put(encoded).apply { flip() }

        val info = AvifDecoder.Info()
        check(AvifDecoder.getInfo(buffer, encoded.size, info)) { "Couldn't read AVIF header" }
        val bitmap = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
        check(AvifDecoder.decode(buffer, encoded.size, bitmap)) { "Couldn't decode AVIF" }
        return DecodeResult(bitmap.asImage(), isSampled = false)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? =
            if (result.source.source().rangeEquals(4, AVIF_BRAND)) LibavifDecoder(result.source) else null
    }

    private companion object {
        val AVIF_BRAND = "ftypavif".encodeUtf8()
    }
}
