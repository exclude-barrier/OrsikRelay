package io.github.excludebarrier.orsikrelay.domain.chat

/** The catalog contributes only identifiers, never provider URLs or auth metadata. */
data class SessionModelOption(val provider: String, val model: String) {
    val qualified: String get() = "$provider::$model"
    fun isValid(): Boolean = provider.matches(Regex("[A-Za-z0-9_.-]{1,80}")) &&
        model.isNotBlank() && model.length <= 200 && model.none { it.isISOControl() || it.isWhitespace() } &&
        "::" !in model
}

sealed interface ModelOptionsResult {
    data class Loaded(val options: List<SessionModelOption>) : ModelOptionsResult
    data object Failed : ModelOptionsResult
}

fun interface ModelOptionsSource {
    fun loadModels(profileId: String): ModelOptionsResult
}

fun modelSuggestions(text: String, options: List<SessionModelOption>): List<SessionModelOption> {
    if (!text.startsWith("/model ")) return emptyList()
    val argument = text.removePrefix("/model ").trim()
    if (argument.isNotEmpty() && text.last().isWhitespace()) return emptyList()
    return options.filter { it.qualified.contains(argument, ignoreCase = true) }.take(40)
}
