package com.claudecode.chatplugin.permissions

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A question from Claude, read as one.
 *
 * Configuring a permission tool is what makes the CLI enable `AskUserQuestion`,
 * and it then calls it down the same road. Rendered as an ordinary approval
 * card that read as nonsense — **Run** or **Skip** in answer to *tabs or
 * spaces* — which is what the plugin shipped in 0.10.4.
 *
 * The payload below is verbatim from CLI 2.1.232.
 */
class UserQuestionTest {

    private fun input(json: String) = JsonParser.parseString(json).asJsonObject

    private val real = input(
        """
        {"questions":[{
          "question":"Do you prefer tabs or spaces for indentation?",
          "header":"Indentation",
          "options":[
            {"label":"Spaces","description":"Indent with space characters."},
            {"label":"Tabs","description":"Indent with tab characters."}],
          "multiSelect":false}]}
        """
    )

    @Test
    fun `the question, its heading and its options are all read`() {
        val question = UserQuestion.parseAll(real).single()
        assertEquals("Indentation", question.header)
        assertEquals("Do you prefer tabs or spaces for indentation?", question.question)
        assertEquals(listOf("Spaces", "Tabs"), question.options.map { it.label })
        assertEquals("Indent with space characters.", question.options[0].description)
    }

    @Test
    fun `a choice survives the trip through one number`() {
        // A click carries a single index, and a call can hold several questions.
        val questions = UserQuestion.parseAll(real)
        val (question, option) = UserQuestion.decodeChoice(questions, UserQuestion.encodeChoice(0, 1))!!
        assertEquals("Tabs", option.label)
        assertEquals("Indentation", question.header)
    }

    @Test
    fun `several questions in one call keep their own options`() {
        val two = input(
            """
            {"questions":[
              {"question":"First?","options":[{"label":"A"},{"label":"B"}]},
              {"question":"Second?","options":[{"label":"C"},{"label":"D"}]}]}
            """
        )
        val questions = UserQuestion.parseAll(two)
        assertEquals(2, questions.size)
        assertEquals("D", UserQuestion.decodeChoice(questions, UserQuestion.encodeChoice(1, 1))!!.second.label)
        assertEquals("A", UserQuestion.decodeChoice(questions, UserQuestion.encodeChoice(0, 0))!!.second.label)
    }

    @Test
    fun `an index pointing at nothing answers nothing`() {
        val questions = UserQuestion.parseAll(real)
        assertNull(UserQuestion.decodeChoice(questions, UserQuestion.encodeChoice(9, 0)))
        assertNull(UserQuestion.decodeChoice(questions, UserQuestion.encodeChoice(0, 9)))
    }

    @Test
    fun `a question with no options is not a question anyone can answer`() {
        assertTrue(UserQuestion.parseAll(input("""{"questions":[{"question":"Well?","options":[]}]}""")).isEmpty())
        assertTrue(UserQuestion.parseAll(input("""{"questions":[{"options":[{"label":"A"}]}]}""")).isEmpty())
    }

    @Test
    fun `something that is not a question at all yields none`() {
        assertTrue(UserQuestion.parseAll(input("""{"command":"npm test"}""")).isEmpty())
        assertTrue(UserQuestion.parseAll(null).isEmpty())
    }

    @Test
    fun `the model is told the answer, not that it was refused`() {
        // It travels back as a refusal — running the tool needs a terminal this
        // chat has not got — but what the model reads is the answer.
        val question = UserQuestion.parseAll(real).single()
        assertEquals(
            "The user answered \"Do you prefer tabs or spaces for indentation?\" with: Tabs",
            UserQuestion.answer(question, question.options[1])
        )
    }
}
