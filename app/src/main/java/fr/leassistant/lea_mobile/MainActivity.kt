package fr.leassistant.lea_mobile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.*
import android.net.Uri
import android.os.*
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.location.*
import com.google.android.gms.tasks.OnSuccessListener
import fr.leassistant.lea_mobile.*
import kotlinx.android.synthetic.main.activity_main.*
import okio.ByteString
import org.json.JSONObject
import java.util.*


class MainActivity : AppCompatActivity() {
    private val request_code = 101
    private lateinit var info: TextView
    private val storage = Storage(this)
    private lateinit var loc_manager: LocationManager
    private lateinit var loc_client: FusedLocationProviderClient
    private var has_location = false
    private lateinit var loc_provider: String

    private fun showMessage(title: String, message: String, onClose: () -> Unit): AlertDialog
    {
        val alertDialog: AlertDialog = AlertDialog.Builder(this@MainActivity).create()
        alertDialog.setTitle(title)
        alertDialog.setMessage(message)
        alertDialog.setButton(
                AlertDialog.BUTTON_NEUTRAL, "OK"
        ) { _, _ ->
            alertDialog.dismiss()
        }
        alertDialog.setOnDismissListener {
            alertDialog.cancel()
            onClose()
        }
        alertDialog.show()
        return alertDialog
    }

    private var is_gps_asserting = false
    private fun assertGPSEnabled(cb: () -> Unit): Boolean {
        if (!has_location) {
            cb()
            return true
        }
        if (loc_manager.isProviderEnabled(loc_provider)) {
            is_gps_asserting = false
            cb()
            return true
        } else if (!is_gps_asserting) {
            is_gps_asserting = true
            showMessage("Avertissement: GPS désactivé", "Merci d'activer le GPS dans les paramètres du téléphone.") {
                is_gps_asserting = false
                assertGPSEnabled(cb)
            }
            return false
        } else {
            return false
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            request_code -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    record()
                } else {
                    showMessage("Permission nécessaire", "Cette application ne peut pas fonctionner sans ces permissions.") {
                        record()
                    }
                }
            }
        }
    }

    var mp: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        info = findViewById(R.id.text)
        runOnUiThread {
            info.text = "Not connected"
        }
        record()
    }

    fun playSFX(res: Int) {
        try {
            val mp = MediaPlayer.create(this, res)
            mp!!.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun record() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_DENIED) {
                return requestPermissions(
                    arrayOf(android.Manifest.permission.RECORD_AUDIO),
                    request_code
                )
            } else if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_DENIED) {
                return requestPermissions(
                    arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION),
                    request_code
                )
            } else if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED) {
                return requestPermissions(
                    arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                    request_code
                )
            }
        }

        loc_manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val loc_best_provider = loc_manager.getBestProvider(Criteria(), false)
        if (loc_best_provider != null) {
            has_location = true
            loc_provider = loc_best_provider
        }

        assertGPSEnabled() {
            loc_client = LocationServices.getFusedLocationProviderClient(this)

            val gps_timer = Handler(Looper.getMainLooper())
            val gps_asserter = object {
                fun run() {
                    assertGPSEnabled() {}
                    gps_timer.postDelayed({
                        run()
                    }, 1000)
                }
            }
            gps_asserter.run()

            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                registerReceiver(null, ifilter)
            }
            val getBatteryLevel: () -> Float = {
                val res = batteryStatus?.let { intent ->
                    val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    level / scale.toFloat()
                }
                if (res != null)
                    res
                else
                    1.0f
            }

            val handler = object : WebsocketHandler, AudioHandler {
                public val ws = Websocket(storage, this, getBatteryLevel)
                public val audio = Audio(this)
                public var enabled = false
                public var talking = false

                private var hasConnected = false

                val bat_timer = Handler(Looper.getMainLooper())
                val bat_querier = object {
                    fun run() {
                        ws.send("batteryLevel", getBatteryLevel())
                        bat_timer.postDelayed({
                            run()
                        }, 60000)
                    }
                }

                private val location_cb = object : LocationCallback() {
                    override fun onLocationAvailability(locationAvailability: LocationAvailability) {
                        if (!locationAvailability.isLocationAvailable)
                            ws.send("locationDisabled");
                    }
                    override fun onLocationResult(result: LocationResult) {
                        val d = JSONObject()
                        d.put("lat", result.lastLocation.latitude)
                        d.put("lng", result.lastLocation.longitude)
                        ws.send("location", d)
                    }
                }

                private val gps = object {
                    @SuppressLint("MissingPermission")
                    fun start(delta: Double) {
                        stop()
                        loc_client.requestLocationUpdates(LocationRequest.create().setInterval((delta * 1000.0).toLong()), location_cb, Looper.myLooper())
                    }

                    fun stop() {
                        loc_client.removeLocationUpdates(location_cb);
                    }
                }

                override fun onOutdated() {
                    runOnUiThread {
                        val alertDialog: AlertDialog = AlertDialog.Builder(this@MainActivity).create()
                        alertDialog.setTitle("Bonne nouvelle !")
                        alertDialog.setMessage("Une nouvelle version de Léa est disponible !\nUtilisez l'application Play Store pour effectuer la mise à jour.")
                        alertDialog.setButton(
                            AlertDialog.BUTTON_POSITIVE, "Mettre à jour"
                        ) { _, _ ->
                            val packageName = "fr.leassistant.lea_mobile"
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
                            } catch (e: ActivityNotFoundException) {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
                            }
                            onOutdated()
                        }
                        alertDialog.setCancelable(false)
                        alertDialog.setCanceledOnTouchOutside(false)
                        alertDialog.show()
                    }
                }

                override fun onStatus(msg: String, connected: Boolean) {
                    runOnUiThread {
                        info.text = msg
                        val statusButton: ImageButton = findViewById(R.id.status)
                        statusButton.setBackgroundResource(if (connected) R.drawable.led_on else R.drawable.led_off)
                        if (!enabled && connected)
                            playSFX(R.raw.ready)
                        enabled = connected
                        val button: ImageButton = findViewById(R.id.talk)
                        button.setBackgroundResource(if (connected) R.drawable.big_red_button else R.drawable.big_red_button_disabled)
                    }
                    if (connected && !hasConnected) {
                        hasConnected = true
                        bat_querier.run()
                    }
                }

                override fun onAudio(data: ByteString) {
                    val decoded = audio.decode(data)
                    if (decoded != null) {
                        audio.play(decoded)
                    }
                }

                override fun onMicTalked(opusBuffer: ByteString) {
                    ws.sendAudio(opusBuffer)
                }

                override fun onEnableLocation(delta: Double) {
                    runOnUiThread {
                        if (has_location) {
                            if (!assertGPSEnabled() {})
                                ws.send("locationDisabled")
                            gps.start(delta)
                        } else {
                            ws.send("locationUnavailable")
                        }
                    }
                }

                override fun onDisableLocation() {
                    runOnUiThread {
                        if (has_location)
                            gps.stop()
                    }
                }
            }

            val button: ImageButton = findViewById(R.id.talk)
            button.setOnClickListener(object : View.OnClickListener {
                override fun onClick(v: View?) {
                    handler.audio.stopTalking()
                    if (handler.talking) {
                        playSFX(R.raw.stop_talking)
                        button.setBackgroundResource(R.drawable.big_red_button)
                    }
                    handler.talking = false
                }
            })
            button.setOnTouchListener(object : View.OnTouchListener {
                override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                    if (handler.enabled) {
                        handler.audio.startTalking()
                        if (!handler.talking) {
                            button.setBackgroundResource(R.drawable.big_red_button_pushed)
                            playSFX(R.raw.start_talking)
                        }
                        handler.talking = true
                    }
                    return false
                }
            })
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    companion object {
        // Used to load the 'native-lib' library on application startup.
        init {
            System.loadLibrary("native-lib")
        }
    }
}
