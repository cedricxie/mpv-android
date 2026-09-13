package `is`.xyz.mpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StudyDataParserTest {
    @Test
    fun acceptsOrderedValidCues() {
        val cues = listOf(cue("one", 1.0, 2.0), cue("two", 3.0, 4.0))

        assertEquals(cues, StudyDataParser.validate(cues))
    }

    @Test
    fun rejectsEmptyData() {
        assertThrows(IllegalArgumentException::class.java) {
            StudyDataParser.validate(emptyList())
        }
    }

    @Test
    fun rejectsInvalidRanges() {
        assertThrows(IllegalArgumentException::class.java) {
            StudyDataParser.validate(listOf(cue("bad", 2.0, 2.0)))
        }
    }

    @Test
    fun acceptsLoopBoundaryPadding() {
        val cue = cue("one", 1.0, 2.0, loopStart = 0.8, loopEnd = 2.3)

        assertEquals(listOf(cue), StudyDataParser.validate(listOf(cue)))
    }

    @Test
    fun rejectsLoopThatCutsOffCue() {
        assertThrows(IllegalArgumentException::class.java) {
            StudyDataParser.validate(listOf(cue("bad", 1.0, 2.0, loopEnd = 1.9)))
        }
    }

    @Test
    fun rejectsDuplicateIds() {
        assertThrows(IllegalArgumentException::class.java) {
            StudyDataParser.validate(listOf(cue("same", 1.0, 2.0), cue("same", 3.0, 4.0)))
        }
    }

    @Test
    fun rejectsUnorderedCues() {
        assertThrows(IllegalArgumentException::class.java) {
            StudyDataParser.validate(listOf(cue("later", 3.0, 4.0), cue("earlier", 1.0, 2.0)))
        }
    }

    private fun cue(
        id: String,
        start: Double,
        end: Double,
        loopStart: Double = start,
        loopEnd: Double = end,
    ) = StudyCue(
        id = id,
        start = start,
        end = end,
        loopStart = loopStart,
        loopEnd = loopEnd,
        kind = "dialogue",
        japanese = "日本語",
        originalChinese = "中文",
        naturalChinese = "中文",
        difficulty = "N4",
        vocabulary = emptyList(),
        grammar = emptyList(),
        listeningNotes = emptyList(),
        translationNote = "",
    )
}
