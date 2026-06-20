// Init script (NOT a tracked build file). Publishes the compile-time dependencies the extensions
// repo needs, to mavenLocal (~/.m2 only):
//   - :source-api      -> com.github.Blackyfi.aniyomi-revived:source-api(-android)
//   - :core:common     -> Aniyomi.core:common  (carries GET/parseAs/rateLimit/NetworkHelper)
// Uses reflection so it does not need the Kotlin Gradle plugin types on the init classpath.
gradle.beforeProject {
    if (path == ":source-api") {
        pluginManager.apply("maven-publish")
        group = "com.github.Blackyfi.aniyomi-revived"

        // androidTarget() is declared in the project's own build script, so configure the android
        // library variant for publishing in an afterEvaluate registered here (runs before the KMP
        // plugin's own afterEvaluate, where publishLibraryVariants is read).
        afterEvaluate {
            val kmp = extensions.findByName("kotlin") ?: return@afterEvaluate
            val targets = kmp.javaClass.getMethod("getTargets").invoke(kmp) as Iterable<*>
            targets.forEach { target ->
                if (target != null && target.javaClass.name.contains("KotlinAndroidTarget")) {
                    target.javaClass
                        .getMethod("publishLibraryVariants", Array<String>::class.java)
                        .invoke(target, arrayOf("release"))
                }
            }
        }
    }

    if (path == ":core:common") {
        pluginManager.apply("maven-publish")
        // Register a single "release" publishable variant so a `release` software component exists.
        pluginManager.withPlugin("com.android.library") {
            val android = extensions.getByName("android")
            val publishingExt = android.javaClass.getMethod("getPublishing").invoke(android)
            publishingExt.javaClass
                .getMethod("singleVariant", String::class.java)
                .invoke(publishingExt, "release")
        }
        // AGP creates the `release` software component in its own afterEvaluate, which runs after
        // this init script's afterEvaluate. React when the component is actually added instead.
        components.all {
            val component = this
            if (component.name == "release") {
                val publishing =
                    extensions.getByType(org.gradle.api.publish.PublishingExtension::class.java)
                if (publishing.publications.findByName("release") == null) {
                    publishing.publications.create(
                        "release",
                        org.gradle.api.publish.maven.MavenPublication::class.java,
                    ) {
                        from(component)
                    }
                }
            }
        }
    }
}
