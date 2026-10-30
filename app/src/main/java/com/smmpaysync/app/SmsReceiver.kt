package com.smmpaysync.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.util.UUID

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (msgs == null || msgs.isEmpty()) return

        val from = msgs[0].originatingAddress ?: return
        val provider = Providers.byHeader(from) ?: return      // only official headers
        val body = msgs.joinToString("") { it.messageBody ?: "" }

        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                if (!Prefs(app).providerEnabled(provider)) return@Thread
                val p = SmsParsers.parse(provider, body) ?: return@Thread  // OTP / promo => ignored
                Db.get(app).insert(
                    Ev(
                        localId = UUID.randomUUID().toString(),
                        provider = provider.key,
                        trxId = p.trxId,
                        amount = p.amount,
                        sender = p.sender,
                        smsTime = p.smsTime,
                        raw = body,
                        status = St.PENDING,
                        error = null,
                        createdAt = System.currentTimeMillis()
                    )
                )
                Notifier.update(app)
                SyncRunner.trigger(app)      // send right now (process is alive)
                SyncWorker.enqueue(app)      // backup if the process gets killed
                MonitorService.ensure(app)   // make sure the keep-alive service is up
            } catch (e: Exception) {
                // ignore; periodic worker will pick up anything pending
            } finally {
                pending.finish()
            }
        }.start()
    }
}
