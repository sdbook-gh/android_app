package com.example.mobiledatanotifier

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 20085（getNewPlanRemainQry）明文解析，与界面解耦。
 *
 * 余量查询页（Hook 抓 success 回调）和主界面（静默刷新）共用同一套规则，
 * 避免两处解析逻辑漂移。
 */
object MarginParser {

    private const val TAG = "MarginQuery"

    /** 解析出的余量：数值不带单位，unit 已翻译成 MB/GB 这类可读单位。 */
    data class Result(val used: String?, val remain: String?, val unit: String?, val source: String)

    /** success=false 表示登录失效/接口报错；result=null 表示结构不认识。 */
    data class Outcome(val success: Boolean, val result: Result?, val raw: String)

    fun parse(json: String): Outcome {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.w(TAG, "明文不是 JSON", e)
            return Outcome(false, null, json)
        }
        val code = firstNonBlank(
            root.optString("retCode", ""),
            root.optString("code", ""),
            root.optString("resultCode", "")
        )
        val success = code == null || code == "000000" || code == "0" || code == "success"
        val rsp = root.optJSONObject("rspBody") ?: root
        val candidate = if (success) bestFlowCandidate(rsp) else null
        return Outcome(success, candidate?.let { Result(it.used, it.remain, it.unit, it.path) }, json)
    }

    /** 兜底：从页面渲染出来的文本里抠「已用 / 剩余」的带单位数字。 */
    fun parsePageText(text: String): Result? {
        val usedM = pageUsedRegex.find(text)
        val remainM = pageRemainRegex.find(text)
        if (usedM == null && remainM == null) return null
        val used = usedM?.let { it.groupValues[1] + it.groupValues[2].uppercase() }
        val remain = remainM?.let { it.groupValues[1] + it.groupValues[2].uppercase() }
        return Result(used, remain, null, "pageText")
    }

    // ------------------------------------------------------------------

    private data class Candidate(
        val path: String,
        val used: String?,
        val remain: String?,
        val unit: String?,
        val label: String,
        val score: Int
    )

    private val usedKeys = listOf(
        "usedRes", "usedValue", "usedAmount", "usedFlow", "used", "hasUsed", "usedNum",
        // 20085 流量汇总字段
        "flowUsdNum", "flowUsedNum", "usedtotal", "usedTotal", "useTotal"
    )
    private val remainKeys = listOf(
        "remainRes", "remainValue", "remainAmount", "remainFlow", "remain", "leftValue", "leftRes",
        "balalance", "balance", "surplus",
        // 20085 流量汇总字段
        "flowRemainNum", "remainNumber"
    )
    private val totalKeys = listOf(
        "totalRes", "totalValue", "totalAmount", "totalFlow", "total", "allValue",
        // 20085 流量汇总字段
        "flowSumNum", "resSum"
    )
    private val unitKeys = listOf(
        "unit", "unitName", "unitType", "resUnit", "remainUnit", "usedUnit", "valueUnit",
        "measureUnit", "flowUnit"
    )
    private val labelKeys = listOf(
        "name", "planName", "itemName", "title", "bizName", "resName", "offerName", "productName",
        "typeName", "resTypeName"
    )

    private val flowUnitRegex = Regex("^(TB|GB|MB|KB|T|G|M|K)$", RegexOption.IGNORE_CASE)
    private val nonFlowUnitRegex = Regex("分钟|条|次|天|元|小时|分|个|次/月")
    private val flowLabelRegex = Regex("流量|上网|通用|国内")

    /** 在整份明文里挑分数最高的流量候选；找不到流量项返回 null。 */
    private fun bestFlowCandidate(root: JSONObject): Candidate? {
        // 先认协议里固定的汇总节点，避免被「某个流量包明细」带偏
        summaryFlowCandidate(root)?.let { c ->
            Log.d(TAG, "汇总节点命中: used=${c.used} remain=${c.remain} unit=${c.unit} path=${c.path}")
            return c
        }
        val all = mutableListOf<Candidate>()
        collectCandidates(root, "", all)
        if (all.isEmpty()) return null
        all.sortedByDescending { it.score }.forEach {
            Log.d(TAG, "候选 path=${it.path} used=${it.used} remain=${it.remain} unit=${it.unit} label=${it.label} score=${it.score}")
        }
        // 先过滤出流量项再取最高分，避免高分但非流量的“剩余天数/话费”挡住正确项
        return all.filter { isFlowCandidate(it) }.maxByOrNull { it.score }
    }

    // ---- 20085 汇总节点 ----

    /** 汇总节点 key（含常见拼写变体 planRemian / planRemain）。 */
    private val summaryListKeys = listOf("planRemianFlowListRes", "planRemainFlowListRes")
    private val summaryItemKeys = listOf("planRemianFlowRes", "planRemainFlowRes")

    /**
     * 20085 流量汇总的固定结构（实测报文摘录）：
     *   rspBody.newPlanRemainQryRes.planRemianFlowListRes = {
     *     "usedtotal": "195.75", "unit": "03",
     *     "planRemianFlowRes": [ {
     *       "flowSumNum": "634", "flowUsdNum": "195.75", "flowRemainNum": "438.25",
     *       "unit": "03",
     *       "planRemianFlowInfoRes": [ { "flowInfoRes": [ { totalRes/usedRes/remainRes ... } ] } ]
     *     } ]
     *   }
     * 只有 planRemianFlowRes[] 这一层是「本月流量汇总」，planRemianFlowInfoRes[].flowInfoRes[]
     * 是各流量包明细；直接取第一个明细包会得到 7.75/192.25 这种错误数字。
     */
    private fun summaryFlowCandidate(root: JSONObject): Candidate? {
        var list: JSONObject? = null
        for (k in summaryListKeys) {
            list = findObjectByKey(root, k)
            if (list != null) break
        }
        val listRes = list ?: return null

        var item: JSONObject? = null
        for (k in summaryItemKeys) {
            item = listRes.optJSONArray(k)?.optJSONObject(0)
            if (item != null) break
        }

        val used = firstNonBlank(item?.optString("flowUsdNum", ""), listRes.optString("usedtotal", ""))
        var remain = firstNonBlank(item?.optString("flowRemainNum", ""))
        val total = firstNonBlank(item?.optString("flowSumNum", ""))
        if (remain == null && total != null && used != null) {
            val t = total.toDoubleOrNull()
            val u = used.toDoubleOrNull()
            if (t != null && u != null) remain = trimNumber(t - u)
        }
        if (used == null && remain == null) return null
        // 两个都不是数字说明结构变了，交给启发式
        if (used?.toDoubleOrNull() == null && remain?.toDoubleOrNull() == null) return null

        val unit = unitCodeName(firstNonBlank(item?.optString("unit", ""), listRes.optString("unit", "")))
            ?: unitFromValues(used, remain)
        return Candidate("summary", used, remain, unit, "流量汇总", 300)
    }

    /** 深度优先按 key 名找第一个对象节点。 */
    private fun findObjectByKey(node: Any?, key: String): JSONObject? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(key)?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    findObjectByKey(node.opt(k), key)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findObjectByKey(node.opt(i), key)?.let { return it }
                }
            }
        }
        return null
    }

    /** 移动 20085 的单位码：01=分钟、02=条、03=MB；其它（已带单位/未知码）原样返回。 */
    private fun unitCodeName(raw: String?): String? {
        val u = raw?.trim().orEmpty()
        if (u.isEmpty() || u == "null") return null
        return when (u) {
            "01" -> "分钟"
            "02" -> "条"
            "03" -> "MB"
            else -> u
        }
    }

    private fun isFlowCandidate(c: Candidate): Boolean {
        val u = c.unit?.trim().orEmpty()
        if (u.isNotEmpty() && flowUnitRegex.matches(u)) return true
        if (u.isNotEmpty() && nonFlowUnitRegex.containsMatchIn(u)) return false
        if (flowLabelRegex.containsMatchIn(c.label)) return true
        if (c.path.contains("flow", ignoreCase = true) && !c.path.contains("remainNum", ignoreCase = true)) return true
        // 单位未知但数值带小数（流量常为 195.75 这种），才算可信
        val decimals = (c.used?.contains('.') == true) || (c.remain?.contains('.') == true)
        return decimals && c.score > 0
    }

    private fun collectCandidates(node: Any?, path: String, out: MutableList<Candidate>) {
        when (node) {
            is JSONObject -> {
                buildCandidate(node, path)?.let { out.add(it) }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (v is JSONObject || v is JSONArray) {
                        collectCandidates(v, if (path.isEmpty()) k else "$path.$k", out)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val v = node.opt(i)
                    if (v is JSONObject || v is JSONArray) collectCandidates(v, "$path[$i]", out)
                }
            }
        }
    }

    private fun buildCandidate(obj: JSONObject, path: String): Candidate? {
        val usedRaw = firstNonBlank(*usedKeys.map { obj.optString(it, "") }.toTypedArray())
        val remainRaw = firstNonBlank(*remainKeys.map { obj.optString(it, "") }.toTypedArray())
        val totalRaw = firstNonBlank(*totalKeys.map { obj.optString(it, "") }.toTypedArray())
        if (usedRaw == null && remainRaw == null) return null
        var used = usedRaw
        var remain = remainRaw
        if (remain == null && totalRaw != null && used != null) {
            val t = totalRaw.toDoubleOrNull()
            val u = used.toDoubleOrNull()
            if (t != null && u != null) remain = trimNumber(t - u)
        }
        val unit = unitCodeName(firstNonBlank(*unitKeys.map { obj.optString(it, "") }.toTypedArray()))
            ?: unitFromValues(used, remain)
        val label = firstNonBlank(*labelKeys.map { obj.optString(it, "") }.toTypedArray()).orEmpty()
        var score = scoreCandidate(path, label, unit, used, remain, totalRaw != null)
        // 带 flowSumNum/flowUsdNum 的是汇总节点，优先级高于流量包明细
        if (obj.has("flowSumNum") || obj.has("flowUsdNum")) score += 60
        return Candidate(path, used, remain, unit, label, score)
    }

    private fun scoreCandidate(
        path: String,
        label: String,
        unit: String?,
        used: String?,
        remain: String?,
        hasTotal: Boolean
    ): Int {
        var score = 10 // 能凑出 used/remain 就说明是个数值项
        if (hasTotal) score += 10
        val u = unit?.trim().orEmpty()
        when {
            u.isNotEmpty() && flowUnitRegex.matches(u) -> score += 100
            u.isNotEmpty() && nonFlowUnitRegex.containsMatchIn(u) -> score -= 200
        }
        if (flowLabelRegex.containsMatchIn(label)) score += 60
        if (path.contains("flow", ignoreCase = true)) score += 30
        if (path.contains("voice", ignoreCase = true) ||
            path.contains("sms", ignoreCase = true) ||
            path.contains("call", ignoreCase = true) ||
            path.contains("fee", ignoreCase = true) ||
            path.contains("day", ignoreCase = true) ||
            path.contains("remainNum", ignoreCase = true)
        ) score -= 100
        // 单个流量包明细（flowInfoRes）不参与汇总竞争
        if (path.contains("flowInfoRes", ignoreCase = true)) score -= 40
        if (used?.contains('.') == true || remain?.contains('.') == true) score += 20
        return score
    }

    /** 值里自带单位时（如 "438.25MB"）抽出来。 */
    private fun unitFromValues(used: String?, remain: String?): String? {
        for (v in listOfNotNull(used, remain)) {
            Regex("([A-Za-z]+)\\s*$").find(v.trim())?.groupValues?.getOrNull(1)?.let { suffix ->
                if (flowUnitRegex.matches(suffix)) return suffix.uppercase()
            }
        }
        return null
    }

    private val pageUsedRegex = Regex(
        """(?:已用|已使用|使用的流量)[\s:：]{0,6}(\d+(?:\.\d+)?)\s*(TB|GB|MB|KB|[TGMK])""",
        RegexOption.IGNORE_CASE
    )
    private val pageRemainRegex = Regex(
        """(?:剩余|可用)[\s:：]{0,6}(\d+(?:\.\d+)?)\s*(TB|GB|MB|KB|[TGMK])""",
        RegexOption.IGNORE_CASE
    )

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() && it != "null" }

    private fun trimNumber(v: Double): String {
        val s = String.format(Locale.US, "%.2f", v)
        return s.trimEnd('0').trimEnd('.')
    }
}
