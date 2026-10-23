package id.mutqin.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Paritas dengan rumus di versi web (src/services/srsEngine.ts). */
class SrsEngineTest {
    private fun eval(rating: Int, m: Int = 0, h: Int = 0, s: Int = 0, t: Int = 0, box: Int = 2) =
        SrsEngine.evaluate(EvaluationInput(rating, m, h, s, t, box))

    @Test
    fun perfectRatingPromotes() {
        val r = eval(5)
        assertEquals(100, r.compositeScore)
        assertEquals("Sangat Lancar", r.aiGrade)
        assertEquals(3, r.recommendedBox)
        assertEquals(7, r.intervalDays)
    }

    @Test
    fun oneMistakeStillPromotesWhenRatingHigh() {
        val r = eval(4, m = 1) // 88 - 12 = 76
        assertEquals(76, r.compositeScore)
        assertEquals("Lancar", r.aiGrade)
        assertEquals(3, r.recommendedBox)
    }

    @Test
    fun skippedKeepsBox() {
        val r = eval(2, s = 1) // 64 - 10 = 54
        assertEquals(54, r.compositeScore)
        assertEquals("Cukup", r.aiGrade)
        assertEquals(2, r.recommendedBox)
    }

    @Test
    fun twoMistakesDropToBoxOne() {
        val r = eval(2, m = 2) // 64 - 24 = 40
        assertEquals(40, r.compositeScore)
        assertEquals("Kurang Lancar", r.aiGrade)
        assertEquals(1, r.recommendedBox)
        assertEquals(1, r.intervalDays)
    }

    @Test
    fun scoreNeverBelowTen() {
        val r = eval(1, m = 10)
        assertEquals(10, r.compositeScore)
        assertEquals("Perlu Diulang", r.aiGrade)
    }

    @Test
    fun boxSevenIsOneHundredTwentyDays() {
        assertEquals(120, Leitner.intervalFor(7))
        assertEquals(7, eval(5, box = 7).recommendedBox)
    }
}
