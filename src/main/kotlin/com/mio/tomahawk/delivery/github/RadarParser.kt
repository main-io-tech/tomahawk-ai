package com.mio.tomahawk.delivery.github

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.mio.tomahawk.delivery.PrComment
import com.mio.tomahawk.delivery.PrThread
import com.mio.tomahawk.delivery.ThreadKind

/** As conversas do PR a partir do GraphQL do GitHub, da mais recente para a mais antiga. */
internal object RadarParser {

    private const val COMMENT = "databaseId body createdAt url author { login }"

    val QUERY = """
        query(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!) {
          repository(owner: ${'$'}owner, name: ${'$'}name) {
            pullRequest(number: ${'$'}number) {
              reviewThreads(first: 100) {
                nodes {
                  id path line originalLine isResolved isOutdated
                  comments(first: 100) { nodes { $COMMENT diffHunk } }
                }
              }
              comments(last: 100) { nodes { $COMMENT } }
              reviews(last: 100) { nodes { $COMMENT state submittedAt } }
            }
          }
        }
    """.trimIndent()

    fun threads(pullRequest: JsonObject): List<PrThread> {
        val code = nodes(pullRequest.getAsJsonObject("reviewThreads")).mapNotNull { thread ->
            val comments = nodes(thread.getAsJsonObject("comments"))
            val first = comments.firstOrNull() ?: return@mapNotNull null
            PrThread(
                key = "t:${first.long("databaseId")}",
                kind = ThreadKind.CODE,
                comments = comments.map { comment("c", it) },
                replyTo = first.long("databaseId"),
                path = thread.str("path"),
                line = thread.int("line") ?: thread.int("originalLine"),
                diffHunk = first.str("diffHunk"),
                resolved = thread.bool("isResolved"),
                outdated = thread.bool("isOutdated"),
                nodeId = thread.str("id"),
            )
        }
        val conversation = nodes(pullRequest.getAsJsonObject("comments")).map { node ->
            val comment = comment("i", node)
            PrThread("i:${comment.id}", ThreadKind.CONVERSATION, listOf(comment), comment.id)
        }
        val reviews = nodes(pullRequest.getAsJsonObject("reviews")).mapNotNull { node ->
            val state = node.str("state")
            if (state == "PENDING") return@mapNotNull null
            val comment = comment("r", node, node.str("submittedAt"))
            if (comment.body.isBlank() && state != "CHANGES_REQUESTED" && state != "APPROVED") return@mapNotNull null
            PrThread("r:${comment.id}", ThreadKind.REVIEW, listOf(comment), comment.id, reviewState = state)
        }
        return (code + conversation + reviews).sortedByDescending { it.last.createdAt }
    }

    private fun comment(prefix: String, node: JsonObject, date: String? = null): PrComment {
        val id = node.long("databaseId")
        return PrComment(
            key = "$prefix:$id",
            id = id,
            author = node.getAsJsonObject("author")?.str("login") ?: "fantasma",
            body = node.str("body").orEmpty(),
            createdAt = date ?: node.str("createdAt").orEmpty(),
            url = node.str("url").orEmpty(),
        )
    }

    private fun nodes(connection: JsonObject?): List<JsonObject> =
        connection?.getAsJsonArray("nodes")?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonObject.str(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.asString

    private fun JsonObject.int(key: String): Int? = get(key)?.takeUnless { it is JsonNull }?.asInt

    private fun JsonObject.long(key: String): Long = get(key)?.takeUnless { it is JsonNull }?.asLong ?: 0L

    private fun JsonObject.bool(key: String): Boolean = get(key)?.takeUnless { it is JsonNull }?.asBoolean == true
}
