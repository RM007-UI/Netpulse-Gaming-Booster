package com.rmgb.netpulse.vpn

import android.content.Intent
import android.net.VpnService
import android.os.IBinder

/** Does not establish a tunnel until a real protocol engine is attached. */
class NetPulseVpnService : VpnService() {
    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
