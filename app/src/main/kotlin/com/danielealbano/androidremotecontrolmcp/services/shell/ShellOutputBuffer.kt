package com.danielealbano.androidremotecontrolmcp.services.shell

private const val BYTE_MASK = 0xFF
private const val CONTINUATION_MASK = 0xC0
private const val CONTINUATION_BITS = 0x80
private const val TWO_BYTE_MASK = 0xE0
private const val TWO_BYTE_LEAD = 0xC0
private const val THREE_BYTE_MASK = 0xF0
private const val THREE_BYTE_LEAD = 0xE0
private const val FOUR_BYTE_MASK = 0xF8
private const val FOUR_BYTE_LEAD = 0xF0
private const val THREE_BYTES = 3
private const val FOUR_BYTES = 4

data class ShellOutputChunk(
    val text: String,
    val startOffset: Long,
    val nextOffset: Long,
    val droppedBytes: Long,
    val totalBytes: Long,
)

class ShellOutputBuffer(
    capacityBytes: Int,
) {
    private val headCapacity = capacityBytes / 2
    private val tailCapacity = capacityBytes - headCapacity
    private var head = ByteArray(0)
    private var headLength = 0
    private var tail = ByteArray(0)
    private var tailStart = 0
    private var tailLength = 0
    private var total = 0L

    @Synchronized
    fun append(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val toHead = minOf(length, headCapacity - headLength)
        if (toHead > 0) {
            head = grow(head, headLength + toHead, headCapacity)
            System.arraycopy(bytes, offset, head, headLength, toHead)
            headLength += toHead
        }
        appendTail(bytes, offset + toHead, offset + length)
        total += length
    }

    @Synchronized
    fun totalBytes(): Long = total

    @Synchronized
    fun read(
        fromOffset: Long,
        maxBytes: Int,
        growing: Boolean,
    ): ShellOutputChunk {
        require(fromOffset in 0..total) { "offset $fromOffset is outside 0..$total" }
        val tailFirst = total - tailLength
        val gap = tailFirst > headLength
        val start = if (gap && fromOffset >= headLength && fromOffset < tailFirst) tailFirst else fromOffset
        val regionEnd = if (gap && start < headLength) headLength.toLong() else total
        val end = minOf(regionEnd, start + maxBytes)
        val bytes = copyRange(start, end)
        val trim = end < regionEnd || (end == total && growing)
        val size = if (trim) completeUtf8Prefix(bytes) else bytes.size
        return ShellOutputChunk(String(bytes, 0, size, Charsets.UTF_8), start, start + size, start - fromOffset, total)
    }

    private fun appendTail(
        bytes: ByteArray,
        from: Int,
        until: Int,
    ) {
        var index = from
        while (index < until) {
            if (tailLength < tailCapacity) {
                tail = grow(tail, minOf(tailCapacity, tailLength + (until - index)), tailCapacity)
            }
            val writeIndex = (tailStart + tailLength) % tailCapacity
            val count = minOf(until - index, tailCapacity - writeIndex)
            System.arraycopy(bytes, index, tail, writeIndex, count)
            val overflow = maxOf(0, tailLength + count - tailCapacity)
            tailLength = minOf(tailCapacity, tailLength + count)
            tailStart = (tailStart + overflow) % tailCapacity
            index += count
        }
    }

    private fun grow(
        array: ByteArray,
        required: Int,
        cap: Int,
    ): ByteArray = if (array.size >= required) array else array.copyOf(minOf(cap, maxOf(required, array.size * 2)))

    private fun copyRange(
        start: Long,
        end: Long,
    ): ByteArray {
        val out = ByteArray((end - start).toInt())
        val fromHead = (minOf(end, headLength.toLong()) - start).coerceAtLeast(0).toInt()
        if (fromHead > 0) System.arraycopy(head, start.toInt(), out, 0, fromHead)
        if (fromHead < out.size) copyFromTail(maxOf(start, headLength.toLong()), out, fromHead)
        return out
    }

    private fun copyFromTail(
        offset: Long,
        out: ByteArray,
        at: Int,
    ) {
        val first = ((tailStart + (offset - (total - tailLength))) % tailCapacity).toInt()
        val count = out.size - at
        val firstPart = minOf(count, tailCapacity - first)
        System.arraycopy(tail, first, out, at, firstPart)
        System.arraycopy(tail, 0, out, at + firstPart, count - firstPart)
    }

    private fun completeUtf8Prefix(bytes: ByteArray): Int {
        var lead = bytes.size - 1
        while (lead >= 0 && bytes.size - lead < FOUR_BYTES && isContinuation(bytes[lead])) lead--
        if (lead < 0) return bytes.size
        return if (lead + sequenceLength(bytes[lead]) > bytes.size) lead else bytes.size
    }

    private fun isContinuation(byte: Byte): Boolean = (byte.toInt() and CONTINUATION_MASK) == CONTINUATION_BITS

    private fun sequenceLength(byte: Byte): Int {
        val value = byte.toInt() and BYTE_MASK
        return when {
            (value and TWO_BYTE_MASK) == TWO_BYTE_LEAD -> 2
            (value and THREE_BYTE_MASK) == THREE_BYTE_LEAD -> THREE_BYTES
            (value and FOUR_BYTE_MASK) == FOUR_BYTE_LEAD -> FOUR_BYTES
            else -> 1
        }
    }
}
