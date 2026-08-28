package com.filedrop.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var etIp: EditText
    private lateinit var btnSelectFile: Button
    private lateinit var btnSend: Button
    private lateinit var tvStatus: TextView
    private lateinit var lvDevices: ListView
    private lateinit var tvEmpty: TextView

    private val deviceList = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private val deviceSet = ConcurrentHashMap.newKeySet<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var socket: DatagramSocket? = null
    private var isListening = false

    private var selectedFilePath: String? = null

    companion object {
        private const val PORT = 12346
        private const val BUFFER_SIZE = 1024
        private const val PREFIX = "FILEDROP_SERVER:"
        private const val TCP_PORT = 12345
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etIp = findViewById(R.id.etIp)
        btnSelectFile = findViewById(R.id.selectFileBtn)
        btnSend = findViewById(R.id.sendBtn)
        tvStatus = findViewById(R.id.tvStatus)
        lvDevices = findViewById(R.id.deviceListView)
        tvEmpty = findViewById(R.id.tvEmpty)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceList)
        lvDevices.adapter = adapter
        lvDevices.visibility = android.view.View.GONE
        tvEmpty.visibility = android.view.View.GONE

        // 点击设备列表自动填入 IP
        lvDevices.setOnItemClickListener { _, _, position, _ ->
            val ip = deviceList[position]
            etIp.setText(ip)
            etIp.setSelection(ip.length)
            tvStatus.text = "已选择：$ip"
        }

        // 选择文件
        btnSelectFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "*/*"
            startActivityForResult(intent, 1000)
        }

        // 发送文件
        btnSend.setOnClickListener {
            val ip = etIp.text.toString().trim()
            val filePath = selectedFilePath

            if (ip.isEmpty()) {
                Toast.makeText(this, "请填写或选择电脑 IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (filePath == null) {
                Toast.makeText(this, "请先选择文件", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnSend.isEnabled = false
            tvStatus.text = "正在连接 $ip ..."

            thread {
                try {
                    sendFile(ip, TCP_PORT, filePath)
                    handler.post {
                        tvStatus.text = "发送完成！"
                        btnSend.isEnabled = true
                        Toast.makeText(this, "文件传输成功", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    handler.post {
                        tvStatus.text = "发送失败：${e.message}"
                        btnSend.isEnabled = true
                        Toast.makeText(this, "错误：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    e.printStackTrace()
                }
            }
        }

        startDiscovery()
    }

    // 处理文件选择结果
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1000 && resultCode == RESULT_OK) {
            val uri = data?.data
            uri?.let {
                selectedFilePath = getRealPathFromUri(it)
                val fileName = getFileNameFromUri(it)
                tvStatus.text = "已选择：$fileName"
                Toast.makeText(this, "已选择：$fileName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 把 Uri 转成真实路径
    private fun getRealPathFromUri(uri: android.net.Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val fileName = getFileNameFromUri(uri)
            val cacheFile = File(cacheDir, fileName)
            inputStream?.use { input ->
                java.io.FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
            cacheFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun getFileNameFromUri(uri: android.net.Uri): String {
        var fileName = "unknown"
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    fileName = it.getString(nameIndex)
                }
            }
        }
        return fileName
    }

    // 核心发送函数
    private fun sendFile(ip: String, port: Int, filePath: String) {
        val file = File(filePath)
        val fileName = file.name
        val fileNameBytes = fileName.toByteArray(Charsets.UTF_8)

        // 带超时的 Socket 连接（5秒）
        val socket = Socket()
        socket.connect(InetSocketAddress(ip, port), 5000)
        val output = socket.getOutputStream()

        // 1. 发送文件名长度（4字节）
        val lenBuffer = ByteBuffer.allocate(4).putInt(fileNameBytes.size)
        output.write(lenBuffer.array())
        output.flush()

        // 2. 发送文件名
        output.write(fileNameBytes)
        output.flush()

        // 3. 发送文件内容
        val fileInput = FileInputStream(file)
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (fileInput.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
        }
        fileInput.close()
        output.close()
        socket.close()
    }

    // UDP 广播监听
    private fun startDiscovery() {
        if (isListening) return
        isListening = true
        tvStatus.text = "正在扫描局域网设备..."
        Thread {
            try {
                socket = DatagramSocket(PORT).apply {
                    reuseAddress = true
                    broadcast = true
                }
                val buffer = ByteArray(BUFFER_SIZE)
                while (isListening) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    val message = String(packet.data, 0, packet.length)
                    if (message.startsWith(PREFIX)) {
                        val parts = message.removePrefix(PREFIX).split(":")
                        if (parts.size >= 1) {
                            val ip = parts[0]
                            if (deviceSet.add(ip)) {
                                handler.post {
                                    deviceList.add(ip)
                                    adapter.notifyDataSetChanged()
                                    updateVisibility()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    tvStatus.text = "扫描已停止"
                    isListening = false
                }
            }
        }.start()
    }

    private fun updateVisibility() {
        if (deviceList.isEmpty()) {
            lvDevices.visibility = android.view.View.GONE
            tvEmpty.visibility = android.view.View.VISIBLE
            tvStatus.text = "未发现电脑，请确保电脑已运行广播程序"
        } else {
            lvDevices.visibility = android.view.View.VISIBLE
            tvEmpty.visibility = android.view.View.GONE
            tvStatus.text = "发现 ${deviceList.size} 台设备"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        socket?.close()
    }
}