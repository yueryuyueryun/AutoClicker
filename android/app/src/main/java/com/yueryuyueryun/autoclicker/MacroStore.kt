// AutoClicker Android 版 · 作者 cui-huajin / yueryuyueryun · MIT License
package com.yueryuyueryun.autoclicker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// 宏存档读写，JSON 结构与 Windows 版 macros.json 对齐：
// [{"name":..,"steps":[{"x":..,"y":..,"interval":..}],"gap":..,"loops":..}]
class MacroStore(context: Context) {

    data class Step(val x: Int, val y: Int, val interval: Long)

    data class Macro(val name: String, val steps: List<Step>, val gap: Long, val loops: Int)

    // 用 applicationContext 防止持有 Activity/Service 引用
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadAll(): List<Macro> {
        val raw = prefs.getString(KEY_MACROS, null) ?: return emptyList()
        // 存档损坏时按空处理，避免连点器整体不可用
        return try {
            parseArray(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun names(): List<String> = loadAll().map { it.name }

    fun find(name: String): Macro? = loadAll().firstOrNull { it.name == name }

    fun save(macro: Macro) {
        // 同名视为覆盖，与 Windows 版按名字管理存档的语义一致
        val list = loadAll().filter { it.name != macro.name } + macro
        prefs.edit().putString(KEY_MACROS, toJson(list)).apply()
    }

    fun delete(name: String) {
        val list = loadAll().filter { it.name != name }
        prefs.edit().putString(KEY_MACROS, toJson(list)).apply()
    }

    private fun parseArray(raw: String): List<Macro> {
        val arr = JSONArray(raw)
        val result = ArrayList<Macro>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val stepsArr = obj.optJSONArray("steps") ?: JSONArray()
            val steps = ArrayList<Step>(stepsArr.length())
            for (j in 0 until stepsArr.length()) {
                val s = stepsArr.getJSONObject(j)
                steps.add(Step(s.getInt("x"), s.getInt("y"), s.optLong("interval", 0L)))
            }
            result.add(
                Macro(
                    obj.getString("name"),
                    steps,
                    obj.optLong("gap", 500L),
                    obj.optInt("loops", 1)
                )
            )
        }
        return result
    }

    private fun toJson(list: List<Macro>): String {
        val arr = JSONArray()
        for (m in list) {
            val stepsArr = JSONArray()
            for (s in m.steps) {
                stepsArr.put(JSONObject().put("x", s.x).put("y", s.y).put("interval", s.interval))
            }
            arr.put(
                JSONObject()
                    .put("name", m.name)
                    .put("steps", stepsArr)
                    .put("gap", m.gap)
                    .put("loops", m.loops)
            )
        }
        return arr.toString()
    }

    private companion object {
        const val PREFS_NAME = "macros"
        const val KEY_MACROS = "macros_json"
    }
}
