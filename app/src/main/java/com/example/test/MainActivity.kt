package com.example.test

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
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
		val urlEditText: EditText = findViewById(R.id.urlEditText) // Получаем ссылку из EditText

		downloadButton.setOnClickListener {
			val url = urlEditText.text.toString() // Получаем текст из EditText
			if (url.isNotEmpty()) {
				startCustomDownload(url) // Передаем URL в метод загрузки
			} else {
				// Обработка случая, когда URL пустой (например, показать сообщение об ошибке)
				Toast.makeText(this, "Пожалуйста, введите URL", Toast.LENGTH_SHORT).show()
			}
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
	private fun startCustomDownload(url: String) { // Изменяем метод для принятия URL
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
				return false // Сервер вернул ошибку HTTP
			}

			// Получаем размер файла из заголовка Content-Length
			val fileLength = connection.contentLength.toLong()

			// Получаем имя файла из заголовка Content-Disposition или используем имя файла из URL
			val contentDisposition = connection.getHeaderField("Content-Disposition")
			val fileName = if (contentDisposition != null && contentDisposition.contains("filename=")) {
				contentDisposition.substringAfter("filename=").replace("\"", "")
			} else {
				urlString.substring(urlString.lastIndexOf('/') + 1)
			}

			// Создаем выходной файл в директории загрузок
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

				// Обновляем прогресс и размер файла в уведомлении
				val progress = (total * 100 / fileLength).toInt()
				onProgressUpdate(progress, fileLength, total)
			}

			output.flush()
			output.close()
			input.close()

			true // Успех
		} catch (e: Exception) {
			e.printStackTrace()
			false // Ошибка
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
