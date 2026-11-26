package dev.knshell.atlas.core.entry

// як розкладати комбінації матриці
enum class MatrixView(val title: String) {
    // групи за першою axis, превʼю з назвами, як у Compose Preview
    Compose("Compose"),
    // перша axis — рядки, друга — колонки
    Table("Table");

    fun supports(axisCount: Int): Boolean = this != Table || axisCount == 2
}
