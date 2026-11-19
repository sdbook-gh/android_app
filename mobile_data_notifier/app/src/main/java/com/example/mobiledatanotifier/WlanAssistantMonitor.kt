package com.example.mobiledatanotifier

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.Settings

/**
 * 读取 ColorOS / realme WLAN 助理页上会干扰流量的开关。
 * 读 Settings.Global 不需要额外权限；写入需要 WRITE_SECURE_SETTINGS（第三方没有），
 * 因此关闭只能调起系统设置页让用户手动关。
 *
 * 会自动打开「移动数据」开关的只有 [KEY_AUTO_MOBILE]。悬浮窗闪动只看这一项。
 */
object WlanAssistantMonitor {

    const val ACTION_WLAN_ASSISTANT = "wireless.settings.WLAN_ASSISTANT_SETTINGS"
    const val ACTION_SLA = "wireless.settings.SLA_SETTINGS"
    const val ACTION_WIFI_ADVANCED = "android.settings.WIFI_ADVANCED_SETTINGS"

    /** 截图「智能切换移动数据」；Wi-Fi 差时系统会打开移动数据开关。 */
    const val KEY_AUTO_MOBILE = "wifi_auto_change_network"

    data class Flag(
        val key: String,
        val titleRes: Int,
        val detailRes: Int,
        val closeAction: String,
        val enabled: Boolean,
        val causesDataToggle: Boolean
    )

    private class Def(
        val key: String,
        val titleRes: Int,
        val detailRes: Int,
        val closeAction: String,
        val causesDataToggle: Boolean
    )

    private val defs = arrayOf(
        Def(
            KEY_AUTO_MOBILE,
            R.string.wlan_flag_auto_mobile,
            R.string.wlan_flag_auto_mobile_detail,
            ACTION_WLAN_ASSISTANT,
            true
        ),
        Def(
            "wifi_sla_switch_on",
            R.string.wlan_flag_sla,
            R.string.wlan_flag_sla_detail,
            ACTION_SLA,
            false
        )
    )

    fun flags(ctx: Context): List<Flag> {
        val out = ArrayList<Flag>(defs.size)
        for (d in defs) {
            out.add(
                Flag(
                    key = d.key,
                    titleRes = d.titleRes,
                    detailRes = d.detailRes,
                    closeAction = d.closeAction,
                    enabled = isOn(ctx, d.key),
                    causesDataToggle = d.causesDataToggle
                )
            )
        }
        return out
    }

    /** 仅「智能切换移动数据」为开时，悬浮窗才闪、才显示「助理开启」。 */
    fun shouldFlashOverlay(ctx: Context): Boolean = isOn(ctx, KEY_AUTO_MOBILE)

    fun isOn(ctx: Context, key: String): Boolean {
        return try {
            Settings.Global.getInt(ctx.contentResolver, key, 0) != 0
        } catch (_: Exception) {
            try {
                val s = Settings.Global.getString(ctx.contentResolver, key) ?: return false
                s == "1" || s.equals("true", ignoreCase = true)
            } catch (_: Exception) {
                false
            }
        }
    }

    fun registerObserver(ctx: Context, handler: Handler, onChange: () -> Unit): ContentObserver {
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                onChange()
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                onChange()
            }
        }
        ctx.contentResolver.registerContentObserver(Settings.Global.CONTENT_URI, true, obs)
        return obs
    }

    fun unregisterObserver(ctx: Context, observer: ContentObserver?) {
        if (observer == null) return
        try {
            ctx.contentResolver.unregisterContentObserver(observer)
        } catch (_: Exception) {
        }
    }

    /** 无法直接写 Global，依次尝试助理页 / SLA 页 / WLAN 高级 / 系统 WLAN。 */
    fun openClosePage(ctx: Context, preferredAction: String) {
        val actions = arrayOf(
            preferredAction,
            ACTION_WLAN_ASSISTANT,
            ACTION_SLA,
            ACTION_WIFI_ADVANCED,
            Settings.ACTION_WIFI_SETTINGS
        )
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        for (action in actions) {
            val intent = Intent(action)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addFlags(flags)
            try {
                if (ctx.packageManager.resolveActivity(intent, 0) != null) {
                    ctx.startActivity(intent)
                    return
                }
            } catch (_: Exception) {
            }
            try {
                ctx.startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
    }
}
