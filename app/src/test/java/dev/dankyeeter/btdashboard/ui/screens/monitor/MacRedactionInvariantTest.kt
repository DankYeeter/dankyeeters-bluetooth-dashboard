package dev.dankyeeter.btdashboard.ui.screens.monitor

import dev.dankyeeter.btdashboard.monitor.codec.BtAudioDevice
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.diagnostic.DiagnosticReport
import dev.dankyeeter.btdashboard.monitor.diagnostic.DiagnosticStep
import dev.dankyeeter.btdashboard.monitor.diagnostic.DiagnosticStepResult
import dev.dankyeeter.btdashboard.monitor.diagnostic.StepOutcome
import dev.dankyeeter.btdashboard.system.devices.CodecApplyOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One rule, checked against every user-facing string the Monitor tab builds:
 * **no raw Bluetooth address reaches the screen or the clipboard.**
 *
 * [MonitorTuningAddressTest] pins the two halves of the join that produced the
 * original defect — the redacted address must not go *down* to the controller,
 * the real one must not come *up* to the UI — but it does so one sentence at a
 * time, and one sentence is exactly what a new outcome branch or a new report
 * line is. This walks every producer instead, over a corpus of addresses in the
 * spellings the platform and its own dumps actually use.
 *
 * It found one: `DiagnosticReport.asPlainText()` printed `deviceAddress`
 * verbatim whenever the headphone had no name, and that string exists to be
 * pasted into a support ticket. The report is built from the A2DP profile, not
 * from the dump, so its address is raw on every build — user or userdebug.
 */
class MacRedactionInvariantTest {

    /**
     * Addresses in every spelling that reaches this layer.
     *
     * `dumpsys` prints upper case, `BluetoothDevice.getAddress()` upper case,
     * hand-written logs and vendor tools lower or mixed. A redaction that only
     * knows one of them leaks on the others, which is the ordinary way a regex
     * guard rots.
     */
    private val rawAddresses = listOf(
        "AC:DE:48:00:37:8F",
        "ac:de:48:00:37:8f",
        "Ac:De:48:00:37:8f",
        "00:1A:7D:DA:71:13",
        "FF:FF:FF:FF:FF:FF",
        "00:00:00:00:00:00",
    )

    /** Any six-octet hex run, whatever the case — the thing that must not survive. */
    private val rawAddressPattern = Regex("""(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}""")

    /**
     * True when [text] still contains a real address.
     *
     * A masked address is `XX:XX:XX:XX:37:8F`, which the pattern above cannot
     * match because `XX` is not hex — so "contains a match" and "leaked" are the
     * same question, and the check needs no list of known-good strings.
     */
    private fun leaks(text: String): Boolean = rawAddressPattern.containsMatchIn(text)

    private fun assertNoLeak(label: String, text: String) {
        assertFalse("$label leaked a raw address: $text", leaks(text))
    }

    // ---- the producers ----------------------------------------------------------

    /**
     * The tuning sentences, exactly as `MonitorViewModel.setLdacQuality` builds
     * them.
     *
     * Mirrored here rather than driven through the ViewModel because that path
     * needs `MonitorGraph`, the privileged helper and a live A2DP profile — none
     * of which a unit test can stand up, and all of which would make the test
     * about the graph rather than about the sentence.
     *
     * The mirror is kept honest by the `when` being exhaustive over a sealed
     * interface: a fourth [CodecApplyOutcome] does not make this test fail, it
     * makes this **file stop compiling**, which is the same moment somebody has
     * to come back here and decide whether the new sentence needs redacting too.
     */
    private fun tuningMessage(outcome: CodecApplyOutcome): String = redactAddresses(
        when (outcome) {
            is CodecApplyOutcome.Applied ->
                "LDAC is now ${outcome.observed} — read back, not just requested."

            is CodecApplyOutcome.NotObserved ->
                "The link still reads ${outcome.observed}: ${outcome.detail}."

            is CodecApplyOutcome.Unavailable ->
                "LDAC quality was not changed — ${outcome.reason}."
        },
    )

    /**
     * Every outcome a tuning tap can produce, with the address in each field
     * that carries text from below.
     *
     * The real sentences these stand for are the helper's own: it is handed an
     * address and quotes it back when it refuses — *"AC:DE:48:00:37:8F is not a
     * Bluetooth address"* is the message that started the whole exercise.
     */
    private fun outcomesFor(address: String): List<CodecApplyOutcome> = listOf(
        CodecApplyOutcome.Applied("LDAC · 96 kHz · 32 bit"),
        CodecApplyOutcome.Applied("codec on $address"),
        CodecApplyOutcome.NotObserved("AAC", "codec on $address was not observed after 2000 ms"),
        CodecApplyOutcome.NotObserved(address, "the read-back names $address"),
        CodecApplyOutcome.Unavailable("$address is not a Bluetooth address"),
        CodecApplyOutcome.Unavailable("setCodecConfigPreference($address, LDAC) threw"),
        CodecApplyOutcome.Unavailable("the privileged helper is not running"),
    )

    /** A finished diagnostic with the address wherever it can legitimately appear. */
    private fun reportFor(address: String, deviceName: String?) = DiagnosticReport(
        deviceAddress = address,
        deviceName = deviceName,
        startedAtMs = 1_700_000_000_000L,
        finishedAtMs = 1_700_000_180_000L,
        steps = listOf(
            DiagnosticStepResult(DiagnosticStep.CONNECTION_CHECK, StepOutcome.Passed("Connected as Bathys")),
            DiagnosticStepResult(
                DiagnosticStep.CODEC_NEGOTIATION,
                // The shape a lower layer's reason really has: it names what it
                // was handed, and what it was handed is the raw address.
                StepOutcome.Warned("Codec status failed: no A2DP device for $address"),
            ),
            DiagnosticStepResult(
                DiagnosticStep.CODEC_CYCLING,
                StepOutcome.Skipped("Codec switching skipped — $address rejected the preference"),
            ),
            DiagnosticStepResult(DiagnosticStep.SOAK, StepOutcome.Passed("120 samples, no drops")),
        ),
        bestStableCodec = CodecFamily.LDAC,
        dropCount = 0,
        rssiRangeDbm = -70..-52,
        sampleCount = 120,
    )

    // ---- the invariant ----------------------------------------------------------

    @Test
    fun `no tuning outcome puts a raw address on screen`() {
        rawAddresses.forEach { address ->
            outcomesFor(address).forEach { outcome ->
                assertNoLeak("tuning outcome $outcome", tuningMessage(outcome))
            }
        }
    }

    @Test
    fun `no diagnostic report puts a raw address on the clipboard`() {
        rawAddresses.forEach { address ->
            listOf(null, "Bathys").forEach { name ->
                val text = reportFor(address, name).asPlainText()
                assertNoLeak("report for $address named $name", text)
                // The last two octets are what tells two headphones apart, so
                // the line must still identify the device it tested.
                assertTrue(
                    "the report stopped identifying the device: $text",
                    text.contains(name?.takeIf { it.isNotBlank() } ?: maskAddress(address)),
                )
            }
        }
    }

    /** The masking helper itself, over every spelling and over what it is not. */
    @Test
    fun `masking is total, idempotent and leaves non-addresses alone`() {
        rawAddresses.forEach { address ->
            val masked = maskAddress(address)

            assertNoLeak("maskAddress($address)", masked)
            assertEquals("idempotence for $address", masked, maskAddress(masked))
            // The two octets the platform itself prints stay verbatim.
            assertTrue(masked.endsWith(address.takeLast(5)))
        }
        listOf("Bathys", "", "LDAC · 96 kHz", "not-an-address").forEach {
            assertEquals(it, maskAddress(it))
        }
    }

    /**
     * The redaction is a sentence-level rule, so it has to survive whatever the
     * sentence puts around the address: punctuation, brackets, several addresses
     * at once, and the two ends of the string where a naive word boundary is
     * easiest to get wrong.
     */
    @Test
    fun `redaction finds an address wherever it sits in a sentence`() {
        val a = rawAddresses[0]
        val b = rawAddresses[3]
        listOf(
            a,
            "$a is not a Bluetooth address",
            "the device is $a",
            "($a)",
            "\"$a\"",
            "codec on $a was not observed after 2000 ms",
            "$a and $b disagree",
            "[$a] → [$b]",
            "address=$a;codec=LDAC",
            "$a\n$b",
        ).forEach { assertNoLeak("redactAddresses", redactAddresses(it)) }

        // And it must not eat text that merely looks technical.
        val plain = "LDAC is now LDAC · 96 kHz · 32 bit — read back, not just requested."
        assertEquals(plain, redactAddresses(plain))
        assertEquals("signal -70 to -52 dBm", redactAddresses("signal -70 to -52 dBm"))
    }

    /**
     * The other direction of the same boundary: what goes *down* to the helper
     * must never be a masked string, because a masked one is not an address and
     * the helper says so. Generalised over the corpus rather than the one pair.
     */
    @Test
    fun `nothing masked is ever handed back down to the controller`() {
        rawAddresses.forEach { address ->
            val shown = maskAddress(address)
            val connected = listOf(BtAudioDevice(address = address, name = "Bathys"))

            val resolved = rawAddressFor(shown, connected)
            assertEquals(address, resolved)
            assertFalse("a masked address reached the controller: $resolved", resolved!!.contains("X"))

            // And with nothing matching on the profile, the answer is nothing —
            // never the masked string dressed up as an address.
            assertNull(rawAddressFor(shown, emptyList()))
        }
    }

}
