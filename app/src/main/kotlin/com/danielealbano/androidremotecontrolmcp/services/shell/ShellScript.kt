package com.danielealbano.androidremotecontrolmcp.services.shell

import java.io.ByteArrayOutputStream
import java.io.InputStream

private const val NEWLINE = '\n'.code
private const val HEADER_MAX_BYTES = 32
private const val EXIT_CANNOT_ENTER_WORKDIR = 126
private const val NUL = '\u0000'

data class ShellHeader(
    val pgid: Int,
    val uid: Int,
) {
    fun line(): ByteArray = "$pgid $uid\n".toByteArray(Charsets.US_ASCII)
}

class ShellHeaderRead(
    val header: ShellHeader?,
    val leftover: ByteArray,
)

class ShellScriptBuilder(
    private val shellPath: String = ANDROID_SHELL_PATH,
    private val setsidPath: String = ANDROID_SETSID_PATH,
    private val idPath: String = ANDROID_ID_PATH,
) {
    fun build(
        command: String,
        workdir: String,
        envs: Map<String, String>,
    ): String {
        val inner =
            "echo \"\$\$ \$($idPath -u)\"; umask 0007; cd -- \"\$1\" || exit $EXIT_CANNOT_ENTER_WORKDIR; " +
                "exec $shellPath -c \"\$2\" </dev/null"
        val exports = envs.entries.joinToString("") { (name, value) -> "export $name=${quoteForShell(value)}\n" }
        return exports +
            "exec $setsidPath -w $shellPath -c ${quoteForShell(inner)} sh " +
            "${quoteForShell(workdir)} ${quoteForShell(command)}\n"
    }

    companion object {
        const val ANDROID_SHELL_PATH = "/system/bin/sh"
        const val ANDROID_SETSID_PATH = "/system/bin/setsid"
        const val ANDROID_ID_PATH = "/system/bin/id"
        private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        fun isValidEnvName(name: String): Boolean = ENV_NAME.matches(name)

        fun hasNul(value: String): Boolean = value.contains(NUL)

        fun quoteForShell(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        fun readHeader(input: InputStream): ShellHeaderRead {
            val consumed = ByteArrayOutputStream()
            var next = input.read()
            while (next != -1 && next != NEWLINE && consumed.size() < HEADER_MAX_BYTES) {
                consumed.write(next)
                next = input.read()
            }
            val header = if (next == NEWLINE) parseHeader(String(consumed.toByteArray(), Charsets.US_ASCII)) else null
            if (header == null && next != -1) consumed.write(next)
            return ShellHeaderRead(header, if (header == null) consumed.toByteArray() else ByteArray(0))
        }

        private fun parseHeader(line: String): ShellHeader? {
            val parts = line.trim().split(' ')
            val pgid = parts.getOrNull(0)?.toIntOrNull()
            val uid = parts.getOrNull(1)?.toIntOrNull()
            return if (parts.size == 2 && pgid != null && uid != null) ShellHeader(pgid, uid) else null
        }
    }
}
