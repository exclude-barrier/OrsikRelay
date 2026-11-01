package io.github.excludebarrier.orsikrelay.domain.chat

/** One line of the `/model` list: a section title or a model. */
sealed interface ModelPickerRow {
    /** "Current & recent" above everything else. */
    data object RecentHeader : ModelPickerRow

    /** All remaining models of one provider follow this title. */
    data class ProviderHeader(val provider: String) : ModelPickerRow

    /**
     * A model the user can lock. [current] marks the model this app last
     * locked for the chat; [match] is the part of [SessionModelOption.model]
     * the filter text matched (null without a filter or a provider-only match).
     */
    data class Item(
        val option: SessionModelOption,
        val current: Boolean = false,
        val match: IntRange? = null
    ) : ModelPickerRow
}

/** Recently locked models shown next to the current one. */
const val MODEL_PICKER_RECENT = 2

/**
 * The rows of the `/model` list.
 *
 * Without [query]: the current model ([current], a qualified id) and up to
 * [MODEL_PICKER_RECENT] of [recent] (newest first) under one title, then
 * every other model grouped by provider in catalog order.
 *
 * With [query]: the models whose `provider/model` contains it (ignoring
 * case), matches at a word start first, otherwise in catalog order. No
 * titles.
 */
fun modelPickerRows(
    query: String,
    options: List<SessionModelOption>,
    current: String?,
    recent: List<String>
): List<ModelPickerRow> {
    val needle = query.trim().lowercase()
    if (needle.isNotEmpty()) {
        val (wordStart, inside) = options.mapNotNull { option ->
            val haystack = "${option.provider}/${option.model}".lowercase()
            val index = haystack.indexOf(needle)
            if (index < 0) null else option to (index == 0 || haystack[index - 1] in WORD_BREAKS)
        }.partition { it.second }
        return (wordStart + inside).map { (option, _) ->
            ModelPickerRow.Item(option, current = option.qualified == current, match = matchIn(option.model, needle))
        }
    }
    val byId = options.associateBy { it.qualified }
    val top = buildList {
        current?.let(byId::get)?.let(::add)
        recent.asSequence()
            .filter { it != current }
            .mapNotNull(byId::get)
            .take(MODEL_PICKER_RECENT)
            .forEach(::add)
    }
    val rows = mutableListOf<ModelPickerRow>()
    if (top.isNotEmpty()) {
        rows += ModelPickerRow.RecentHeader
        top.forEach { rows += ModelPickerRow.Item(it, current = it.qualified == current) }
    }
    val shown = top.map { it.qualified }.toSet()
    options.filter { it.qualified !in shown }
        .groupBy { it.provider }
        .forEach { (provider, models) ->
            rows += ModelPickerRow.ProviderHeader(provider)
            models.forEach { rows += ModelPickerRow.Item(it) }
        }
    return rows
}

/** The selectable models of [rows], in order (for ↑/↓ and Enter). */
fun List<ModelPickerRow>.items(): List<ModelPickerRow.Item> = filterIsInstance<ModelPickerRow.Item>()

/** The filter text of a `/model ` draft, or null when the draft is not one. */
fun modelPickerQuery(draft: String): String? =
    if (draft.startsWith(MODEL_PREFIX)) draft.removePrefix(MODEL_PREFIX) else null

const val MODEL_PREFIX = "/model "

private val WORD_BREAKS = setOf('/', '-', '_', '.', ':', ' ')

private fun matchIn(model: String, needle: String): IntRange? {
    val index = model.lowercase().indexOf(needle)
    return if (index < 0) null else index until index + needle.length
}
