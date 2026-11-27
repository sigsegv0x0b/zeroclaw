package ai.zeroclaw.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolPolicyTest {

    private fun policy(
        auto: Set<String> = emptySet(),
        ask: Set<String> = emptySet(),
        excluded: Set<String> = emptySet(),
    ) = ToolPolicyUi(autoApprove = auto, alwaysAsk = ask, excluded = excluded)

    @Test
    fun `block wins over approval lists`() {
        val value = policy(
            auto = setOf("timer_set"),
            ask = setOf("timer_set"),
            excluded = setOf("timer_set"),
        )
        assertEquals(ToolPolicy.Blocked, value.policyFor("timer_set"))
    }

    @Test
    fun `ask wins over auto approve`() {
        val value = policy(auto = setOf("timer_set"), ask = setOf("timer_set"))
        assertEquals(ToolPolicy.Ask, value.policyFor("timer_set"))
    }

    @Test
    fun `wildcard entries apply to every tool`() {
        assertEquals(ToolPolicy.Ask, policy(ask = setOf("*")).policyFor("shell"))
        assertEquals(ToolPolicy.Always, policy(auto = setOf("*")).policyFor("shell"))
    }

    @Test
    fun `blocked node tool matches its gateway name`() {
        val name = "node_android-06da938d__timer_set"
        assertEquals(ToolPolicy.Blocked, policy(excluded = setOf(name)).policyFor(name))
    }

    @Test
    fun `unlisted tool uses the profile default`() {
        assertEquals(ToolPolicy.Default, policy().policyFor("timer_set"))
    }
}
