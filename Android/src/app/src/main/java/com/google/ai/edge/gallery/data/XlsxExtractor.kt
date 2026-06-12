package com.google.ai.edge.gallery.data

import android.util.Log
import org.apache.poi.openxml4j.util.ZipSecureFile
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File

object XlsxExtractor {
    fun extractText(file: File): String {
        return try {
            ZipSecureFile.setMinInflateRatio(0.0)
            
            file.inputStream().use { inputStream ->
                XSSFWorkbook(inputStream).use { workbook ->
                    val sb = StringBuilder()
                    val formatter = DataFormatter()
                    for (s in 0 until workbook.numberOfSheets) {
                        val sheet = workbook.getSheetAt(s)
                        sb.append("--- Sheet: ${sheet.sheetName} ---\n")
                        for (row in sheet) {
                            val rowCells = mutableListOf<String>()
                            val maxCell = row.lastCellNum
                            for (c in 0 until maxCell) {
                                val cell = row.getCell(c)
                                if (cell == null) {
                                    rowCells.add("")
                                } else {
                                    val cellValue = formatter.formatCellValue(cell)
                                    rowCells.add(cellValue.trim())
                                }
                            }
                            if (rowCells.any { it.isNotBlank() }) {
                                sb.append(rowCells.joinToString("\t")).append("\n")
                            }
                        }
                        sb.append("\n")
                    }
                    sb.toString()
                }
            }
        } catch (e: Exception) {
            Log.e("XlsxExtractor", "Failed to extract XLSX text from ${file.name}", e)
            ""
        }
    }
}

