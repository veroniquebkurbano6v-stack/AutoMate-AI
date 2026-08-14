package com.palmagent.app.tool

data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val isRequired: Boolean,
    val default: Any? = null,
    val enumValues: List<String>? = null,
    val minValue: Int? = null,
    val maxValue: Int? = null
)
