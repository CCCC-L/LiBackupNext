package com.cc.libackupnext.service.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 仓库结构判定：决定「建库 / 打开 / 提示缺件」。
 *
 * 这是「密码对却提示密码错」的回归点：缺 keys 的仓库如果直接 open，
 * rustic 会报 C002（密码错），所以必须在打开前把缺件挑出来。
 */
class RepoLayoutTest {

    private fun layout(
        config: Boolean = false,
        keys: Boolean = false,
        data: Boolean = false,
        index: Boolean = false,
        snapshots: Boolean = false
    ) = RepoLayout(config, keys, data, index, snapshots)

    @Test
    fun `什么都没有是空仓库，可以建库`() {
        assertEquals(RepoLayoutState.EMPTY, layout().state)
    }

    /** 刚 init 的仓库：五个都在（data / index / snapshots 是空目录）。 */
    @Test
    fun `五个都在是完整仓库`() {
        assertEquals(
            RepoLayoutState.COMPLETE,
            layout(config = true, keys = true, data = true, index = true, snapshots = true).state
        )
    }

    /**
     * 远程 rest 后端表示不了空目录：config + keys 在就够了。
     * 否则远程仓库一初始化就会被判成「目录缺件」。
     */
    @Test
    fun `config 和 keys 在就算完整`() {
        assertEquals(RepoLayoutState.COMPLETE, layout(config = true, keys = true).state)
    }

    @Test
    fun `缺 keys 是缺件，不能当空仓库重建`() {
        assertEquals(
            RepoLayoutState.INCOMPLETE,
            layout(config = true, data = true, index = true, snapshots = true).state
        )
    }

    @Test
    fun `缺 config 是缺件`() {
        assertEquals(
            RepoLayoutState.INCOMPLETE,
            layout(keys = true, data = true, index = true, snapshots = true).state
        )
    }

    /** 只有数据没有仓库身份：既不能建库（会留孤儿数据）也不能打开。 */
    @Test
    fun `只有数据目录是缺件`() {
        assertEquals(
            RepoLayoutState.INCOMPLETE,
            layout(data = true, index = true, snapshots = true).state
        )
    }
}
