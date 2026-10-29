package com.swiftbrowser.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.swiftbrowser.core.L

/**
 * Documents and Files as icons of their own in the phone's app list (2.7), next to Swift's. They
 * are aliases of those screens (AndroidManifest.xml); Settings can hide them.
 */
object AppIcons {
    private val ALIASES = listOf("com.swiftbrowser.ui.DocumentsApp", "com.swiftbrowser.ui.FilesApp")

    fun shown(ctx: Context): Boolean = try {
        ctx.packageManager.getComponentEnabledSetting(ComponentName(ctx.packageName, ALIASES[0])) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } catch (_: Exception) {
        true
    }

    fun show(ctx: Context, on: Boolean) {
        for (alias in ALIASES) {
            try {
                ctx.packageManager.setComponentEnabledSetting(
                    ComponentName(ctx.packageName, alias),
                    if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            } catch (e: Exception) {
                L.w("Couldn't ${if (on) "show" else "hide"} the $alias icon", e)
            }
        }
    }
}
