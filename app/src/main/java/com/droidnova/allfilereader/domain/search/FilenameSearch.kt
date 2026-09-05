package com.droidnova.allfilereader.domain.search

import com.droidnova.allfilereader.domain.model.DocumentClassifier
import com.droidnova.allfilereader.domain.model.DocumentFile
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

object FilenameSearch {
    const val MAX_QUERY_LENGTH = 200
    fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    fun query(value: String) = value.take(MAX_QUERY_LENGTH).trim().let(::normalize)

    fun search(source: List<DocumentFile>, normalizedQuery: String): List<DocumentFile> {
        if (normalizedQuery.isEmpty()) return source.filter(DocumentClassifier::isVisibleDocument)
        val terms = normalizedQuery.split(Regex("\\s+")).filter(String::isNotEmpty)
        return source.asSequence().filter(DocumentClassifier::isVisibleDocument).mapNotNull { file ->
            val name = normalize(file.displayName)
            if (!terms.all(name::contains)) return@mapNotNull null
            val stem = name.substringBeforeLast('.', name)
            val score = when { name == normalizedQuery -> 0; stem == normalizedQuery -> 1
                name.startsWith(normalizedQuery) -> 2; tokenStarts(name, normalizedQuery) -> 3
                name.contains(normalizedQuery) -> 4; else -> 5 }
            Triple(file, score, name)
        }.sortedWith(compareBy<Triple<DocumentFile,Int,String>> { it.second }
            .thenByDescending { it.first.lastModifiedEpochMillis }.thenBy { it.third }.thenBy { it.first.id })
            .map { it.first }.toList()
    }

    /** Maps normalized matches back through Unicode grapheme boundaries to safe original ranges. */
    fun matchRanges(original: String, rawQuery: String): List<IntRange> {
        val terms = query(rawQuery).split(Regex("\\s+")).filter(String::isNotEmpty)
        if (terms.isEmpty()) return emptyList()
        val boundaries = mutableListOf(0); val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(original); var boundary = iterator.first()
        while (boundary != BreakIterator.DONE) { if (boundaries.lastOrNull() != boundary) boundaries += boundary; boundary = iterator.next() }
        val normalized = StringBuilder(); val map = mutableListOf<Int>()
        for (index in 0 until boundaries.lastIndex) {
            val start=boundaries[index]; val end=boundaries[index+1]; val part=normalize(original.substring(start,end))
            part.indices.forEach { map += start }; normalized.append(part)
        }
        map += original.length
        val ranges=mutableListOf<IntRange>()
        terms.forEach { term -> var from=0; while(from<=normalized.length-term.length){ val hit=normalized.indexOf(term,from); if(hit<0)break
            val start=map[hit]; val end=if(hit+term.length<map.size) map[hit+term.length] else original.length
            if(end>start) ranges += start until end; from=hit+term.length.coerceAtLeast(1) } }
        return ranges.sortedBy(IntRange::first).fold(mutableListOf()) { result, range ->
            if(result.isNotEmpty() && range.first<=result.last().last+1) result[result.lastIndex]=result.last().first..maxOf(result.last().last,range.last) else result+=range; result }
    }
    private fun tokenStarts(name:String,query:String)=name.indices.any { (it==0||!name[it-1].isLetterOrDigit())&&name.startsWith(query,it) }
}
