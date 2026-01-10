package com.example.wavplayer

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.wavplayer.ui.theme.WavplayerTheme
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private var filePathPending: String = ""

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                showResult(filePathPending)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var result by remember { mutableStateOf("") }
            WavplayerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(16.dp)
                    ) {
                        Button(onClick = {
                            checkAndRequestPermission("/storage/emulated/0/Download/test_60s.wav")
                        }) {
                            Text("播放并测时60s")
                        }
                        Button(onClick = {
                            checkAndRequestPermission("/storage/emulated/0/Download/test_5min.wav")
                        }) {
                            Text("播放并测时5min")
                        }
                        Button(onClick = {
                            checkAndRequestPermission("/storage/emulated/0/Download/test_10min.wav")
                        }) {
                            Text("播放并测时10min")
                        }
                        Button(onClick = {
                            checkAndRequestPermission("/storage/emulated/0/Download/test_20min.wav")
                        }) {
                            Text("播放并测时20min")
                        }
                        if (result.isNotEmpty()) {
                            Text(result)
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermission(filePath: String) {
        filePathPending = filePath
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                showResult(filePath)
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            } else {
                showResult(filePath)
            }
        }
    }

    private fun playWavAndMeasure(filePath: String): String {
        val fis = FileInputStream(File(filePath))
        val buffer4 = ByteArray(4)
        val buffer8 = ByteArray(8)
        fis.read(buffer4)
        fis.read(buffer4)
        fis.read(buffer4)
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataSize = 0
        while (true) {
            val chunkIdBytes = ByteArray(4)
            fis.read(chunkIdBytes)
            val chunkId = String(chunkIdBytes)
            fis.read(buffer4)
            val chunkSize = ByteBuffer.wrap(buffer4).order(ByteOrder.LITTLE_ENDIAN).int
            if (chunkId == "fmt ") {
                val fmtData = ByteArray(chunkSize)
                fis.read(fmtData)
                channels = ByteBuffer.wrap(fmtData, 2, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                sampleRate = ByteBuffer.wrap(fmtData, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                bitsPerSample = ByteBuffer.wrap(fmtData, 14, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            } else if (chunkId == "data") {
                dataSize = chunkSize
                break
            } else {
                fis.skip(chunkSize.toLong())
            }
        }
        val totalSamples = dataSize / (bitsPerSample / 8) / channels
        val expectedSec = totalSamples.toDouble() / sampleRate.toDouble()
        val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val audioFormat = if (bitsPerSample == 8) AudioFormat.ENCODING_PCM_8BIT else AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize,
            AudioTrack.MODE_STREAM
        )
        val startTime = System.currentTimeMillis()
        audioTrack.play()
        val audioBuffer = ByteArray(bufferSize)
        var readBytes: Int
        while (fis.read(audioBuffer).also { readBytes = it } > 0) {
            audioTrack.write(audioBuffer, 0, readBytes)
        }
        audioTrack.stop()
        audioTrack.release()
        fis.close()
        val endTime = System.currentTimeMillis()
        val actualSec = (endTime - startTime) / 1000.0
        val ppm = (actualSec - expectedSec) / expectedSec * 1_000_000
        return String.format(
            "实际播放耗时: %.3f 秒\n理论时长: %.3f 秒\n时钟偏差: %.2f ppm",
            actualSec, expectedSec, ppm
        )
    }

    private fun showResult(filePath: String) {
        thread {
            val result = playWavAndMeasure(filePath)
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("播放统计结果")
                    .setMessage(result)
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    WavplayerTheme {
        Greeting("Android")
    }
}