package com.example.wavplayer

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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

    private var pendingAction: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                pendingAction?.invoke()
                pendingAction = null
            } else {
                Toast.makeText(this, "File access denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        resultsList.add("Build Time: ${BuildConfig.BUILD_TIME}")
        setContent {
            WavplayerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(16.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                checkAndRequestPermission { startAutoTestStream() }
                            }) {
                                Text("StreamTest")
                            }
                            Button(onClick = {
                                checkAndRequestPermission { startAutoTestStatic() }
                            }) {
                                Text("StaticTest")
                            }
                            Button(onClick = { resultsList.clear() }) {
                                Text("Clear")
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

    private fun checkAndRequestPermission(runAfter: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingAction = runAfter
                permissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                runAfter()
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingAction = runAfter
                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            } else {
                runAfter()
            }
        }
    }

    private fun startAutoTestStream() {
        thread {
            fileList.forEach { path ->
                runOnUiThread { resultsList.add("Stream testing: ${File(path).name}") }
                repeat(5) { round ->
                    val result = playWavStream(path)
                    System.gc();
                    runOnUiThread { resultsList.add("${round + 1} $result") }
                }
            }
        }
    }

    private fun startAutoTestStatic() {
        thread {
            fileList.forEach { path ->
                runOnUiThread { resultsList.add("Static testing: ${File(path).name}") }
                repeat(5) { round ->
                    val result = playWavStatic(path)
                    System.gc();
                    runOnUiThread { resultsList.add("${round + 1} $result") }
                }
            }
        }
    }

    @SuppressLint("DefaultLocale")
    private fun playWavStream(filePath: String): String {
        val fis = FileInputStream(File(filePath))
        val buffer4 = ByteArray(4)
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
        val bufferSize = 2*AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        val audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize,
            AudioTrack.MODE_STREAM
        )

        var startTime: Long = 0
        val audioBuffer = ByteArray(bufferSize)
        var readBytes: Int
        while (fis.read(audioBuffer).also { readBytes = it } > 0) {
            audioTrack.write(audioBuffer, 0, readBytes)
            if (startTime==0L) {
                audioTrack.play()
                while (audioTrack.playbackHeadPosition == 0) {
                    //Thread.sleep(1)
                }
                startTime = System.nanoTime()
            }
        }
        while (audioTrack.playbackHeadPosition < totalSamples) {
            Thread.sleep(1)
        }
        val endTime = System.nanoTime()

        audioTrack.stop()
        audioTrack.release()
        fis.close()

        val actualSec = (endTime - startTime) / 1_000_000_000.0
        val ppm = (actualSec - expectedSec) / expectedSec * 1_000_000
        return String.format("act:%.3fs set:%.3fs diff:%.2fppm", actualSec, expectedSec, ppm)
    }

    @SuppressLint("DefaultLocale")
    private fun playWavStatic(filePath: String): String {
        val fis = FileInputStream(File(filePath))
        val buffer4 = ByteArray(4)
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

        val pcmData = ByteArray(dataSize)
        fis.read(pcmData)
        fis.close()

        val audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelConfig,
            audioFormat,
            pcmData.size,
            AudioTrack.MODE_STATIC
        )
        audioTrack.write(pcmData, 0, pcmData.size)
        audioTrack.play()

        while (audioTrack.playbackHeadPosition == 0) {
           //Thread.sleep(1)
        }

        val startTime = System.nanoTime()

        while (audioTrack.playbackHeadPosition < totalSamples) {
            Thread.sleep(1)
        }
        val endTime = System.nanoTime()

        audioTrack.stop()
        audioTrack.release()

        val actualSec = (endTime - startTime) / 1_000_000_000.0
        val ppm = (actualSec - expectedSec) / expectedSec * 1_000_000
        return String.format("act:%.3fs set:%.3fs diff:%.2fppm", actualSec, expectedSec, ppm)
    }
}