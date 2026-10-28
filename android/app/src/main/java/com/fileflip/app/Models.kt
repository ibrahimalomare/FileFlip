package com.fileflip.app

import java.io.File

data class InputFile(val file: File, val name: String, val kind: String, val size: Long, val rotation: Int = 0)
data class ResultFile(val file: File, val warnings: String = "", val preview: String = "")
data class Options(
    val target: String = "pdf", val pages: String = "", val pageSize: String = "A4",
    val landscape: Boolean = false, val margin: Int = 24, val background: String = "#FFFFFF",
    val imageMode: String = "embed", val sheets: String = "", val range: String = "", val language: String = "eng"
)
object Rules {
    const val MAX_BYTES = 50L * 1024 * 1024
    fun outputs(files: List<InputFile>): List<String> {
        if (files.isEmpty()) return emptyList()
        if (files.all { it.kind in listOf("jpg", "png") }) return listOf("pdf", "docx", "xlsx", "jpg", "png")
        if (files.size > 1) return emptyList()
        return when(files[0].kind) {
            "pdf" -> listOf("docx", "xlsx", "png", "jpg")
            "doc", "docx" -> listOf("pdf", "xlsx", "png", "jpg")
            "xls", "xlsx" -> listOf("pdf", "docx", "png", "jpg")
            else -> emptyList()
        }
    }
    fun local(files: List<InputFile>, o: Options): Boolean =
        (files.all { it.kind in listOf("jpg", "png") } && (o.target in listOf("pdf", "jpg", "png") || (o.target == "docx" && o.imageMode == "embed"))) ||
            (files.size == 1 && files[0].kind == "pdf" && o.target in listOf("jpg", "png"))
    fun pages(spec: String, count: Int): List<Int> {
        require(count in 1..100) { "PDF must contain 1–100 pages. Split larger documents first." }
        if(spec.isBlank()) return (0 until count).toList()
        return spec.split(',').flatMap { part ->
            val ends = part.trim().split('-')
            require(ends.size in 1..2) { "Use page numbers such as 1,3-5." }
            val a = ends.first().toIntOrNull() ?: error("Invalid page selection.")
            val b = ends.last().toIntOrNull() ?: error("Invalid page selection.")
            require(a in 1..count && b in a..count) { "Page selection is outside the document." }
            (a-1 until b).toList()
        }.distinct()
    }
    fun mime(ext: String) = when(ext) {
        "pdf" -> "application/pdf"; "png" -> "image/png"; "jpg" -> "image/jpeg"; "zip" -> "application/zip"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "application/octet-stream"
    }
}
