package com.cj.glasses

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/**
 * The apps on this phone (the ones in the app drawer), sent to the brain so "open my calendar" works for any app
 * and "order food" picks Swiggy when Zomato isn't installed. Names and package names only.
 */
object InstalledApps {
    private var cached: JSONArray? = null
    private var at = 0L

    fun json(context: Context): JSONArray {
        val now = System.currentTimeMillis()
        cached?.takeIf { now - at < 5 * 60_000 }?.let { return it }
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = runCatching { pm.queryIntentActivities(launcher, 0) }.getOrDefault(emptyList())
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { (pkg, _) -> pkg != context.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        return JSONArray(apps.map { (pkg, label) -> JSONObject().put("label", label.take(60)).put("package", pkg) })
            .also { cached = it; at = now }
    }
}
