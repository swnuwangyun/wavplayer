package com.example.wavplayer

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.wavplayer.ui.theme.WavplayerTheme
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private var resultsList = mutableStateListOf<String>()

    private val fileList = listOf(
        "/storage/emulated/0/Download/test_60s.wav",
        "/storage/emulated/0/Download/test_5min.wav",
        "/storage/emulated/0/Download/test_10min.wav",
        "/storage/emulated/0/Download/test_20min.wav"
    )

    private var filePathPending: String = ""

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startAutoTest()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WavplayerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(16.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { checkAndRequestPermission() }) {
                                Text("开始测试")
                            }
                            Button(onClick = { resultsList.clear() }) {
                                Text("清空结果")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(resultsList) { res ->
                                Text(
                                    text = res,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermission() {
        filePathPending = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                startAutoTest()
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            } else {
                startAutoTest()
            }
        }
    }

    private fun startAutoTest() {
        thread {
            fileList.forEach { path ->
                runOnUiThread {
                    resultsList.add("Testing: ${File(path).name}")
                }
                repeat(3) { round ->
                    val result = playWavAndMeasure(path)
                    runOnUiThread {
                        resultsList.add("${round + 1} $result")
                    }
                }
            }
        }
    }

    @SuppressLint("DefaultLocale")
    private fun playWavAndMeasure(filePath: String): String {
        val fis = FileInputStream(File(filePath))
        val buffer4 = ByteArray(4)
        fis.read(buffer4) // RIFF
        fis.read(buffer4) // chunkSize
        fis.read(buffer4) // WAVE

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

        while (audioTrack.playbackHeadPosition < totalSamples) {
            Thread.sleep(1)
        }

        val endTime = System.currentTimeMillis()

        audioTrack.stop()
        audioTrack.release()
        fis.close()

        val actualSec = (endTime - startTime) / 1000.0
        val ppm = (actualSec - expectedSec) / expectedSec * 1_000_000

        return String.format(
            "act:%.3fs set:%.3fs diff:%.2fppm",
            actualSec, expectedSec, ppm
        )
    }
}