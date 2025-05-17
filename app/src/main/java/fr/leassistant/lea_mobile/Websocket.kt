package fr.leassistant.lea_mobile

import android.os.*
import com.theeasiestway.opus.Constants
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
import org.json.JSONObject

interface WebsocketHandler {
    fun onOutdated()
    fun onStatus(msg: String, connected: Boolean)
    fun onAudio(data: ByteString)
    fun onEnableLocation(delta: Double)
    fun onDisableLocation()
}

class Websocket(storage: Storage, handler: WebsocketHandler, getBatteryLevel: () -> Float) {
    private val storage = storage
    private val handler = handler
    private val getBatteryLevel = getBatteryLevel
    private val httpClient = OkHttpClient()
    private var websocket: WebSocket? = null
    private var isOutdated = false
    private var reconnectHandler = Handler(Looper.getMainLooper())

    companion object {
        fun genMsg(type: String, data: Any? = null) : String {
            val r = JSONObject()
            r.put("type", type)
            if (data != null) {
                r.put("data", data)
            }
            return r.toString()
        }

        fun getData(json: JSONObject, messageType: String) : String? {
            if (!json.has("data")) {
                println("WS ERROR: No data member but was required for message type '${messageType}'")
                return null
            }
            val r = json.optString("data", "")
            if (r == "") {
                println("WS ERROR: data member empty but was required for message type '${messageType}'")
                return null
            }
            return r
        }
    }

    fun send(type: String, data: Any? = null) {
        val r = JSONObject()
        r.put("type", type)
        if (data != null)
            r.put("data", data)
        websocket?.send(r.toString())
    }

    private fun initWs() {
        if (isOutdated)
            return
        if (websocket != null)
            return
        var serverURL = "wss://dev.api.leassistant.fr/dev"
        if (BuildConfig.RELEASE == "true")
            serverURL = "wss://api.leassistant.fr/dev"
        websocket = httpClient.newWebSocket(
            Request.Builder().url(serverURL).build(),
            object : WebSocketListener() {
                private var closed = false

                override fun onOpen(webSocket: WebSocket, response: Response?) {
                    webSocket.send(genMsg("versionAndroid", BuildConfig.VERSION))
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handler.onAudio(bytes)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = JSONObject(text)
                    if (!json.has("type")) {
                        println("WS ERROR: Unknown JSON message type")
                        return
                    }
                    val type = json.optString("type", "")
                    if (type == "") {
                        println("WS ERROR: Unknown JSON message type: must be string")
                        return
                    }
                    if (type == "outdated") {
                        isOutdated = true
                        handler.onOutdated()
                    }
                    if (type == "uptodate") {
                        val tok = storage.read("token.private")
                        handler.onStatus("Authenticating..", false)
                        if (tok.size == 0)
                            webSocket.send(genMsg("firstConnexion", getBatteryLevel()))
                        else
                            webSocket.send(genMsg("login", String(tok)))
                    }
                    if (type == "token") {
                        val tok = getData(json, type)
                        if (tok != null) {
                            storage.write("token.private", tok.toByteArray())
                            println("Got token")
                            handler.onStatus("Connected.", true)
                        }
                    }
                    if (type == "tokenAccepted") {
                        handler.onStatus("Connected.", true)
                    }
                    if (type == "enableLocation") {
                        handler.onEnableLocation(json.getJSONObject("data").getDouble("delta"))
                    }
                    if (type == "disableLocation") {
                        handler.onDisableLocation()
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String?) {
                    if (reason == "BAD_CRED") {
                        storage.erase("token.private")
                    }
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String?) {
                    if (closed)
                        return
                    websocket = null
                    closed = true
                    handler.onStatus("Disconnected by server. Reconnecting..", false)

                    println("WS CLOSED: ${reason}. Reconnecting in 2 seconds.")
                    reconnectHandler.postDelayed({
                        initWs()
                    }, 2000)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable?, response: Response?) {
                    if (closed)
                        return
                    websocket = null
                    closed = true
                    webSocket.close(1000, "Error happened")

                    handler.onStatus("Disconnected. Reconnecting..", false)

                    println("WS FAILURE: ${t.toString()}. Reconnecting in 2 seconds.")
                    reconnectHandler.postDelayed({
                        initWs()
                    }, 2000)
                }
            }
        )
    }

    init {
        initWs()
    }

    public fun sendAudio(data: ByteString) {
        websocket?.send(data)
    }
}