package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlin.random.Random

/** A [Random] whose every roll is 0, so the Certificate Transparency trigger fires on each request. */
object ZeroRandom : Random() {
    override fun nextBits(bitCount: Int): Int = 0
}
