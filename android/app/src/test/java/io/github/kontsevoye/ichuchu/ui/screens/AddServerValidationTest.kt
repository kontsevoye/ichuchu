package io.github.kontsevoye.ichuchu.ui.screens

import io.github.kontsevoye.ichuchu.model.AuthMethod
import io.github.kontsevoye.ichuchu.model.HostOpenMode
import io.github.kontsevoye.ichuchu.model.Transport
import io.github.kontsevoye.ichuchu.ui.screens.AddServer.AddServerForm
import io.github.kontsevoye.ichuchu.ui.screens.AddServer.canSave
import io.github.kontsevoye.ichuchu.ui.screens.AddServer.canTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddServerValidationTest {
    private val valid = AddServerForm(name = "qa", host = "::1", username = "fixture")

    @Test
    fun saveAndTestSharePortAndRequiredConnectionBoundaries() {
        for (port in listOf("1", "22", "65535")) {
            assertTrue(valid.copy(port = port).canSave())
            assertTrue(valid.copy(port = port).canTest())
        }
        for (port in listOf("", " ", "0", "65536", "-1", "abc", "99999999999999")) {
            assertFalse(valid.copy(port = port).canSave())
            assertFalse(valid.copy(port = port).canTest())
        }
        assertFalse(valid.copy(host = " \n").canTest())
        assertFalse(valid.copy(username = " \t").canTest())
        assertFalse(valid.copy(name = " ").canSave())
        assertTrue(valid.copy(name = " ").canTest())
        assertTrue(valid.copy(password = " \n'\t").canSave())
    }

    @Test
    fun keyAuthenticationCannotBeTestedWithoutTheSelectedPrivateKey() {
        for (method in listOf(AuthMethod.Key, AuthMethod.KeyWithPassphrase)) {
            val form = valid.copy(authMethod = method)
            assertFalse(form.canTest())
            assertFalse(form.copy(keyId = 1).canTest())
            assertFalse(form.copy(privateKeyPem = "synthetic key").canTest())
            assertTrue(form.copy(keyId = 1, privateKeyPem = "synthetic key").canTest())
        }
    }

    @Test
    fun agentProfileAndExecutableValidationAlsoGateConnectionTests() {
        val form = valid.copy(openMode = HostOpenMode.Agents)
        assertTrue(form.canTest())
        for (profile in listOf("", " ", "a/b", "x".repeat(65))) {
            assertFalse(form.copy(deckProfile = profile).canTest())
        }
        for (executable in listOf("a\nb", "a\rb", "a\u0000b")) {
            assertFalse(form.copy(deckExecutable = executable).canTest())
        }
        assertTrue(
            form
                .copy(deckProfile = "qa_01-test", deckExecutable = "/path with spaces/deck")
                .canTest()
        )
        assertFalse(form.copy(transport = Transport.LocalShell).canTest())
    }

    @Test
    fun jumpRoutesRequireSshForBothSaveAndTest() {
        val routed = valid.copy(jumpHostIds = listOf(1, 2, 3))
        assertTrue(routed.canSave())
        assertTrue(routed.canTest())
        for (transport in listOf(Transport.Mosh, Transport.TailscaleSSH, Transport.LocalShell)) {
            assertFalse(routed.copy(transport = transport).canSave())
            assertFalse(routed.copy(transport = transport).canTest())
        }
    }
}
