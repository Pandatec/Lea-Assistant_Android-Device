package fr.leassistant.lea_mobile

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

import org.json.JSONObject

class WebsocketUnitTest {
    @Test
    fun genMsg_noData() {
        val exp = JSONObject()
        Websocket.genMsg("testmsg")
        exp.put("type", "testmsg")
        assertEquals(exp.toString(), Websocket.genMsg("testmsg"))
    }

    @Test
    fun genMsg_withData() {
        val exp = JSONObject()
        Websocket.genMsg("testmsg", "payload")
        exp.put("type", "testmsg")
        exp.put("data", "payload")
        assertEquals(exp.toString(), Websocket.genMsg("testmsg", "payload"))
    }

    @Test
    fun getData_correct() {
        val o = JSONObject()
        o.put("data", "payload")
        assertEquals("payload", Websocket.getData(o, "test"))
    }

    @Test
    fun getData_nonString() {
        val o = JSONObject()
        o.put("data", JSONObject())
        assertEquals("{}", Websocket.getData(o, "test"))
    }

    @Test
    fun getData_missing() {
        val o = JSONObject()
        assertEquals(null, Websocket.getData(o, "test"))
    }
}