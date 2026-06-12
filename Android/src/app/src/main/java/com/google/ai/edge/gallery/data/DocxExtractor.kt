package com.google.ai.edge.gallery.data

import android.util.Log
import org.apache.poi.openxml4j.util.ZipSecureFile
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable
import java.io.File

object DocxExtractor {
    fun extractText(file: File): String {
        return try {
            // Government DOCX files frequently contain EMF/WMF images that compress
            // at ratios below POI's default 1% threshold (false-positive "zip bomb").
            // We set the ratio to 0 (disabled) since these are trusted local tender files.
            ZipSecureFile.setMinInflateRatio(0.0)

            file.inputStream().use { inputStream ->
                XWPFDocument(inputStream).use { doc ->
                    val sb = StringBuilder()
                    for (element in doc.bodyElements) {
                        when (element) {
                            is XWPFParagraph -> {
                                val text = element.text
                                if (text.isNotBlank()) {
                                    sb.append(text.trim()).append("\n")
                                }
                            }
                            is XWPFTable -> {
                                for (row in element.rows) {
                                    val rowCells = row.tableCells.map { it.text.trim() }
                                    if (rowCells.any { it.isNotBlank() }) {
                                        sb.append(rowCells.joinToString("\t")).append("\n")
                                    }
                                }
                                sb.append("\n")
                            }
                        }
                    }
                    sb.toString()
                }
            }
        } catch (e: Exception) {
            Log.e("DocxExtractor", "Failed to extract DOCX text from ${file.name}", e)
            ""
        }
    }
}

