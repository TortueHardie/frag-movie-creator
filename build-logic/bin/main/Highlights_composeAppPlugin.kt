/**
 * Precompiled [highlights.compose-app.gradle.kts][Highlights_compose_app_gradle] script plugin.
 *
 * @see Highlights_compose_app_gradle
 */
public
class Highlights_composeAppPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Highlights_compose_app_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
