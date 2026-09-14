package `is`.xyz.mpv

import org.json.JSONObject

data class StudyVocabulary(
    val surface: String,
    val reading: String,
    val base: String,
    val partOfSpeech: String,
    val meaning: String,
    val note: String,
)

data class StudyGrammar(
    val pattern: String,
    val meaning: String,
    val explanation: String,
)

data class StudyCue(
    val id: String,
    val start: Double,
    val end: Double,
    val loopStart: Double,
    val loopEnd: Double,
    val kind: String,
    val japanese: String,
    val originalChinese: String,
    val naturalChinese: String,
    val difficulty: String,
    val vocabulary: List<StudyVocabulary>,
    val grammar: List<StudyGrammar>,
    val listeningNotes: List<String>,
    val translationNote: String,
)

object StudyDataParser {
    fun parse(json: String): List<StudyCue> {
        val items = JSONObject(json).getJSONArray("items")
        return validate(buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val vocabularyJson = item.getJSONArray("vocabulary")
                val vocabulary = buildList(vocabularyJson.length()) {
                    for (vocabularyIndex in 0 until vocabularyJson.length()) {
                        val entry = vocabularyJson.getJSONObject(vocabularyIndex)
                        add(StudyVocabulary(
                            surface = entry.getString("surface"),
                            reading = entry.getString("reading"),
                            base = entry.getString("base"),
                            partOfSpeech = entry.getString("pos"),
                            meaning = entry.getString("meaning"),
                            note = entry.getString("note"),
                        ))
                    }
                }
                val grammarJson = item.getJSONArray("grammar")
                val grammar = buildList(grammarJson.length()) {
                    for (grammarIndex in 0 until grammarJson.length()) {
                        val entry = grammarJson.getJSONObject(grammarIndex)
                        add(StudyGrammar(
                            pattern = entry.getString("pattern"),
                            meaning = entry.getString("meaning"),
                            explanation = entry.getString("explanation"),
                        ))
                    }
                }
                val listeningJson = item.getJSONArray("listening_notes")
                val listeningNotes = buildList(listeningJson.length()) {
                    for (listeningIndex in 0 until listeningJson.length())
                        add(listeningJson.getString(listeningIndex))
                }
                add(StudyCue(
                    id = item.getString("id"),
                    start = item.getDouble("start"),
                    end = item.getDouble("end"),
                    loopStart = if (item.has("loop_start"))
                        item.getDouble("loop_start")
                    else
                        item.getDouble("start"),
                    loopEnd = if (item.has("loop_end"))
                        item.getDouble("loop_end")
                    else
                        item.getDouble("end"),
                    kind = item.getString("kind"),
                    japanese = item.getString("ja"),
                    originalChinese = item.getString("zh"),
                    naturalChinese = item.getString("natural_zh"),
                    difficulty = item.getString("difficulty"),
                    vocabulary = vocabulary,
                    grammar = grammar,
                    listeningNotes = listeningNotes,
                    translationNote = item.getString("translation_note"),
                ))
            }
        })
    }

    internal fun validate(cues: List<StudyCue>): List<StudyCue> {
        require(cues.isNotEmpty()) { "Study data contains no cues" }
        require(cues.zipWithNext().all { (first, second) -> first.start <= second.start }) {
            "Study cues are not ordered by start time"
        }
        val ids = mutableSetOf<String>()
        cues.forEach { cue ->
            require(cue.id.isNotBlank()) { "Study cue ID is blank" }
            require(ids.add(cue.id)) { "Duplicate study cue ID: ${cue.id}" }
            require(cue.start.isFinite() && cue.start >= 0.0) {
                "Invalid start time for ${cue.id}"
            }
            require(cue.end.isFinite() && cue.end > cue.start) {
                "Invalid end time for ${cue.id}"
            }
            require(cue.loopStart.isFinite() && cue.loopStart >= 0.0 && cue.loopStart <= cue.start) {
                "Invalid loop start time for ${cue.id}"
            }
            require(cue.loopEnd.isFinite() && cue.loopEnd >= cue.end) {
                "Invalid loop end time for ${cue.id}"
            }
        }
        return cues
    }
}
