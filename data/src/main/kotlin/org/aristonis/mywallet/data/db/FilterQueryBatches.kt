package org.aristonis.mywallet.data.db

private const val FILTER_ID_CHUNK = 300

internal data class FilterQueryBatch(
    val typeGroups: List<String>,
    val accountIds: List<Long>,
    val categoryIds: List<Long>,
)

/** 300 accounts plus 300 categories bound twice leaves room below old SQLite's 999 parameters. */
internal fun filterQueryBatches(
    typeGroups: List<String>,
    accountIds: List<Long>,
    categoryIds: List<Long>,
): List<FilterQueryBatch> {
    val accounts = accountIds.chunked(FILTER_ID_CHUNK).ifEmpty { listOf(emptyList()) }
    val categories = categoryIds.chunked(FILTER_ID_CHUNK).ifEmpty { listOf(emptyList()) }
    return accounts.flatMap { accountChunk ->
        categories.map { categoryChunk -> FilterQueryBatch(typeGroups, accountChunk, categoryChunk) }
    }
}

/** Each batch's top N contains every candidate for the union's top N. */
internal fun mergeFilteredHeaders(headers: List<EntryEntity>, limit: Int): List<EntryEntity> =
    headers.distinctBy { it.id }
        .sortedWith(compareByDescending<EntryEntity> { it.date }.thenByDescending { it.id })
        .take(limit)
