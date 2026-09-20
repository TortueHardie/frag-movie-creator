/**
 * Precompiled [highlights.kotlin-library.gradle.kts][Highlights_kotlin_library_gradle] script plugin.
 *
 * @see Highlights_kotlin_library_gradle
 */
public
class Highlights_kotlinLibraryPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Highlights_kotlin_library_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
