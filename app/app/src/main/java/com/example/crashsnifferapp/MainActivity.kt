package com.example.crashsnifferapp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.InputStream
import java.lang.Math.*
import java.util.*
import kotlin.random.Random
import android.media.AudioManager
import android.media.ToneGenerator


class MainActivity : AppCompatActivity() {
    private lateinit var editTextW: EditText
    private lateinit var editTextR: EditText
    private lateinit var editTextT: EditText
    private lateinit var buttonStart: Button
    private lateinit var resultText: TextView

    private var crashDetectionJob: Job? = null
    private val positions = mutableListOf<Pair<Double, Double>>()
    private val INTERVAL_MILLIS = 50L // 0.05 seconds
    private val CONSECUTIVE_COUNTS = 3L  // Detect collision when repeatedly detected

    private var r1Latest = 60000.0
    private var r2Latest = 60000.0
    private var collisionCount = 0L

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null

    private lateinit var buttonScan: Button
    private lateinit var bluetoothStatus: TextView
    private lateinit var deviceList: ListView
    private val deviceNames = mutableListOf<String>()
    private lateinit var deviceAdapter: ArrayAdapter<String>

    private var warningBeepJob: Job? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val requiredPermissions = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADMIN,
            )
            val missing = requiredPermissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            if (missing.isNotEmpty()) {
                requestPermissions(this, missing.toTypedArray(), 100)
            }
        }

        // Assuming Serial.println("r1:63.4,r2:59.1"); // Sent over Bluetooth
        bluetoothStatus = findViewById(R.id.bluetoothStatus)
        buttonScan = findViewById(R.id.buttonScan)
        deviceList = findViewById(R.id.deviceList)

        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceNames)
        deviceList.adapter = deviceAdapter
        deviceList.setOnItemClickListener { _, _, position, _ ->
            val selected = deviceNames[position]
            val address = selected.substringAfterLast(" - ")
            connectToDevice(address)
        }

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        buttonScan.setOnClickListener {
            // scanBluetoothDevices()
            connectToRaspberryPiDirectly()
        }

        editTextW = findViewById(R.id.editTextW)
        editTextR = findViewById(R.id.editTextR)
        editTextT = findViewById(R.id.editTextT)
        buttonStart = findViewById(R.id.buttonStart)
        resultText = findViewById(R.id.resultText)

        buttonStart.setOnClickListener {
            if (crashDetectionJob == null) {
                buttonStart.text = "Stop Crash Detection"
                startCrashDetection()
            } else {
                stopCrashDetection()
            }
        }
    }

    fun computeStepVectors(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        // Return raw dx, dy between each pair of consecutive points
        return (1 until points.size).map { i ->
            val dx = points[i].first - points[i - 1].first
            val dy = points[i].second - points[i - 1].second
            Pair(dx, dy)
        }
    }

    fun smoothVectors(vectors: List<Pair<Double, Double>>, windowSize: Int = 5): List<Pair<Double, Double>> {
        return vectors.mapIndexed { i, _ ->
            val start = maxOf(0, i - windowSize / 2)
            val end = minOf(vectors.size, i + windowSize / 2 + 1)
            val window = vectors.subList(start, end)
            val avgDx = window.map { it.first }.average()
            val avgDy = window.map { it.second }.average()
            Pair(avgDx, avgDy)
        }
    }

    private fun startCrashDetection() {
        crashDetectionJob = CoroutineScope(Dispatchers.Default).launch {
            // Initialize
            positions.clear()
            collisionCount = 0
            r1Latest = 60000
            r2Latest = 60000
            
            while (isActive) {
                val w = editTextW.text.toString().toDoubleOrNull() ?: 0.5
                val r = editTextR.text.toString().toDoubleOrNull() ?: 2.0

//                val r1 = Random.nextDouble(50.0, 150.0)
//                val r2 = Random.nextDouble(50.0, 150.0)
                val r1 = r1Latest
                val r2 = r2Latest

                // val (x, y) = trilateration(r1, r2, w, offset1=-0.15, offset2=-0.15)

                if ((r1 >= 60000) || (r2 >= 60000)) continue

                val (x, y) = trilateration(r1, r2, w, offset1 = -0.15, offset2 = -0.15)

                if ((x < -100) or (y < -100) or x.isNaN() or y.isNaN()) continue

                withContext(Dispatchers.Main) {
                    Log.i("BLUETOOTH", r1.toString() + " " + r2.toString() + " " + x.toString() + " " + y.toString() + "\n")
                    bluetoothStatus.text = "r1:%.2f, r2:%.2f, x:%.2f, y:%.2f".format(r1, r2, x, y)
                }

                positions.add(Pair(x, y))
                if (positions.size > 10) positions.removeAt(0) // keep 0.5 sec of data

                if (positions.size >= 2) {
                    // val (x1, y1) = positions.first()
                    val (x2, y2) = positions.last()

                    val smoothed = smoothVectors(computeStepVectors(positions))
                    val avgDx = smoothed.map { it.first }.average()
                    val avgDy = smoothed.map { it.second }.average()
                    val dx = avgDx
                    val dy = avgDy
//                    val dx = (x2 - x1) / positions.size
//                    val dy = (y2 - y1) / positions.size

//                    val futureX = x2 + dx * 20 * 1.5 // 1.5 seconds
//                    val futureY = y2 + dy * 20 * 1.5
                    val t = editTextT.text.toString().toDoubleOrNull() ?: 1.0
                    val steps = (t * 1000 / INTERVAL_MILLIS).toDouble()
                    val futureX = x2 + dx * steps
                    val futureY = y2 + dy * steps

                    val distance = distanceToOriginFromPath(x2, y2, futureX, futureY)
                    if (distance < r)
                        collisionCount += 1
                    else if (collisionCount < CONSECUTIVE_COUNTS)
                        collisionCount = 0

                    withContext(Dispatchers.Main) {
                        if (collisionCount >= CONSECUTIVE_COUNTS) {
                            resultText.text = "⚠️ WARNING: \nCollision Likely!"
                            resultText.setBackgroundColor(getColor(android.R.color.holo_red_dark))
                            playWarningBeepRepeatedly()
                        } else {
                            resultText.text = "✅ SAFE"
                            resultText.setBackgroundColor(getColor(android.R.color.holo_green_dark))
                            stopWarningBeep()
                        }
                    }
                }
                delay(INTERVAL_MILLIS)
            }
        }
    }

    private fun stopCrashDetection() {
        crashDetectionJob?.cancel()
        stopWarningBeep()
        crashDetectionJob = null
        buttonStart.text = "Start Crash Detection"
        resultText.text = ""
        resultText.setBackgroundColor(Color.parseColor("#888888"))
    }

    private fun trilateration(r1: Double, r2: Double, w: Double, offset1: Double = 0.1, offset2: Double = 0.1): Pair<Double, Double> {
        val r1m = r1 / 100 - offset1
        val r2m = r2 / 100 - offset2
        return try {
            val alpha = acos((r1m * r1m + w * w - r2m * r2m) / (2 * r1m * w))
            val beta = acos((r2m * r2m + w * w - r1m * r1m) / (2 * r2m * w))
            val x = -w / ((1 / tan(alpha)) + (1 / tan(beta)))
            val y = sqrt(max(r2m * r2m - x * x, 0.0)) - w / 2
            Pair(x, y)
        } catch (e: Exception) {
            Pair(-500.0, -500.0)
        }
    }

    private fun distanceToOriginFromPath(x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val px = x2 - x1
        val py = y2 - y1
        val norm = px * px + py * py
        val u = if (norm != 0.0) -(x1 * px + y1 * py) / norm else 0.0
        val closestX = x1 + u * px
        val closestY = y1 + u * py
        return sqrt(closestX * closestX + closestY * closestY)
    }

    fun playWarningBeep(durationMs: Int = 500) {
        val toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, durationMs)
    }

    fun playWarningBeepRepeatedly() {
        if (warningBeepJob != null) return // already running

        warningBeepJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                playWarningBeep()
                delay(1000L) // beep every 1 second
            }
        }
    }

    fun stopWarningBeep() {
        warningBeepJob?.cancel()
        warningBeepJob = null
    }

    private fun startBluetoothReading(inputStream: InputStream) {
        CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(1024)
            var bytes: Int
            while (true) {
                try {
                    bytes = inputStream.read(buffer)
                    val incoming = String(buffer, 0, bytes).trim()
                    val parsed = parseSensorData(incoming)
                    val ema = 0.4
                    parsed?.let { (r1, r2) ->
                        if ((r1 < 60000) and (r2 < 60000)) {
                            r1Latest = r1*ema + r1Latest*(1-ema)
                            r2Latest = r2*ema + r2Latest*(1-ema)
                        }
                    }
                } catch (e: Exception) {
                    break // handle disconnect gracefully
                }
            }
        }
    }

    private fun parseSensorData(input: String): Pair<Double, Double>? {
        Log.i("BLUETOOTH", input)
        return try {
            val parts = input.split(",")
            val r1 = parts[0].split(":")[1].toDouble()
            val r2 = parts[1].split(":")[1].toDouble()
            Pair(r1, r2)
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun scanBluetoothDevices() {
        if (!bluetoothAdapter!!.isEnabled) {
            bluetoothStatus.text = "Bluetooth is disabled"
            return
        }

        val pairedDevices = bluetoothAdapter!!.bondedDevices
        deviceNames.clear()

        if (pairedDevices.isNotEmpty()) {
            for (device in pairedDevices) {
                deviceNames.add("${device.name} - ${device.address}")
            }
            bluetoothStatus.text = "Paired Devices:"
        } else {
            bluetoothStatus.text = "No paired devices found"
        }

        deviceAdapter.notifyDataSetChanged()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(address: String) {
        val device = bluetoothAdapter?.getRemoteDevice(address)
        val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // SPP UUID

        CoroutineScope(Dispatchers.IO).launch {
            try {
                bluetoothSocket = device?.createRfcommSocketToServiceRecord(uuid)
                bluetoothAdapter?.cancelDiscovery()
                bluetoothSocket?.connect()

                withContext(Dispatchers.Main) {
                    bluetoothStatus.text = "Connected to $address"
                    Toast.makeText(this@MainActivity, "Bluetooth connected!", Toast.LENGTH_SHORT).show()
                }

                inputStream = bluetoothSocket?.inputStream
                inputStream?.let { startBluetoothReading(it) }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    bluetoothStatus.text = "Connection failed"
                    Toast.makeText(this@MainActivity, "Connection failed", Toast.LENGTH_SHORT).show()
                }
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToRaspberryPiDirectly() {
        val rpiMacAddress = "B8:27:EB:2F:5E:19" // <- replace with your Pi’s actual MAC address
        val device = bluetoothAdapter?.getRemoteDevice(rpiMacAddress)
        val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // SPP UUID

        CoroutineScope(Dispatchers.IO).launch {
            try {
                bluetoothSocket = device?.createRfcommSocketToServiceRecord(uuid)
                bluetoothAdapter?.cancelDiscovery()
                bluetoothSocket?.connect()

                withContext(Dispatchers.Main) {
                    bluetoothStatus.text = "Connected to RPi"
                    Toast.makeText(this@MainActivity, "Connected to Raspberry Pi!", Toast.LENGTH_SHORT).show()
                }

                inputStream = bluetoothSocket?.inputStream
                inputStream?.let { startBluetoothReading(it) }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    bluetoothStatus.text = "Connection failed"
                    Toast.makeText(this@MainActivity, "Failed to connect", Toast.LENGTH_SHORT).show()
                }
                e.printStackTrace()
            }
        }
    }
}
