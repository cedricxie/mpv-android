package `is`.xyz.mpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StudyDocumentResolverTest {
    @Test
    fun pairsExplicitChineseAndJapaneseSubtitles() {
        val result = StudyDocumentResolver.findNames(
            listOf(
                "Dragon Sakura - S01E02.mkv",
                "Dragon Sakura - S01E02.zh.ass",
                "Dragon Sakura - S01E02.ja.srt",
                "Dragon Sakura - S01E02.study.json",
            ),
            "Dragon Sakura - S01E02",
        )

        assertEquals("Dragon Sakura - S01E02.zh.ass", result.primarySubtitle)
        assertEquals("Dragon Sakura - S01E02.ja.srt", result.secondarySubtitle)
        assertEquals("Dragon Sakura - S01E02.study.json", result.studyData)
    }

    @Test
    fun doesNotDuplicateJapaneseForGenericBilingualAss() {
        val result = StudyDocumentResolver.findNames(
            listOf(
                "Operation Love - S01E02.ass",
                "Operation Love - S01E02.ja.srt",
            ),
            "Operation Love - S01E02",
        )

        assertEquals("Operation Love - S01E02.ass", result.primarySubtitle)
        assertNull(result.secondarySubtitle)
    }

    @Test
    fun preservesExistingPrimarySubtitlePriority() {
        val result = StudyDocumentResolver.findNames(
            listOf(
                "Episode.ass",
                "Episode.zh.srt",
                "Episode.ja.srt",
            ),
            "Episode",
        )

        assertEquals("Episode.ass", result.primarySubtitle)
        assertNull(result.secondarySubtitle)
    }

    @Test
    fun matchesCompanionsCaseInsensitively() {
        val result = StudyDocumentResolver.findNames(
            listOf("EPISODE.ZH.ASS", "EPISODE.JA.SRT", "EPISODE.STUDY.JSON"),
            "Episode",
        )

        assertEquals("EPISODE.ZH.ASS", result.primarySubtitle)
        assertEquals("EPISODE.JA.SRT", result.secondarySubtitle)
        assertEquals("EPISODE.STUDY.JSON", result.studyData)
    }
}
