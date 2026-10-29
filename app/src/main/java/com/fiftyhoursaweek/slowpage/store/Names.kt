package com.fiftyhoursaweek.slowpage.store

/** Why a document name was refused; the messages are the iPad's (`RenameError`). */
enum class NameProblem(val message: String) {
    EMPTY("The name is empty."),
    INVALID_CHARACTER("A name cannot contain “/” or “:”."),
    LEADING_DOT("A name cannot start with a dot."),
    TOO_LONG("The name is too long."),
}

class NameException(val problem: NameProblem) : Exception(problem.message)

object Names {
    const val EXTENSION = "md"
    private const val MAX_BYTES = 255

    /**
     * The file name for [input], with [ext] kept and not doubled if typed (R1, ported from
     * `DocumentRename.validatedName`): trimmed; refuses empty, `/`, `:`, a leading dot and
     * more than 255 bytes. A NUL is refused with `/`, since no file system takes it.
     */
    fun validated(input: String, ext: String = EXTENSION): String {
        var base = input.trim()
        if (ext.isNotEmpty() && base.lowercase().endsWith(".${ext.lowercase()}")) {
            base = base.dropLast(ext.length + 1).trim()
        }
        if (base.isEmpty()) throw NameException(NameProblem.EMPTY)
        if ('/' in base || ':' in base || '\u0000' in base) throw NameException(NameProblem.INVALID_CHARACTER)
        if (base.startsWith(".")) throw NameException(NameProblem.LEADING_DOT)
        val name = if (ext.isEmpty()) base else "$base.$ext"
        if (name.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw NameException(NameProblem.TOO_LONG)
        return name
    }

    /** "Base.ext" for n = 1, then "Base 2.ext", "Base 3.ext", … (`makeUntitled`, `AutoTitle`). */
    fun numbered(base: String, ext: String, n: Int): String =
        if (n == 1) "$base.$ext" else "$base $n.$ext"

    /** Splits "Notes.md" into ("Notes", "md"); a name without a dot has no extension. */
    fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name to "" else name.substring(0, dot) to name.substring(dot + 1)
    }

    /**
     * A name for an imported file, from the provider's display name: the iPad rules, with
     * anything they refuse made acceptable (characters replaced, leading dots dropped, the
     * base shortened at a character), falling back to "Imported". Keeps `.md` and `.txt`;
     * anything else gets `.md`.
     */
    fun forImport(displayName: String?): Pair<String, String> {
        val raw = displayName?.trim().orEmpty()
        var (base, ext) = split(raw)
        if (ext.lowercase() !in setOf("md", "txt", "markdown")) {
            base = raw
            ext = EXTENSION
        }
        base = base.replace(Regex("[/:\\u0000]"), "-").trimStart('.').trim()
        if (base.isEmpty()) base = "Imported"
        // Room for " 99999." and the extension within 255 bytes.
        val room = MAX_BYTES - " 99999.".length - ext.length
        while (base.toByteArray(Charsets.UTF_8).size > room) {
            base = base.substring(0, base.offsetByCodePoints(0, base.codePointCount(0, base.length) - 1))
        }
        return base.trimEnd() to ext
    }
}
