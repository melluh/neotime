package com.melluh.neotime.timeedit

object Fixtures {
    fun read(path: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$path")) { "missing fixture $path" }.readText()
}
