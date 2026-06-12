package com.google.ai.edge.gallery.tools

import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

class TenderVectorIndex(chunks: List<Chunk>) {

    data class Chunk(
        val fileName: String,
        val text: String,
        val pageNum: Int? = null
    )

    private val documents: List<ProcessedDocument>
    private val idf: Map<String, Double>

    private class ProcessedDocument(
        val originalChunk: Chunk,
        val termFrequencies: Map<String, Double>,
        val magnitude: Double
    )

    companion object {
        private val STOP_WORDS = setOf(
            "the", "a", "an", "and", "or", "but", "if", "then", "else", "when",
            "at", "by", "for", "from", "in", "into", "of", "off", "on", "onto",
            "out", "over", "to", "up", "with", "is", "was", "were", "be", "been",
            "has", "have", "had", "do", "does", "did", "this", "that", "these", "those"
        )

        fun tokenize(text: String): List<String> {
            return text.lowercase()
                .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length > 2 && it !in STOP_WORDS }
        }
    }

    init {
        val docCount = chunks.size.toDouble().coerceAtLeast(1.0)
        val termDocCount = mutableMapOf<String, Int>()

        val docTerms = chunks.map { chunk ->
            val tokens = tokenize(chunk.text)
            val tfMap = mutableMapOf<String, Double>()
            for (token in tokens) {
                tfMap[token] = tfMap.getOrDefault(token, 0.0) + 1.0
            }
            tfMap.keys.forEach { term ->
                termDocCount[term] = termDocCount.getOrDefault(term, 0) + 1
            }
            chunk to tfMap
        }

        idf = termDocCount.mapValues { (_, count) ->
            log10(docCount / count.toDouble())
        }

        documents = docTerms.map { (chunk, tfMap) ->
            val tfIdfMap = tfMap.mapValues { (term, tf) ->
                tf * idf.getOrDefault(term, 0.0)
            }
            val magnitude = sqrt(tfIdfMap.values.sumOf { it * it })
            ProcessedDocument(chunk, tfIdfMap, magnitude)
        }
    }

    fun search(query: String, limit: Int = 3): List<Chunk> {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty() || documents.isEmpty()) {
            return documents.take(limit).map { it.originalChunk }
        }

        val queryTf = mutableMapOf<String, Double>()
        for (token in queryTokens) {
            queryTf[token] = queryTf.getOrDefault(token, 0.0) + 1.0
        }

        val queryTfIdf = queryTf.mapValues { (term, tf) ->
            tf * idf.getOrDefault(term, 0.0)
        }
        val queryMagnitude = sqrt(queryTfIdf.values.sumOf { it * it })
        if (queryMagnitude == 0.0) {
            return documents.take(limit).map { it.originalChunk }
        }

        return documents.map { doc ->
            var dotProduct = 0.0
            for ((term, queryVal) in queryTfIdf) {
                val docVal = doc.termFrequencies[term]
                if (docVal != null) {
                    dotProduct += queryVal * docVal
                }
            }
            val score = if (doc.magnitude > 0.0) dotProduct / (queryMagnitude * doc.magnitude) else 0.0
            doc to score
        }
        .sortedByDescending { it.second }
        .take(limit)
        .map { it.first.originalChunk }
    }
}
