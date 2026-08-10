package dev.foldcode.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectHeaderCompletionTest {
    @Test
    fun emptyQuotedIncludeShowsProjectHeadersOnly() {
        val source = "#include \"\""
        val result = projectHeaderCompletions(
            source = source,
            cursor = source.length - 1,
            activeFile = "main.cpp",
            projectFiles = setOf(
                "main.cpp",
                "sum.h",
                "include/analytics.hpp",
                "src/private.hxx",
                "README.md",
            ),
        )

        assertEquals(listOf("analytics.hpp", "sum.h", "src/private.hxx"), result.map { it.label })
        assertEquals("analytics.hpp\"", result.first().insertion)
    }

    @Test
    fun nestedPrefixReplacesOnlyHeaderName() {
        val source = "#include \"src/pr"
        val result = projectHeaderCompletions(
            source,
            source.length,
            "main.cpp",
            setOf("main.cpp", "src/private.hxx", "src/public.hpp"),
        )

        assertEquals(listOf("src/private.hxx"), result.map { it.label })
        assertEquals("private.hxx\"", result.single().insertion)
    }

    @Test
    fun symbolsFromIncludedProjectLibraryAreImmediatelyAvailable() {
        val result = includedCppSymbolCompletions(
            source = "#include \"sum.h\"\nint main() { return su; }",
            activeFile = "src/main.cpp",
            projectFiles = mapOf(
                "src/main.cpp" to "",
                "include/sum.h" to """
                    #pragma once
                    #define SUM_LIMIT 32
                    struct SumResult { int value; };
                    int sum_values(int left, int right);
                """.trimIndent(),
            ),
        )

        assertTrue(result.any { it.label == "SUM_LIMIT" && it.category == "macro" })
        assertTrue(result.any { it.label == "SumResult" && it.category == "class" })
        assertTrue(result.any { it.label == "sum_values" && it.insertion == "sum_values(" })
    }

    @Test
    fun completionCategoryIsShortAndConsistent() {
        assertEquals("function", CompletionItem("sum", "sum(", "int sum(int, int)").displayCategory())
        assertEquals("header", CompletionItem("vector", "vector>", "C++ standard header").displayCategory())
        assertEquals("keyword", CompletionItem("def", "def ", "Python keyword").displayCategory())
    }

    @Test
    fun clangdOverloadLabelsCollapseToOneSearchableSymbol() {
        assertEquals("operator<<", normalizeClangdCompletionLabel("std::operator<<(basic_ostream<char>&, int)"))
        assertEquals("push_back", normalizeClangdCompletionLabel("std::vector<int>::push_back(const int&)"))
        assertEquals("operator()", normalizeClangdCompletionLabel("std::function<void()>::operator()()"))
    }

    @Test
    fun completionMatchingRefinesFromFirstCharacterAndSupportsFuzzyInput() {
        assertTrue(completionMatchScore("vector", "v") != null)
        assertTrue(completionMatchScore("vector", "vec")!! < completionMatchScore("move", "ve")!!)
        assertTrue(completionMatchScore("vector", "vtr") != null)
        assertEquals(null, completionMatchScore("vector", "xyz"))
    }

    @Test
    fun emptyScopeCompletionPreservesSemanticEngineRelevanceOrder() {
        val semanticOrder = listOf(
            CompletionItem("cout", "cout", "std::ostream"),
            CompletionItem("cin", "cin", "std::istream"),
            CompletionItem("addressof", "addressof(", "function"),
            CompletionItem("allocator_arg", "allocator_arg", "variable"),
        )

        val result = rankSemanticCompletionItems(
            items = semanticOrder,
            prefix = "",
            preserveSourceOrder = true,
        )

        assertEquals(listOf("cout", "cin", "addressof", "allocator_arg"), result.map { it.label })
    }

    @Test
    fun emptyOperatorCompletionIsDetectedBeforePublishingFallbacks() {
        assertTrue(isEmptyOperatorCompletion("std::", 5, "main.cpp"))
        assertTrue(isEmptyOperatorCompletion("value->", 7, "main.cpp"))
        assertEquals(false, isEmptyOperatorCompletion("std::co", 7, "main.cpp"))
        assertEquals(false, isEmptyOperatorCompletion("value", 5, "main.cpp"))
    }
}
