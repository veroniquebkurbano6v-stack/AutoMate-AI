package com.palmagent.app.channel.wechat

object WeChatMarkdown {

    fun markdownToPlainText(text: String): String {
        var result = text

        result = Regex("```[^\\n]*\\n?([\\s\\S]*?)```").replace(result) { it.groupValues[1].trim() }

        result = Regex("!\\[[^\\]]*]\\([^)]*\\)").replace(result, "")

        result = Regex("\\[([^\\]]+)]\\([^)]*\\)").replace(result) { it.groupValues[1] }

        result = Regex("^\\|[\\s:|-]+\\|$", RegexOption.MULTILINE).replace(result, "")

        result = Regex("^\\|(.+)\\|$", RegexOption.MULTILINE).replace(result) {
            it.groupValues[1].split("|").joinToString("  ") { s -> s.trim() }
        }

        result = stripMarkdown(result)

        return result
    }

    private fun stripMarkdown(text: String): String {
        var result = text

        result = Regex("\\*\\*(.+?)\\*\\*").replace(result) { it.groupValues[1] }
        result = Regex("__(.+?)__").replace(result) { it.groupValues[1] }

        result = Regex("\\*(.+?)\\*").replace(result) { it.groupValues[1] }
        result = Regex("(?<=\\s|^)_(.+?)_(?=\\s|$)").replace(result) { it.groupValues[1] }

        result = Regex("~~(.+?)~~").replace(result) { it.groupValues[1] }

        result = Regex("`(.+?)`").replace(result) { it.groupValues[1] }

        result = Regex("^#{1,6}\\s+", RegexOption.MULTILINE).replace(result, "")

        result = Regex("^>\\s?", RegexOption.MULTILINE).replace(result, "")

        result = Regex("^[-*_]{3,}$", RegexOption.MULTILINE).replace(result, "")

        result = Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE).replace(result, "")

        result = Regex("^\\s*\\d+\\.\\s+", RegexOption.MULTILINE).replace(result, "")

        return result
    }
}