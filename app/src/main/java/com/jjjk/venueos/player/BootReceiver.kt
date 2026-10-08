package com.jjjk.venueos.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // MY_PACKAGE_REPLACED: a remote update (ApkUpdater) just killed and
        // replaced this app - bring the player straight back up the same way
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Starting MainActivity directly from here (a background
            // BroadcastReceiver) hit Android 10+'s background-activity-start
            // restriction on real hardware - BOOT_COMPLETED receivers are
            // documented as exempt in AOSP, but this vendor firmware enforces
            // it anyway, so the launch silently never happened and the box
            // just sat at the stock launcher. A running foreground service
            // has a much stronger exemption, so AgentService triggers the
            // launch itself once startForeground() has actually taken effect
            // (see AgentService.onStartCommand) instead of doing it here.
            val svc = Intent(context, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        }
    }
}
