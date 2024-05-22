package net.ankio.bluetooth.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import net.ankio.bluetooth.R
import net.ankio.bluetooth.data.BluetoothData
import net.ankio.bluetooth.utils.ByteUtils
import net.ankio.bluetooth.utils.SpUtils
import net.ankio.bluetooth.utils.WebdavUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class SendWebdavServer : Service() {
    companion object {
        var isRunning = false
        private const val CHANNEL_ID = "ForegroundServiceChannel"
        private var reportMac = ""
        private var reportTime = 0L
        private var reportData = ""
    }
    private val tag = "BluetoothScanService"
    private var deviceAddress = SpUtils.getString("pref_mac2", "") // 指定的蓝牙设备MAC地址
    private var deviceCompany = SpUtils.getString("pref_company", "") // 指定的蓝牙设备公司
    private val scanInterval: Long = 1 * 60 * 1000 // 10 minutes

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var scanCallback: ScanCallback

    private lateinit var wakeLock: WakeLock

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        startScan()
        return START_STICKY
    }
    override fun onCreate() {

        super.onCreate()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock =
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, SendWebdavServer::class.java.name)
        wakeLock.acquire()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_bluetooth_scan)
            .setContentText(getString(R.string.server_name))
            .build()

        startForeground(1, notification)
        if(deviceAddress==="" && deviceCompany===""){
            stopSelf()
        }
        bluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

        //扫描结果回调
        scanCallback = object : ScanCallback() {
            override fun onScanFailed(errorCode: Int) {
                //errorCode=1;Fails to start scan as BLE scan with the same settings is already started by the app.
                //errorCode=2;Fails to start scan as app cannot be registered.
                //errorCode=3;Fails to start scan due an internal error
                //errorCode=4;Fails to start power optimized scan as this feature is not supported
                Log.i(tag, "onScanFailed callback---->$errorCode")
            }
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val scanRecord = result.scanRecord?.bytes ?: return
                if(deviceCompany != "") {
                    val companyName = BluetoothData(this@SendWebdavServer).parseManufacturerData(scanRecord)?:""
                    if(companyName != deviceCompany) {
                        return
                    }
                }
                if(deviceAddress != "") {
                    if(result.device.address != deviceAddress) {
                        return
                    }
                }
                val tempData = ByteUtils.bytesToHexString(scanRecord)?:""
                val rawData = "0x" + changeData(tempData.uppercase())
                if(reportMac == result.device.address) {
                    if(Date().time - reportTime < 1000) {
                        Log.i(tag, getTimeString(reportTime) + " Last report: ${result.device.address}")
                        return
                    }
                    if(reportData == rawData) {
                        Log.i(tag, getTimeString(reportTime) + " Same report: ${result.device.address}")
                        return
                    }
                }
                Log.i(tag, "Found device: ${result.device.address}")
                reportMac = result.device.address
                reportTime = Date().time
                reportData = rawData
                Thread(Runnable Thread@{
                    try {
                        WebdavUtils(
                            SpUtils.getString("webdav_username", ""),
                            SpUtils.getString("webdav_password", "")
                        ).sendToServer(
                            net.ankio.bluetooth.bluetooth.BluetoothData(
                                rawData,
                                result.device.address,
                                result.rssi.toString(),
                                getTimeString(reportTime)
                            )
                        )
                    }catch (e:Exception){
                        Log.i(tag, "WebdavException: " + e.message)
                    }
                }).start()
            }
        }
    }

    private fun getTimeString(time: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return formatter.format(time)
    }

    private fun changeData(data: String): String {
        var realLen = 0
        var start = 0
        var end = 2
        while (realLen < data.length) {
            val lenStr = data.substring(start, end)
            val len = lenStr.toInt(16)
            if (len == 0) {
                break
            }
            start += len * 2 + 2
            end += len * 2 + 2
            realLen += (len + 1) * 2
        }
        return data.substring(0, realLen)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        wakeLock.release();
        super.onDestroy()
        stopScan()
        Log.i(tag, "Server destroy")
        isRunning = false
    }

    private fun startScan() {
        if (bluetoothAdapter.isEnabled) {
            Log.i(tag, "Start scanning...")
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(tag, "start scan check failed")
            }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            val filterList: MutableList<ScanFilter> = ArrayList()
            if(deviceAddress != "") {
                filterList.add(ScanFilter.Builder().setDeviceAddress(deviceAddress).build())
            } else {
                filterList.add(ScanFilter.Builder().build())
            }
            bluetoothAdapter.bluetoothLeScanner.startScan(filterList, settings, scanCallback)
            Handler(Looper.getMainLooper()).postDelayed({
                stopScan()
                Thread.sleep(100)
                startScan()
            },scanInterval )
        } else {
            Log.e(tag, getString(R.string.unsupported_bluetooth))
            stopSelf()
        }
    }




    private fun stopScan() {
        Log.i(tag, "Stop scanning")
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(tag, "stop scan check failed")
        }
        try{
            bluetoothAdapter.bluetoothLeScanner.stopScan(scanCallback)
        }catch (_:Exception){

        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_ID,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = CHANNEL_ID
            val manager = getSystemService(
                NotificationManager::class.java
            )
            manager.createNotificationChannel(channel)
        }
    }
}