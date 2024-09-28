package com.example.test

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class CancelDownloadReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent?) {
		// Notify that the download has been canceled
		Toast.makeText(context, "Download canceled", Toast.LENGTH_SHORT).show()

		// Cancel the ongoing download job
		GlobalScope.launch {
			MainActivity.downloadJob?.cancel() // Cancel the download job

			// Cancel the notification
			val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
			notificationManager.cancel(MainActivity.NOTIFICATION_ID) // Use the same notificationId
		}
	}
}
