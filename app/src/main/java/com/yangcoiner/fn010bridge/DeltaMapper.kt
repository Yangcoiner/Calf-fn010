package com.yangcoiner.fn010bridge

/**
 * 伸缩「差值模式」映射器(可选功能,默认关闭)。
 *
 * 背景:funscript 是"位置"时间轴(pos 0~100),而本 App 的伸缩马达只吃"速度档位"。
 * 直控模式(原有行为)把上游数值直接当档位,位置 20 就只是 20% 速度,看起来不像在运动。
 *
 * 差值模式把上游每次发来的位置值当作一次位移,按速度换算档位:
 *
 *     Δpos% = |pos_new - pos_prev| * 100
 *     v     = Δpos% / Δt(s)           单位:%/秒
 *     level = clamp(v / vMax, 0, 1) ^ gamma
 *
 * 位移越大、间隔越短 → 速度越快 → 伸缩档位越高。
 *
 * 小冲程(Δpos% < aMin):机械行程不足以做一次完整往复,改由振动通道代偿,
 * 此时伸缩归零、振动强度 = level * vibGain。带迟滞(退出阈值 = aMin * 1.5),
 * 避免在阈值附近来回跳。
 *
 * 直控模式下 [enabled] = false,本类完全不参与任何计算。
 */
object DeltaMapper {

    /** 差值模式总开关。false = 直控(原有行为)。 */
    @Volatile var enabled = false

    /** 满速线:%/秒。达到该速度即 100% 档位。 */
    @Volatile var vMaxPctPerSec = 800.0

    /** 小冲程阈值(%位移)。低于该值改用振动。 */
    @Volatile var aMinPct = 20.0

    /** 小冲程时振动强度增益。 */
    @Volatile var vibGain = 1.0

    /** 响应曲线指数:1.0 线性,>1 偏慢(<1 偏快)。 */
    @Volatile var gamma = 1.0

    /** 断流判定:距上次命令超过该时长(ms)视为重新对齐,输出停止。 */
    @Volatile var maxGapMs = 600L

    /** 小冲程迟滞系数:位移 > aMin * 该值 才退出小冲程。 */
    private const val HYSTERESIS = 1.5

    data class Out(
        val osc: Double,        // 伸缩档位 0..1
        val vib: Double,        // 振动档位 0..1(仅小冲程时 > 0)
        val speedPct: Double,   // 实测速度 %/s(用于日志/调试)
        val small: Boolean      // 本帧是否判定为小冲程
    )

    private var lastPos = Double.NaN
    private var lastAt = 0L
    private var inSmall = false

    /** 清空状态(切换模式 / 停止 / 重连时调用)。 */
    @Synchronized
    fun reset() {
        lastPos = Double.NaN
        lastAt = 0L
        inSmall = false
    }

    /**
     * 输入一帧位置(0..1)与该帧的单调时钟时间(ms),输出两路目标档位。
     * 首帧只用来建立基准,不做输出。
     */
    @Synchronized
    fun map(pos: Double, nowMs: Long): Out {
        val p = pos.coerceIn(0.0, 1.0)

        if (lastPos.isNaN()) {
            lastPos = p
            lastAt = nowMs
            return Out(0.0, 0.0, 0.0, false)
        }

        val dtMs = nowMs - lastAt
        val dPct = Math.abs(p - lastPos) * 100.0
        lastPos = p
        lastAt = nowMs

        // 时间戳异常 / 断流:重新对齐,输出停止(安全)
        if (dtMs <= 0 || dtMs > maxGapMs) return Out(0.0, 0.0, 0.0, false)

        val v = dPct / (dtMs / 1000.0)
        var level = (v / vMaxPctPerSec).coerceIn(0.0, 1.0)
        if (gamma != 1.0) level = Math.pow(level, gamma)

        // 小冲程迟滞
        if (inSmall) {
            if (dPct > aMinPct * HYSTERESIS) inSmall = false
        } else {
            if (dPct < aMinPct) inSmall = true
        }

        return if (inSmall) {
            Out(0.0, (level * vibGain).coerceIn(0.0, 1.0), v, true)
        } else {
            Out(level, 0.0, v, false)
        }
    }
}
