package atlas

import dev.knshell.atlas.runner.atlasMain
import stories.support.environments

fun main(args: Array<String>) = atlasMain(kitStories, args, title = "shell-ui-kit", environments = environments)
