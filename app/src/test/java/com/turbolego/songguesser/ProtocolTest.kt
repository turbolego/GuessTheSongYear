package com.turbolego.songguesser

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Protocol — HMAC signing, session keys, JSON helpers.
 */
class ProtocolTest {

    @Test
    fun `buildJson creates valid JSONObject`() {
        val obj = Protocol.buildJson {
            put("type", "TEST")
            put("value", 42)
        }
        assertEquals("TEST", obj.getString("type"))
        assertEquals(42, obj.getInt("value"))
    }

    @Test
    fun `sign adds sig field`() {
        val auth = Protocol.createAuthenticator(Protocol.generateSessionKey())
        val msg = Protocol.buildJson { put("type", "TEST") }
        auth.sign(msg)
        assertTrue(msg.has(Protocol.FIELD_SIG))
    }

    @Test
    fun `signed message verifies against itself`() {
        val auth = Protocol.createAuthenticator(Protocol.generateSessionKey())
        val msg = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_GUESS_BLIND)
            put(Protocol.FIELD_PLAYER, "Alice")
            put(Protocol.FIELD_GUESS, 1995)
        }
        auth.sign(msg)
        assertTrue(auth.verify(msg))
    }

    @Test
    fun `verify rejects tampered message`() {
        val auth = Protocol.createAuthenticator(Protocol.generateSessionKey())
        val msg = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_VIDEO)
            put(Protocol.FIELD_YEAR, 1990)
        }
        auth.sign(msg)
        msg.put(Protocol.FIELD_YEAR, 2000)
        assertFalse(auth.verify(msg))
    }

    @Test
    fun `verify rejects tampered player name`() {
        val auth = Protocol.createAuthenticator(Protocol.generateSessionKey())
        val msg = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_GUESS_BLIND)
            put(Protocol.FIELD_PLAYER, "Alice")
        }
        auth.sign(msg)
        msg.put(Protocol.FIELD_PLAYER, "Eve")
        assertFalse(auth.verify(msg))
    }

    @Test
    fun `unsigned message is accepted (backward compat)`() {
        val auth = Protocol.createAuthenticator(Protocol.generateSessionKey())
        val msg = Protocol.buildJson { put("type", "END") }
        assertTrue(auth.verify(msg))
    }

    @Test
    fun `different session keys produce different authenticators`() {
        val authA = Protocol.createAuthenticator(Protocol.generateSessionKey())
        val authB = Protocol.createAuthenticator(Protocol.generateSessionKey())
        val msg = Protocol.buildJson { put("type", "TEST") }
        authA.sign(msg)
        assertFalse(authB.verify(msg))
    }

    @Test
    fun `generateSessionKey produces 32-byte Base64`() {
        val key = Protocol.generateSessionKey()
        val decoded = java.util.Base64.getDecoder().decode(key)
        assertEquals(32, decoded.size)
    }

    @Test
    fun `session keys are unique`() {
        val keys = (1..10).map { Protocol.generateSessionKey() }.toSet()
        assertEquals(10, keys.size)
    }

    @Test
    fun `tryParse returns null on invalid JSON`() {
        assertNull(Protocol.tryParse("not json"))
        assertNull(Protocol.tryParse(""))
        assertNull(Protocol.tryParse("{"))
    }

    @Test
    fun `tryParse returns parsed JSON on valid input`() {
        val msg = Protocol.tryParse("""{"type":"HELLO"}""")
        assertNotNull(msg)
        assertEquals("HELLO", msg!!.getString("type"))
    }

    @Test
    fun `message signing works with large messages`() {
        val auth = Protocol.createAuthenticator(Protocol.generateSessionKey())
        val msg = Protocol.buildJson {
            put(Protocol.FIELD_TYPE, Protocol.MSG_REVEAL_RESULT)
            for (i in 0 until 20) {
                put("score_$i", Protocol.buildJson {
                    put(Protocol.FIELD_PLAYER, "Player $i")
                    put(Protocol.FIELD_POINTS_EARNED, (0..100).random())
                })
            }
        }
        auth.sign(msg)
        assertTrue(auth.verify(msg))
    }
}