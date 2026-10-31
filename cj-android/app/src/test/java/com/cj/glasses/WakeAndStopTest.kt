package com.cj.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wake rule (in sync with tools/wake_test.py) and the words that cut CJ off mid-answer. */
class WakeAndStopTest {
    private fun partial(text: String) = text.split(" ").map { Heard(it, null) }
    private fun settled(vararg words: Pair<String, Double>) = words.map { Heard(it.first, it.second) }

    @Test fun theNameWakesAtTheStartOrAfterHey() {
        listOf("see jay", "c j", "hey see jay", "okay c j", "see jay [unk] [unk]").forEach {
            assertTrue(it, called(partial(it)))
        }
    }

    @Test fun lookAlikesInsideASentenceDoNot() {
        // "Did you see Jake?" decodes as "you c j"; "see you", "city" and the rest are decoys in the grammar.
        listOf("you c j", "[unk] see jay", "see you", "city", "jay", "she see jay").forEach {
            assertFalse(it, called(partial(it)))
        }
    }

    @Test fun settledResultsNeedSomeConfidence() {
        assertTrue(called(settled("see" to 0.63, "jay" to 0.49)))
        assertFalse(called(settled("see" to 0.9, "jay" to 0.2)))
    }

    @Test fun stopWordsCutCjOff() {
        listOf("stop", "Stop!", "ok stop", "ruko", "bas bas", "chup", "that's enough", "रुको", "बस").forEach {
            assertTrue(it, STOP_WORDS.containsMatchIn(it))
        }
    }

    @Test fun wordsThatMerelyContainThemDoNot() {
        listOf("bus stand", "unstoppable", "basmati rice", "chupke", "stopwatch").forEach {
            assertFalse(it, STOP_WORDS.containsMatchIn(it))
        }
    }
}
