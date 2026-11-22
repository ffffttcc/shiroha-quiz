package com.yiqiu.shirohaquiz.sync.webdav

/**
 * 改凭据的纯逻辑：是否需要迁移、请求体与端点构造。
 *
 * 之所以要「迁移」而不是简单改密码：task-31 之后密钥材料是 `用户名\u0000口令`，
 * 改任一项都等于换钥匙，云端旧对象必须用新钥匙重新加密覆盖。
 */
object WebDavRotation {
    const val MODE_ADD = "add"
    const val MODE_FINALIZE = "finalize"

    /** 凭据是否真的变了。用户名也参与密钥派生，所以用户名变化同样返回 true。 */
    fun needed(
        oldUsername: String,
        oldPassword: String,
        newUsername: String,
        newPassword: String
    ): Boolean = oldUsername != newUsername || oldPassword != newPassword

    fun endpoint(baseUrl: String): String = baseUrl.trimEnd('/') + "/admin/rotate"

    fun body(mode: String, username: String, oldPassword: String, newPassword: String): String = buildString {
        append("{")
        append("\"mode\":\"").append(escape(mode)).append("\",")
        append("\"username\":\"").append(escape(username)).append("\",")
        append("\"oldPassword\":\"").append(escape(oldPassword)).append("\",")
        append("\"newPassword\":\"").append(escape(newPassword)).append("\"")
        append("}")
    }

    /**
     * 只有「新口令已加到服务器」且「云端对象已用新钥匙重加密成功」才允许摘旧口令。
     * 任一为 false 都必须停下——旧口令继续有效，用户可重试，绝不会把自己锁在外面。
     */
    fun canFinalize(addSucceeded: Boolean, resyncSucceeded: Boolean): Boolean =
        addSucceeded && resyncSucceeded

    private fun escape(raw: String): String = buildString {
        for (ch in raw) when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
}
