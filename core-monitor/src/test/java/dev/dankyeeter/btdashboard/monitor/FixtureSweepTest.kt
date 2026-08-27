package dev.dankyeeter.btdashboard.monitor

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * Every fixture through every parser, checked for the things that are true of
 * *all* dumps rather than of one.
 *
 * ## Why this is not more golden tests
 *
 * `LiveLinkParserTest` pins what each capture contains, which is the right test
 * for "does the parser still read this phone". It cannot state the properties
 * that hold across all input — that a section which is not in the dump comes
 * back absent rather than zeroed, that a codec is never reported without a
 * device, that no parser ever throws — because each of its assertions is about
 * one file's contents.
 *
 * Those cross-cutting properties are where the expensive bugs in this module
 * have been. A parser that reads past its section boundary still passes every
 * golden test whose fixture happens to end before the next section; the one
 * that ran 850 lines into `Profile: HeadsetService` did exactly that. So this
 * file asserts the invariants and sweeps them over the whole corpus, and every
 * fixture added later is swept without anyone having to remember it.
 *
 * The fixture list is enumerated from disk on purpose, README included: a
 * parser must survive text that is not a dump at all just as much as text that
 * is.
 */
@RunWith(Parameterized::class)
class FixtureSweepTest(private val name: String, private val fixture: File) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<Array<Any>> =
            RepoTree.dumpFixtures.map { arrayOf(it.name, it) }
    }

    private val text: String by lazy { fixture.readText() }

    @Test
    fun `every parser reads it without throwing and without inventing data`() {
        ParserInvariants.assertAll(name, text)
    }

    /**
     * The corpus itself has to stay worth sweeping.
     *
     * A fixture truncated to nothing, or a directory that quietly emptied out,
     * would make every assertion above vacuously true.
     */
    @Test
    fun `the fixture carries content`() {
        assertTrue("$name is empty", text.isNotBlank())
    }
}
