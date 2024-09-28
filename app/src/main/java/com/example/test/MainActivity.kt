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
		var downloadJob: Job? = null
		const val NOTIFICATION_ID = 1
		const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1
	}

	private val channelId = "custom_download_channel"
	private val notificationId = 1
	private var outputFilePath: String? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		checkNotificationPermission()
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
			val importance = NotificationManager.IMPORTANCE_LOW
			val channel = NotificationChannel(channelId, name, importance).apply {
				description = descriptionText
			}

			val notificationManager: NotificationManager =
				getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
			notificationManager.createNotificationChannel(channel)
		}
	}

	@SuppressLint("SetTextI18n")
	private fun startCustomDownload(url: String) {
		if (downloadJob?.isActive == true) {
			Toast.makeText(this, "Скачивание уже идет, дождитесь завершения", Toast.LENGTH_SHORT).show()
			return
		}

		val cancelIntent = Intent(this, CancelDownloadReceiver::class.java)
		val cancelPendingIntent: PendingIntent = PendingIntent.getBroadcast(
			this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)

		downloadJob = CoroutineScope(Dispatchers.IO).launch {
			val startTime = System.currentTimeMillis()

			val fileName = getFileName(url)
			if (fileName == null) {
				withContext(Dispatchers.Main) {
					Toast.makeText(this@MainActivity, "Не удалось получить имя файла", Toast.LENGTH_SHORT)
						.show()
				}
				return@launch
			}

			val builder = NotificationCompat.Builder(this@MainActivity, channelId)
				.setSmallIcon(android.R.drawable.stat_sys_download)
				.setContentTitle(fileName)
				.setPriority(NotificationCompat.PRIORITY_LOW)
				.setOngoing(true)
				.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отмена", cancelPendingIntent)

			val notificationManager = NotificationManagerCompat.from(this@MainActivity)
			checkNotificationPermission()
			notificationManager.notify(notificationId, builder.build())

			val success = downloadFile(url) { progress, fileSize, downloadedBytes ->
				val elapsedTime = System.currentTimeMillis() - startTime
				val downloadSpeed = downloadedBytes / (elapsedTime / 1000.0)

				val remainingBytes = fileSize - downloadedBytes
				val remainingTime = if (downloadSpeed > 0) (remainingBytes / downloadSpeed).toInt() else 0

				val eta = formatTime(remainingTime)

				val downloadedMB = downloadedBytes / (1024 * 1024)
				val totalMB = fileSize / (1024 * 1024)

				builder.setProgress(100, progress, false)
					.setContentText("Скачано: $downloadedMB MB / $totalMB MB, Осталось: $eta")

				notificationManager.notify(notificationId, builder.build())
			}

			withContext(Dispatchers.Main) {
				if (success) {
					builder.setContentText("Скачивание завершено")
						.setProgress(0, 0, false)
						.setOngoing(false)
						.setSmallIcon(android.R.drawable.stat_sys_download_done)

					notificationManager.notify(notificationId, builder.build())
					delay(2000)
				} else {
					Toast.makeText(this@MainActivity, "Загрузка прервана", Toast.LENGTH_SHORT).show()
				}

				notificationManager.cancel(notificationId)
				downloadJob = null // Allow new download after the current one finishes
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
				return false
			}

			val fileLength = connection.contentLength.toLong()

			val contentDisposition = connection.getHeaderField("Content-Disposition")
			val fileName = if (contentDisposition != null && contentDisposition.contains("filename=")) {
				contentDisposition.substringAfter("filename=").replace("\"", "")
			} else {
				urlString.substring(urlString.lastIndexOf('/') + 1)
			}

			// Определение директории для загрузки и получение уникального имени файла
			val downloadsDirectory =
				Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
			val uniqueFileName = getUniqueFileName(fileName, downloadsDirectory)

			outputFilePath = "${downloadsDirectory}/$uniqueFileName"
			val input = BufferedInputStream(connection.inputStream, 16 * 1024) // 16KB buffer
			val output = withContext(Dispatchers.IO) {
				FileOutputStream(outputFilePath)
			}

			val data = ByteArray(1024)
			var total: Long = 0
			var count: Int

			var lastReportedProgress = 0

			while (withContext(Dispatchers.IO) {
					input.read(data)
				}.also { count = it } != -1) {
				if (downloadJob?.isCancelled == true) {
					withContext(Dispatchers.IO) {
						output.close()
						input.close()
					}

					deleteDownloadedFile(outputFilePath)
					return false
				}

				total += count
				withContext(Dispatchers.IO) {
					output.write(data, 0, count)
				}

				val progress = (total * 100 / fileLength).toInt()

				if (progress - lastReportedProgress >= 1) {
					lastReportedProgress = progress
					onProgressUpdate(progress, fileLength, total)
				}
			}

			withContext(Dispatchers.IO) {
				output.flush()
				output.close()
				input.close()
			}

			true // Успешная загрузка
		} catch (e: Exception) {
			e.printStackTrace()
			deleteDownloadedFile(outputFilePath)
			false // Ошибка при загрузке
		}
	}


	private fun deleteDownloadedFile(filePath: String?) {
		if (!filePath.isNullOrEmpty()) {
			val file = File(filePath)
			if (file.exists()) {
				file.delete()
			}
		}
		downloadJob = null
	}


	@SuppressLint("DefaultLocale")
	private fun formatTime(seconds: Int): String {
		val hours = seconds / 3600
		val minutes = (seconds % 3600) / 60
		val secs = seconds % 60
		return String.format("%02d:%02d:%02d", hours, minutes, secs)
	}

	private fun getUniqueFileName(fileName: String, directory: File): String {
		var uniqueFileName = fileName
		var file = File(directory, uniqueFileName)

		var index = 1
		val extensionIndex = fileName.lastIndexOf('.')
		val baseName = if (extensionIndex > 0) fileName.substring(0, extensionIndex) else fileName
		val extension = if (extensionIndex > 0) fileName.substring(extensionIndex) else ""

		while (file.exists()) {
			uniqueFileName = "$baseName($index)$extension"
			file = File(directory, uniqueFileName)
			index++
		}

		return uniqueFileName
	}

	private fun getFileName(urlString: String): String? {
		return try {
			val url = URL(urlString)
			val connection: HttpURLConnection = url.openConnection() as HttpURLConnection
			connection.connect()

			if (connection.responseCode != HttpURLConnection.HTTP_OK) {
				null
			} else {
				val contentDisposition = connection.getHeaderField("Content-Disposition")
				val originalFileName =
					if (contentDisposition != null && contentDisposition.contains("filename=")) {
						contentDisposition.substringAfter("filename=").replace("\"", "")
					} else {
						urlString.substring(urlString.lastIndexOf('/') + 1)
					}

				val downloadsDirectory =
					Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
				getUniqueFileName(originalFileName, downloadsDirectory)
			}
		} catch (e: Exception) {
			e.printStackTrace()
			null
		}
	}


}
