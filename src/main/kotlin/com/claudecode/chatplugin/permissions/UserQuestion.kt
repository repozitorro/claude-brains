package com.claudecode.chatplugin.permissions

import com.google.gson.JsonObject

/**
 * A question Claude is asking you, arriving by the same road as a permission
 * request.
 *
 * When the CLI has somewhere to ask — which for this plugin means the approval
 * endpoint — it enables `AskUserQuestion`, and calls it through that endpoint
 * like any other tool. What arrives is not a tool call to be allowed or
 * refused: it is a fully formed question with labelled options.
 *
 *     {"tool_name":"AskUserQuestion","input":{"questions":[{
 *       "question":"Do you prefer tabs or spaces for indentation?",
 *       "header":"Indentation",
 *       "options":[{"label":"Spaces","description":"…"},
 *                  {"label":"Tabs","description":"…"}]}]}}
 *
 * Captured from CLI 2.1.232. Rendered as an ordinary approval card it read as
 * nonsense — "Run" or "Skip" in answer to *tabs or spaces* — so it is read
 * properly here instead.
 *
 * The answer travels back as a refusal carrying the choice. That is not a
 * workaround for its own sake: the tool cannot run, because running it needs a
 * terminal this chat does not have. Declining it and saying what the user chose
 * is the truthful outcome, and it is the whole answer the model needs.
 */
data class UserQuestion(
    val header: String,
    val question: String,
    val options: List<Option>,
    val multiSelect: Boolean = false
) {
    data class Option(val label: String, val description: String)

    companion object {

        const val TOOL_NAME = "AskUserQuestion"

        /**
         * A click carries one number, and a call can hold several questions, so
         * which question and which option travel packed together.
         *
         * Both the renderer that writes the number and the panel that reads it
         * come here, rather than each keeping its own copy of the arithmetic —
         * two constants with the same name in two files is how the click token
         * drifted in the first place.
         */
        private const val OPTIONS_PER_QUESTION = 100

        fun encodeChoice(questionIndex: Int, optionIndex: Int): Int =
            questionIndex * OPTIONS_PER_QUESTION + optionIndex

        /** The question and option a packed index refers to, or null if neither exists. */
        fun decodeChoice(questions: List<UserQuestion>, encoded: Int): Pair<UserQuestion, Option>? {
            val question = questions.getOrNull(encoded / OPTIONS_PER_QUESTION) ?: return null
            val option = question.options.getOrNull(encoded % OPTIONS_PER_QUESTION) ?: return null
            return question to option
        }

        /** Every question in one call — the CLI may ask several at once. */
        fun parseAll(input: JsonObject?): List<UserQuestion> =
            input?.getAsJsonArray("questions")
                ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
                ?.mapNotNull { parseOne(it) }
                .orEmpty()

        private fun parseOne(json: JsonObject): UserQuestion? {
            val question = string(json, "question") ?: return null
            val options = json.getAsJsonArray("options")
                ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
                ?.mapNotNull { option ->
                    string(option, "label")?.let { Option(it, string(option, "description").orEmpty()) }
                }
                .orEmpty()
            // Nothing to click is not a question this can put to anyone.
            if (options.isEmpty()) return null
            return UserQuestion(
                header = string(json, "header").orEmpty(),
                question = question,
                options = options,
                multiSelect = json.get("multiSelect")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            )
        }

        /**
         * What the model is told once an option is chosen.
         *
         * Phrased as the answer rather than as a refusal, because that is what
         * it is: the question was put and this is what came back.
         */
        fun answer(question: UserQuestion, option: Option): String =
            "The user answered \"${question.question}\" with: ${option.label}"

        /** And when they would rather not say. */
        fun declined(): String = "The user chose not to answer that question."

        private fun string(json: JsonObject, key: String): String? =
            json.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?.takeIf { it.isNotBlank() }
    }
}
