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

    private fun cue(id: String, start: Double, end: Double) = StudyCue(
        id = id,
        start = start,
        end = end,
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
