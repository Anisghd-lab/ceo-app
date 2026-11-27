package com.foundryos.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.foundryos.app.agy.AgyAccountManager
import com.foundryos.app.agy.AgyCliInstaller
import com.foundryos.app.agy.AgyRuntimeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AgyAndAccountManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.resolve("agy_accounts").deleteRecursively()
        context.filesDir.resolve("agy_home").deleteRecursively()
    }

    @Test
    fun parseStreamJsonLine_handlesThinkingToolResponseAndResultEvents() {
        val thinkEv = AgyRuntimeManager.parseStreamJsonLine(
            """{"event":"step_update","step_update":{"step_type":"thinking","thinking_delta":"Analyzing repo structure..."}}"""
        )
        assertEquals("thinking", thinkEv.kind)
        assertEquals("Analyzing repo structure...", thinkEv.text)

        val toolEv = AgyRuntimeManager.parseStreamJsonLine(
            """{"event":"step_update","step_update":{"step_type":"tool_call","tool_name":"read_file","arguments":"{\"path\":\"README.md\"}"}}"""
        )
        assertEquals("tool", toolEv.kind)
        assertEquals("read_file", toolEv.toolName)
        assertTrue(toolEv.toolArgs.contains("README.md"))

        val deltaEv = AgyRuntimeManager.parseStreamJsonLine(
            """{"event":"step_update","step_update":{"step_type":"agent_response","text_delta":"Here is the answer."}}"""
        )
        assertEquals("text_delta", deltaEv.kind)
        assertEquals("Here is the answer.", deltaEv.text)

        val okResult = AgyRuntimeManager.parseStreamJsonLine(
            """{"event":"result","result":{"status":"SUCCESS","response":"Final verified output"}}"""
        )
        assertEquals("result", okResult.kind)
        assertEquals("SUCCESS", okResult.resultStatus)
        assertEquals("Final verified output", okResult.finalResponse)

        val errResult = AgyRuntimeManager.parseStreamJsonLine(
            """{"event":"result","result":{"status":"ERROR","error":"Unauthenticated"}}"""
        )
        assertEquals("result", errResult.kind)
        assertEquals("ERROR", errResult.resultStatus)
        assertEquals("Unauthenticated", errResult.resultError)
    }

    @Test
    fun accountManager_savesAccountsStripsWriterIdAndMirrorsDualKeys() {
        val installer = AgyCliInstaller(context)
        val mgr = AgyAccountManager(installer.homeDir())

        val authDir = File(installer.homeDir(), ".config/agy-auth").apply { mkdirs() }
        val agyCliDir = File(installer.homeDir(), ".config/antigravity-cli").apply { mkdirs() }

        val acct1Json = """{"refresh_token":"tok_alpha","email":"alpha@example.com","name":"Alpha User","writer_id":"stale_writer_1"}"""
        File(authDir, "jetski-standalone-oauth-token").writeText(acct1Json)
        assertTrue(mgr.ensureTokensSynced())

        val mirroredCli = File(agyCliDir, "antigravity-oauth-token")
        assertTrue("antigravity-oauth-token mirror must exist", mirroredCli.exists())
        assertTrue(mirroredCli.readText().contains("tok_alpha"))

        val (saved1Ok, _) = mgr.saveCurrentAccount()
        assertTrue(saved1Ok)
        val accountsAfter1 = mgr.listSavedAccounts()
        assertTrue(accountsAfter1.any { it.email == "alpha@example.com" })

        // Add second account and ensure switching back restores first cleanly without writer_id collisions
        File(authDir, "jetski-standalone-oauth-token").writeText(
            """{"refresh_token":"tok_beta","email":"beta@example.com","name":"Beta User"}"""
        )
        val (saved2Ok, _) = mgr.saveCurrentAccount()
        assertTrue(saved2Ok)
        assertEquals(2, mgr.listSavedAccounts().size)

        val (switchOk, _) = mgr.switchToAccount("alpha@example.com")
        assertTrue(switchOk)
        val liveText = File(authDir, "jetski-standalone-oauth-token").readText()
        assertTrue(liveText.contains("tok_alpha"))
        assertFalse("writer_id must be stripped from switched snapshots", liveText.contains("writer_id"))
        assertTrue(File(agyCliDir, "antigravity-oauth-token").readText().contains("tok_alpha"))
    }
}
