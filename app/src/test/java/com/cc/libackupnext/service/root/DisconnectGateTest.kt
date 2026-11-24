package com.cc.libackupnext.service.root

import org.junit.Assert.assertEquals
import org.junit.Test

/** 钉住「一次 kill 只报一条」和「两次 kill 各报一条」，边界在窗口长度上。 */
class DisconnectGateTest {

    private val window = 1_000L
    private val base = 10_000L

    @Test
    fun `同一次死亡的连续回调序号递增`() {
        val gate = DisconnectGate(window)

        assertEquals(1, gate.mark(base))
        assertEquals(2, gate.mark(base + 10))
        assertEquals(3, gate.mark(base + 20))
    }

    @Test
    fun `差一毫秒仍在窗口内`() {
        val gate = DisconnectGate(window)

        assertEquals(1, gate.mark(base))
        assertEquals(2, gate.mark(base + window - 1))
    }

    @Test
    fun `刚好等于窗口算新的一次`() {
        val gate = DisconnectGate(window)

        assertEquals(1, gate.mark(base))
        assertEquals(1, gate.mark(base + window))
    }

    @Test
    fun `两次独立 kill 各自从 1 开始`() {
        val gate = DisconnectGate(window)

        assertEquals(1, gate.mark(base))
        assertEquals(2, gate.mark(base + 5))
        assertEquals(3, gate.mark(base + 10))

        assertEquals(1, gate.mark(base + 2_000))
        assertEquals(2, gate.mark(base + 2_005))
    }

    @Test
    fun `时间戳回退并入上一次，不产生负数`() {
        // 时间回退按窗口内处理，不能出现 0 或负数
        val gate = DisconnectGate(window)

        assertEquals(1, gate.mark(base))
        assertEquals(2, gate.mark(base - 500))
    }
}
