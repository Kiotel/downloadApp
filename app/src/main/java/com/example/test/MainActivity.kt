package com.example.test

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

	private val channelId = "custom_download_channel"
	private val notificationId = 1

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		// Create notification channel for Android O and above
		createNotificationChannel()

		val downloadButton: Button = findViewById(R.id.downloadButton)
		downloadButton.setOnClickListener {
			startCustomDownload()
		}
	}

	private fun createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val name = "Download Channel"
			val descriptionText = "Channel for file download notifications"
			val importance = NotificationManager.IMPORTANCE_LOW
			val channel = NotificationChannel(channelId, name, importance).apply {
				description = descriptionText
			}

			val notificationManager: NotificationManager =
				getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
			notificationManager.createNotificationChannel(channel)
		}
	}

	@SuppressLint("SetTextI18n", "MissingPermission")
	private fun startCustomDownload() {
		val url =
			"https://s216vlx.storage.yandex.net/rdisk/7f350f2f4d4d17ba272db6a3dd39ce079bdc11c1daaa744e8b7ce8a7375eea81/66f690b2/fKqInKw3d7bLFOeFnMGnhA8Q6PeBKp799W0q9l403OQK9zSryjagcvg0ekuKKajZi350VkuiCcy9j6sQxQ9ZGG9Vz1zOHGONThAu08wy7UCr8npumZHI4midPdWhecNq?uid=0&filename=%D0%BF%D1%80%D0%B5%D0%B4%D0%BC%D0%B5%D1%82%D1%8B%20%E2%80%94%20%D0%BA%D0%BE%D0%BF%D0%B8%D1%8F.zip&disposition=attachment&hash=cGcxDFrzTqWw0riwhSCQH00oeZmUlFsp0W31R0RaMbM3EMM8pe4kQtpcSDp2HBKJEkI0e0it/P53JjBKdrjFug%3D%3D&limit=0&content_type=application%2Fzip&owner_uid=1130000067180474&fsize=554754887&hid=a99b8d2f6f3cb94d294fc2dcae3a753f&media_type=compressed&tknv=v2&ts=62317c9601080&s=d211f24b3a608954aa7212c73d6765ea9cdb1358f9892a6e7a2873771cf69064&pb=U2FsdGVkX18YClUcGn1EIR-QSIsqVuH4cvNK22k-kIiB37s60ccIfG446fALt7zXojyQjdB4n4UbI8b76YS9_YxRmo1Awi1qL6KpMVspWELqLiPWiJE9E2k3ETCeSbzI" // replace with actual file URL

		// Create notification builder for the initial notification
		val builder = NotificationCompat.Builder(this, channelId)
			.setSmallIcon(android.R.drawable.stat_sys_download)
			.setContentTitle("Download in progress")
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setOngoing(true) // prevent it from being swiped away

		val notificationManager = NotificationManagerCompat.from(this)
		notificationManager.notify(notificationId, builder.build())

		// Use coroutines to handle download on background thread
		CoroutineScope(Dispatchers.IO).launch {
			val startTime = System.currentTimeMillis()

			val success = downloadFile(url) { progress, fileSize, downloadedBytes ->
				val elapsedTime = System.currentTimeMillis() - startTime
				val downloadSpeed = downloadedBytes / (elapsedTime / 1000.0) // bytes per second

				// Calculate remaining time (seconds)
				val remainingBytes = fileSize - downloadedBytes
				val remainingTime = if (downloadSpeed > 0) (remainingBytes / downloadSpeed).toInt() else 0

				// Convert remaining time to hh:mm:ss format
				val eta = formatTime(remainingTime)

				// Convert bytes to KB or MB for readability
				val downloadedMB = downloadedBytes / (1024 * 1024)
				val totalMB = fileSize / (1024 * 1024)

				// Update progress in notification with file size and estimated time
				builder.setProgress(100, progress, false)
					.setContentText("Downloaded: $downloadedMB MB / $totalMB MB, ETA: $eta")

				notificationManager.notify(notificationId, builder.build())
			}

			// Download complete
			withContext(Dispatchers.Main) {
				if (success) {
					builder.setContentText("Download complete")
						.setProgress(0, 0, false)
						.setOngoing(false)
						.setSmallIcon(android.R.drawable.stat_sys_download_done)

					notificationManager.notify(notificationId, builder.build())
					delay(2000) // Optional delay before hiding notification (2 seconds)
				}

				// Cancel the notification after download is complete or failed
				notificationManager.cancel(notificationId)
			}
		}
	}


	private fun downloadFile(
		urlString: String,
		onProgressUpdate: (progress: Int, fileSize: Long, downloadedBytes: Long) -> Unit,
	): Boolean {
		return try {
			val url = URL(urlString)
			val connection: HttpURLConnection = url.openConnection() as HttpURLConnection
			connection.connect()

			if (connection.responseCode != HttpURLConnection.HTTP_OK) {
				return false // Server returned HTTP error
			}

			// Get the file size from the Content-Length header
			val fileLength = connection.contentLength.toLong()

			// Get the file name from the Content-Disposition header or fallback to the URL's file name
			val contentDisposition = connection.getHeaderField("Content-Disposition")
			val fileName = if (contentDisposition != null && contentDisposition.contains("filename=")) {
				contentDisposition.substringAfter("filename=").replace("\"", "")
			} else {
				urlString.substring(urlString.lastIndexOf('/') + 1)
			}

			// Create output file in the Downloads directory
			val outputFile =
				"${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/$fileName"
			val input = BufferedInputStream(connection.inputStream)
			val output = FileOutputStream(outputFile)

			val data = ByteArray(1024)
			var total: Long = 0
			var count: Int

			while (input.read(data).also { count = it } != -1) {
				total += count
				output.write(data, 0, count)

				// Update progress and file size in notification
				val progress = (total * 100 / fileLength).toInt()
				onProgressUpdate(progress, fileLength, total)
			}

			output.flush()
			output.close()
			input.close()

			true // Success
		} catch (e: Exception) {
			e.printStackTrace()
			false // Failure
		}
	}
}

@SuppressLint("DefaultLocale")
private fun formatTime(seconds: Int): String {
	val hours = seconds / 3600
	val minutes = (seconds % 3600) / 60
	val secs = seconds % 60
	return String.format("%02d:%02d:%02d", hours, minutes, secs)
}

