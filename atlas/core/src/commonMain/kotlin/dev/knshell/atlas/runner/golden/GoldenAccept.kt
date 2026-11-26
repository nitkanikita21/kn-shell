package dev.knshell.atlas.runner.golden

/** Which differences an accept takes over into the goldens. */
sealed interface AcceptScope {
    data class Case(val id: String) : AcceptScope
    data class Story(val story: String) : AcceptScope
    data object All : AcceptScope
}

/** The cases of [scope] that have a new picture to take over. */
fun GoldenReport.acceptable(scope: AcceptScope): List<CaseResult> = cases.filter { case ->
    (case.status == CaseStatus.Changed || case.status == CaseStatus.New) && when (scope) {
        is AcceptScope.Case -> case.id == scope.id
        is AcceptScope.Story -> case.story == scope.story
        AcceptScope.All -> true
    }
}

/**
 * Why [scope] cannot be accepted, or `null` when it can. Pictures from another build are not the
 * ones on screen; goldens drawn in two environments must not be mixed, so after a change of
 * environment only everything at once can be taken over, and only when everything rendered.
 */
fun GoldenReport.acceptBlocker(scope: AcceptScope, binaryNow: String): String? = when {
    !finished -> "перевірка ще триває"
    binary != binaryNow -> "звіт застарів: атлас перезібрано, запусти перевірку знову"
    environmentDiffers && scope != AcceptScope.All -> "інше середовище: можна лише «Прийняти все»"
    environmentDiffers && count(CaseStatus.Error) > 0 -> "інше середовище, а частина story впала: спершу виправ їх"
    acceptable(scope).isEmpty() -> "немає чого приймати"
    else -> null
}

/**
 * Takes the new pictures of [scope] over into the goldens and returns the updated report, which is
 * also written back. Throws [IllegalStateException] when [acceptBlocker] forbids it.
 */
fun accept(report: GoldenReport, scope: AcceptScope, binaryNow: String): GoldenReport {
    report.acceptBlocker(scope, binaryNow)?.let { throw IllegalStateException(it) }
    val chosen = report.acceptable(scope)
    // Read everything first, so that a missing picture leaves the goldens as they were.
    val pictures = chosen.map { case ->
        case to checkNotNull(readBytes("${report.output}/${case.actualFile}")) { "немає ${case.actualFile}" }
    }
    pictures.forEach { (case, picture) -> writeBytes("${report.goldens}/${case.path}", picture) }

    val environment = report.environment
    val newEnvironment = environment != null && (report.recorded == null || report.environmentDiffers)
    if (newEnvironment) writeText("${report.goldens}/$ENVIRONMENT_FILE", environment!!.toText())

    val taken = chosen.map { it.id }.toSet()
    val updated = report.copy(
        recorded = if (newEnvironment) environment else report.recorded,
        cases = report.cases.map { case ->
            if (case.id in taken) {
                case.copy(
                    status = CaseStatus.Same,
                    differingPixels = 0,
                    expectedWidth = case.width,
                    expectedHeight = case.height,
                )
            } else {
                case
            }
        },
    )
    writeText("${report.output}/$REPORT_FILE", updated.toJson())
    return updated
}
