package il.co.vibit.tabletradio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** אחרי הדלקה / עדכון — ממשיך לנגן את התחנה האחרונה ופותח את המסך. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.wantPlaying(context) && Prefs.lastStation(context) != null) {
            RadioService.send(context, RadioService.ACTION_RESUME)
        }
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {}
    }
}
