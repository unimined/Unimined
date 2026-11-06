package xyz.wagyourtail.unimined.api.util

import org.gradle.api.Project

/**
 * Run [action] once the project's DSL has been evaluated — via
 * [Project.afterEvaluate] when called during evaluation, or
 * immediately when the project is already evaluated. Environment
 * binding can fire post-evaluation (envs declared by other plugins'
 * `afterEvaluate`, init scripts, test fixtures), where bare
 * `afterEvaluate` throws "project is already evaluated".
 */
fun Project.afterDslEval(action: (Project) -> Unit) {
    if (state.executed) action(this) else afterEvaluate(action)
}
