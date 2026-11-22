package dev.ranjan.tvrecents

import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers the preference value format and handling of blank entries. */
class MruCodecTest {
    @Test
    fun decode_blankAndJunk() {
        assertEquals(emptyList<String>(), MruCodec.decode(null))
        assertEquals(emptyList<String>(), MruCodec.decode(""))
        assertEquals(emptyList<String>(), MruCodec.decode("\n\n"))
        assertEquals(listOf("a", "b"), MruCodec.decode(" a \n\nb"))
    }

    @Test
    fun encodeDecode_roundTrip() {
        val packages = listOf("app.b", "app.a", "app.c")
        val encoded = MruCodec.encode(packages)
        assertEquals("app.b\napp.a\napp.c", encoded)
        assertEquals(packages, MruCodec.decode(encoded))
    }
}
