plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

/**
 * Fails the build if a Kotlin source file contains a raw NUL byte.
 *
 * Protocol code here has to spell out C-style string terminators: the SPAKE2
 * role names and the TLS exporter label are both `sizeof()`-length and carry
 * one. Written as a unicode escape that is fine. Written as an actual 0x00 byte
 * it still compiles and means exactly the same thing, which is what makes it
 * dangerous - git and grep then treat the file as binary, so it drops out of
 * diffs and searches without announcing it, and the tooling that edits these
 * files has reintroduced the raw byte more than once.
 *
 * Catching it at build time is the cheap end of that trade: the escape is
 * always available, so there is no legitimate reason for the raw byte.
 */
val verifyNoNulBytes by tasks.registering {
    group = "verification"
    description = "Rejects raw NUL bytes in Kotlin sources; use a unicode escape instead."

    // Resolved here rather than inside the action: the configuration cache
    // cannot serialise a reference back to the build script, which is what a
    // lambda reaching for `rootDir` or `subprojects` quietly captures.
    val kotlinSources = files(
        subprojects.map {
            it.layout.projectDirectory.dir("src").asFileTree.matching { include("**/*.kt") }
        },
    )
    val root = rootDir
    inputs.files(kotlinSources)

    doLast {
        val offenders = kotlinSources.files.filter { it.readBytes().contains(0) }
        if (offenders.isNotEmpty()) {
            val list = offenders.joinToString(separator = System.lineSeparator() + "  ") {
                it.relativeTo(root).path
            }
            throw GradleException(
                "Raw NUL byte in Kotlin source; use a unicode escape instead:" +
                    System.lineSeparator() + "  " + list,
            )
        }
    }
}

subprojects {
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(verifyNoNulBytes)
    }
}
