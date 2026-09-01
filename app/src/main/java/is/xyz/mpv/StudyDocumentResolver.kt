package `is`.xyz.mpv

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

const val EXTRA_STUDY_TREE_URI = "study_tree_uri"
const val EXTRA_STUDY_PARENT_URI = "study_parent_uri"

data class StudyCompanionFiles(
    val subtitle: Uri?,
    val studyData: Uri?,
)

object StudyDocumentResolver {
    fun find(
        resolver: ContentResolver,
        treeUri: Uri,
        parentUri: Uri,
        videoUri: Uri,
    ): StudyCompanionFiles {
        val videoName = displayName(resolver, videoUri)
            ?: throw IllegalArgumentException("Video document has no display name")
        val baseName = videoName.substringBeforeLast('.', videoName)
        val parentDocumentId = if (parentUri == treeUri)
            DocumentsContract.getTreeDocumentId(parentUri)
        else
            DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentDocumentId,
        )

        val documents = mutableMapOf<String, Uri>()
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        resolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(columns[0])
            val nameColumn = cursor.getColumnIndexOrThrow(columns[1])
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn)
                val documentId = cursor.getString(idColumn)
                documents[name.lowercase()] = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    documentId,
                )
            }
        }

        val subtitleNames = listOf(
            "$baseName.zh.ass",
            "$baseName.ass",
            "$baseName.zh.srt",
            "$baseName.srt",
        )
        val subtitle = subtitleNames.firstNotNullOfOrNull { documents[it.lowercase()] }
        return StudyCompanionFiles(
            subtitle = subtitle,
            studyData = documents["$baseName.study.json".lowercase()],
        )
    }

    fun readText(resolver: ContentResolver, uri: Uri): String =
        resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("Could not open $uri")

    fun displayName(resolver: ContentResolver, uri: Uri): String? {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst())
                return cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
        return null
    }
}
