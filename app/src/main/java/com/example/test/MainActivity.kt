package com.example.test

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

	companion object {
		var downloadJob: Job? = null // Job to manage download coroutine
		const val NOTIFICATION_ID = 1 // Make notificationId static
		const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1
	}

	private val channelId = "custom_download_channel"
	private val notificationId = 1
	private var outputFilePath: String? = null // Variable to store the output file path

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		checkNotificationPermission() // Check for notification permission
		createNotificationChannel()

		val downloadButton: Button = findViewById(R.id.downloadButton)
		val urlEditText: EditText = findViewById(R.id.urlEditText)

		downloadButton.setOnClickListener {
			val url = urlEditText.text.toString()
			if (url.isNotEmpty()) {
				startCustomDownload(url)
			} else {
				Toast.makeText(this, "Пожалуйста, введите URL", Toast.LENGTH_SHORT).show()
			}
		}
	}

	private fun checkNotificationPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
				!= PackageManager.PERMISSION_GRANTED
			) {
				ActivityCompat.requestPermissions(
					this,
					arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
					NOTIFICATION_PERMISSION_REQUEST_CODE
				)
			}
		}
	}

	private fun createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val name = "Download Channel"
			val descriptionText = "Channel for file download notifications"
			val importance = NotificationManager.IMPORTANCE_HIGH
			val channel = NotificationChannel(channelId, name, importance).apply {
				description = descriptionText
			}

			val notificationManager: NotificationManager =
				getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
			notificationManager.createNotificationChannel(channel)
		}
	}

	@SuppressLint("SetTextI18n", "MissingPermission")
	private fun startCustomDownload(url: String) {
		val cancelIntent = Intent(this, CancelDownloadReceiver::class.java)
		val cancelPendingIntent: PendingIntent = PendingIntent.getBroadcast(
			this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)

		val builder = NotificationCompat.Builder(this, channelId)
			.setSmallIcon(android.R.drawable.stat_sys_download)
			.setContentTitle("Download in progress")
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setOngoing(true)
			.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)

		val notificationManager = NotificationManagerCompat.from(this)
		notificationManager.notify(notificationId, builder.build())

		// Start the download in a coroutine
		downloadJob = CoroutineScope(Dispatchers.IO).launch {
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
				} else {
					Toast.makeText(this@MainActivity, "Download failed", Toast.LENGTH_SHORT).show()
				}

				// Cancel the notification after download is complete or failed
				notificationManager.cancel(notificationId)
			}
		}
	}

	private suspend fun downloadFile(
		urlString: String,
		onProgressUpdate: (progress: Int, fileSize: Long, downloadedBytes: Long) -> Unit,
	): Boolean {
		return try {
			val url = URL(urlString)
			val connection: HttpURLConnection =
				withContext(Dispatchers.IO) {
					url.openConnection()
				} as HttpURLConnection
			withContext(Dispatchers.IO) {
				connection.connect()
			}

			if (connection.responseCode != HttpURLConnection.HTTP_OK) {
				return false // Server returned an error
			}

			// Get file size from Content-Length header
			val fileLength = connection.contentLength.toLong()

			// Get filename from Content-Disposition or use the URL
			val contentDisposition = connection.getHeaderField("Content-Disposition")
			val fileName = if (contentDisposition != null && contentDisposition.contains("filename=")) {
				contentDisposition.substringAfter("filename=").replace("\"", "")
			} else {
				urlString.substring(urlString.lastIndexOf('/') + 1)
			}

			// Create output file in the Downloads directory
			outputFilePath =
				"${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/$fileName"
			val input = BufferedInputStream(connection.inputStream, 16 * 1024) // 16KB buffer
			val output = withContext(Dispatchers.IO) {
				FileOutputStream(outputFilePath)
			}

			val data = ByteArray(1024)
			var total: Long = 0
			var count: Int

			// Set a variable for the update interval (in milliseconds)
			val updateInterval = 1000L // Update notification every second

			// Track the last reported progress
			var lastReportedProgress = 0

			while (withContext(Dispatchers.IO) {
					input.read(data)
				}.also { count = it } != -1) {
				// Check for cancellation
				if (downloadJob?.isCancelled == true) {
					withContext(Dispatchers.IO) {
						output.close()
						input.close()
					}
					// Delete the partially downloaded file
					deleteDownloadedFile(outputFilePath) // Delete the file if the download is cancelled
					return false // Return false if the download was cancelled
				}

				total += count
				withContext(Dispatchers.IO) {
					output.write(data, 0, count)
				}

				// Update progress every second
				val progress = (total * 100 / fileLength).toInt()

				// Only update the notification if the progress has changed significantly
				if (progress - lastReportedProgress >= 1) { // Update if at least 1% change
					lastReportedProgress = progress
					onProgressUpdate(progress, fileLength, total)
				}
			}

			withContext(Dispatchers.IO) {
				output.flush()
				output.close()
				input.close()
			}

			true // Success
		} catch (e: Exception) {
			e.printStackTrace()
			// If an error occurs, delete the file if it exists
			deleteDownloadedFile(outputFilePath) // Ensure cleanup in case of error
			false // Error
		}
	}

	private fun deleteDownloadedFile(filePath: String?) {
		if (!filePath.isNullOrEmpty()) {
			val file = File(filePath)
			if (file.exists()) {
				file.delete() // Delete the file
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
}
