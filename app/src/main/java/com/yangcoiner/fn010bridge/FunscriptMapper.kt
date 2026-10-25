package com.yangcoiner.fn010bridge

/**
 * funscript 位置轨迹 -> 速度控制的映射引擎。
 *
 * ── 为什么需要它 ────────────────────────────────────────────────────────
 * funscript 的 pos 是「套筒在某一时刻应处于的绝对位置」。
 * FN010 只有速度执行器(震动强度 / 伸缩转速), 没有位置执行器。
 * 对往复式设备, 位置根本无法执行 —— 真正决定体感的是【时间结构】:
 * 抽插多快、有没有停顿。这个信息能从位置序列反推:
 * 位置反转的时刻即往复转折点, 两次反转的间隔就是半周期。
 *
 *   伸缩马达 <- 往复频率 = 1 / (2 × 半周期)
 *   震动马达 <- 位置的一阶低通包络(平滑起伏, 而非方波嗡嗡)
 *
 * 本引擎在服务器端工作, 输入是上游(FFD)发来的命令流,
 * 因此无需解析 funscript 文件, 也对上游的插值/平滑方式不敏感。
 */
class FunscriptMapper(
    /** 档位 -> 往复周期(ms)。0 表示停止。需实测校准。索引=level(0..100)。 */
    private val levelPeriodMs: IntArray = DEFAULT_LEVEL_PERIOD_MS,
    /** 判定静止的速率阈值(归一化位置/秒)。 */
    private val deadbandPerSec: Double = 0.05,
    /** 连续静止多久算「停顿」(ms)。 */
    private val holdMs: Long = 500,
    /** 低通上升时间常数(ms)。 */
    private val attackMs: Double = 180.0,
    /** 低通下降时间常数(ms)。 */
    private val releaseMs: Double = 420.0,
    /** 最高档位(与 BleManager.LEVELS 一致, 0..100)。 */
    private val maxLevel: Int = 100
) {
    companion object {
        /**
         * 占位档位表: 索引=level(0..100), 值=往复周期(ms)。
         * 必须用实测值替换 —— 它决定映射准不准。
         * 依据: 最低档(慢)约 3 s/往复, 最高档(快)约 0.5 s/往复, 中间线性。
         */
        val DEFAULT_LEVEL_PERIOD_MS = IntArray(101) { level ->
            when {
                level <= 0 -> 0
                else -> (3000 - (level * 2500) / 100).coerceAtLeast(500)
            }
        }

        /** 校准表存到 SharedPreferences 的 key。 */
        const val PREFS_CALIBRATION = "fn010_calibration"

        /**
         * 读取校准表。格式: "0,3000,2400,...,500" (逗号分隔, 索引=档位)。
         * 读取失败或未校准时返回 null, 表示应使用默认值。
         */
        fun loadCalibration(prefs: android.content.SharedPreferences): IntArray? {
            val raw = prefs.getString(PREFS_CALIBRATION, null) ?: return null
            val parts = raw.split(",")
            if (parts.size < 10) return null
            return try {
                IntArray(parts.size) { parts[it].trim().toInt() }
            } catch (_: NumberFormatException) {
                null
            }
        }

        fun saveCalibration(prefs: android.content.SharedPreferences, table: IntArray) {
            prefs.edit().putString(PREFS_CALIBRATION, table.joinToString(",")).apply()
        }
    }

    // ---- 节律追踪状态 ----
    private var lastT: Long = -1
    private var lastValue: Double = 0.0
    private var lastSign: Int = 0
    private var lastFlipT: Long = -1
    private var holdSince: Long = -1

    /** 当前推断出的往复频率(Hz)。 */
    var frequencyHz: Double = 0.0
        private set

    /** 当前是否处于停顿段。 */
    var inHold: Boolean = false
        private set

    // ---- 包络状态 ----
    private var envelope: Double = 0.0

    /** 上一次输出的档位, 用于去重。 */
    private var lastOscLevel: Int = -1
    private var lastVibLevel: Int = -1

    fun reset() {
        lastT = -1
        lastValue = 0.0
        lastSign = 0
        lastFlipT = -1
        holdSince = -1
        frequencyHz = 0.0
        inHold = false
        envelope = 0.0
        lastOscLevel = -1
        lastVibLevel = -1
    }

    /**
     * 喂入一个上游命令。
     *
     * @param nowMs        单调递增的时间戳(ms)
     * @param normalized   归一化位置 0.0..1.0
     * @return 映射结果
     */
    fun feed(nowMs: Long, normalized: Double): Result {
        val v = normalized.coerceIn(0.0, 1.0)

        if (lastT < 0) {
            lastT = nowMs
            lastValue = v
            return Result(0, 0, 0.0, true)
        }

        val dt = nowMs - lastT
        val dv = v - lastValue
        lastT = nowMs
        lastValue = v

        if (dt <= 0) {
            return Result(lastVibLevel, lastOscLevel, frequencyHz, inHold)
        }

        // 速率(归一化位置/秒) —— 与上游采样率无关, 是判定运动/静止的正确量纲
        val rate = dv / (dt / 1000.0)

        if (kotlin.math.abs(rate) < deadbandPerSec) {
            // 静止
            if (holdSince < 0) holdSince = nowMs
            if (nowMs - holdSince >= holdMs) {
                inHold = true
                frequencyHz = 0.0
            }
        } else {
            holdSince = -1
            inHold = false

            val sign = if (rate > 0) 1 else -1
            if (lastSign != 0 && sign != lastSign) {
                // 位置反转 => 半周期结束 => 算出往复频率
                if (lastFlipT >= 0) {
                    val half = nowMs - lastFlipT
                    if (half > 0) frequencyHz = 1000.0 / (2.0 * half)
                }
                lastFlipT = nowMs
            }
            lastSign = sign
        }

        // 位置 -> 低通包络 -> 震动强度
        val tau = if (v > envelope) attackMs else releaseMs
        val alpha = 1.0 - Math.pow(0.5, dt / tau)
        envelope += (v - envelope) * alpha

        val vibLevel = (Math.round(envelope * maxLevel)).coerceIn(0, maxLevel).toInt()
        val oscLevel = if (inHold) 0 else levelForFrequency(frequencyHz)

        lastVibLevel = vibLevel
        lastOscLevel = oscLevel
        return Result(vibLevel, oscLevel, frequencyHz, inHold)
    }

    /**
     * 频率 -> 档位。
     * 选「频率刚好够用」的最高效档位(避免过快导致行程跟不上节律);
     * 若最高档仍达不到, 就用最高档。
     */
    fun levelForFrequency(hz: Double): Int {
        if (hz <= 0.0) return 0
        var best = -1
        for (lv in 1..maxLevel) {
            val p = levelPeriodMs.getOrNull(lv) ?: continue
            if (p <= 0) continue
            if (1000.0 / p >= hz) {
                best = lv
                break
            }
        }
        return if (best > 0) best else maxLevel
    }

    /** 档位对应的往复频率(Hz), 0 = 停止。 */
    fun frequencyForLevel(level: Int): Double {
        val p = levelPeriodMs.getOrNull(level) ?: return 0.0
        return if (p <= 0) 0.0 else 1000.0 / p
    }

    data class Result(
        val vibrateLevel: Int,
        val oscillateLevel: Int,
        val frequencyHz: Double,
        val inHold: Boolean
    )
}
