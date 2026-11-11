package io.github.evgenykon.mybooks.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.posix.memcpy

/**
 * Swift-friendly entry points. Converting NSData to ByteArray element by element
 * would be extremely slow for multi-megabyte books, so the bytes are copied in one go.
 */
@OptIn(ExperimentalForeignApi::class)
fun parseBookData(data: NSData): Book {
    val size = data.length.toInt()
    val bytes = if (size == 0) {
        ByteArray(0)
    } else {
        ByteArray(size).apply {
            usePinned { pinned ->
                memcpy(pinned.addressOf(0), data.bytes, data.length)
            }
        }
    }
    return Fb2Parser.parseBytes(bytes)
}

/** Reading progress in percent (0..100) for a position, computed by the shared core. */
fun progressPercent(book: Book, blockIndex: Int, charOffset: Int): Int =
    ReadingProgress.percent(book, Position(blockIndex, charOffset))
