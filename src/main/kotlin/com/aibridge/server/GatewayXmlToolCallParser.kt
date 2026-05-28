package com.aibridge.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Result container for XML tool-call parsing. */
internal data class ParsedXmlTools(val cleanedText: String, val toolCalls: List<ToolCall>)

/**
 * Extracts `<function_calls>` blocks from model text and converts them to
 * OpenAI-style tool calls while returning the remaining visible assistant text.
 */
internal class GatewayXmlToolCallParser(
    private val mapper: ObjectMapper = jacksonObjectMapper()
) {
    fun parse(text: String): ParsedXmlTools {
        val toolCalls = mutableListOf<ToolCall>()
        val cleanedText = StringBuilder(text)
        val matches = mutableListOf<Pair<Int, Int>>()

        for ((start, end, block) in findFunctionCallBlocks(text)) {
            val parsed = parseFunctionCallBlock(block)
            if (parsed.isNotEmpty()) {
                toolCalls.addAll(parsed)
                matches.add(Pair(start, end))
            }
        }

        matches.reversed().forEach { (start, end) ->
            cleanedText.delete(start, end)
        }

        return ParsedXmlTools(cleanedText.toString().trim(), toolCalls)
    }

    private fun findFunctionCallBlocks(text: String): List<Triple<Int, Int, String>> {
        val blocks = mutableListOf<Triple<Int, Int, String>>()
        val startTag = "<function_calls>"
        val endTag = "</function_calls>"
        var cursor = 0

        while (true) {
            val start = text.indexOf(startTag, cursor)
            if (start < 0) {
                break
            }
            val endTagStart = text.indexOf(endTag, start + startTag.length)
            if (endTagStart < 0) {
                break
            }
            val end = endTagStart + endTag.length
            val inner = text.substring(start + startTag.length, endTagStart)
            blocks.add(Triple(start, end, inner))
            cursor = end
        }

        return blocks
    }

    private fun parseFunctionCallBlock(block: String): List<ToolCall> {
        val xml = "<function_calls>$block</function_calls>"
        val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }

        return try {
            val documentBuilder = documentBuilderFactory.newDocumentBuilder()
            val document = documentBuilder.parse(InputSource(StringReader(xml)))
            val root = document.documentElement
            if (root == null || root.nodeName != "function_calls") {
                return emptyList()
            }

            val parsed = mutableListOf<ToolCall>()
            val invokeNodes = root.getElementsByTagName("invoke")
            for (index in 0 until invokeNodes.length) {
                val invoke = invokeNodes.item(index) as? Element ?: continue
                val name = invoke.getAttribute("name")?.trim().orEmpty()
                if (name.isBlank()) continue

                val params = linkedMapOf<String, String>()
                val children = invoke.childNodes
                for (i in 0 until children.length) {
                    val child = children.item(i) as? Element ?: continue
                    if (child.tagName != "parameter") continue
                    val paramName = child.getAttribute("name")?.trim().orEmpty()
                    if (paramName.isBlank()) continue
                    params[paramName] = child.textContent ?: ""
                }

                parsed.add(
                    ToolCall(
                        id = "call_${UUID.randomUUID()}",
                        function = FunctionCall(name, mapper.writeValueAsString(params))
                    )
                )
            }
            parsed
        } catch (_: Exception) {
            emptyList()
        }
    }
}
