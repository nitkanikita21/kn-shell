package dev.knshell.atlas.core.entry

import dev.knshell.atlas.core.arg.ArgSnapshot

// Розкладка комбінацій для виглядів матриці; без UI, щоб перевірялась тестами.

// група Compose-вигляду; title == null, коли групувати нема за чим (менше двох axis)
data class MatrixGroup(val title: String?, val combos: List<ArgSnapshot>)

fun Variants.groups(): List<MatrixGroup> {
    if (axisArgs.size < 2) return listOf(MatrixGroup(null, combos))
    val first = axisArgs.first()
    return combos.groupBy { it[first] }.map { (value, members) -> MatrixGroup(value.toString(), members) }
}

// таблиця: cells[рядок][колонка]; null там, де комбінацію прибрав exclude
data class MatrixTable(val rows: List<String>, val columns: List<String>, val cells: List<List<ArgSnapshot?>>)

fun Variants.table(): MatrixTable {
    require(axisArgs.size == 2) { "a table needs exactly 2 axes, \"$name\" has ${axisArgs.size}" }
    val (rowArg, columnArg) = axisArgs
    val rows = combos.map { it[rowArg] }.distinct()
    // колонку, якої немає в першому рядку через exclude, все одно треба показати
    val columns = combos.map { it[columnArg] }.distinct()
    val byValues = combos.associateBy { it[rowArg] to it[columnArg] }
    return MatrixTable(
        rows.map { it.toString() },
        columns.map { it.toString() },
        rows.map { row -> columns.map { column -> byValues[row to column] } },
    )
}
