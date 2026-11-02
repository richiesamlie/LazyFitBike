// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.kotlin.serialization) apply false
}

fun registerVersionBumpTask(taskName: String, descriptionText: String, bumpTransform: (Int, Int, Int) -> Triple<Int, Int, Int>) {
  val propsFile = layout.projectDirectory.file("gradle.properties").asFile
  tasks.register(taskName) {
    group = "versioning"
    description = descriptionText
    doLast {
      val content = propsFile.readText()
      val codeRegex = Regex("""(?m)^appVersionCode\s*=\s*(\d+)\s*$""")
      val nameRegex = Regex("""(?m)^appVersionName\s*=\s*(\d+)\.(\d+)\.(\d+)\s*$""")

      val codeMatch = codeRegex.find(content)
        ?: error("Could not find appVersionCode in gradle.properties")
      val nameMatch = nameRegex.find(content)
        ?: error("Could not find appVersionName=X.Y.Z in gradle.properties")

      val nextCode = codeMatch.groupValues[1].toInt() + 1
      val (maj, min, pat) = bumpTransform(
        nameMatch.groupValues[1].toInt(),
        nameMatch.groupValues[2].toInt(),
        nameMatch.groupValues[3].toInt()
      )
      val nextName = "$maj.$min.$pat"

      val updated = content
        .replace(codeRegex, "appVersionCode=$nextCode")
        .replace(nameRegex, "appVersionName=$nextName")
      propsFile.writeText(updated)
      println("Bumped version to v$nextName (versionCode $nextCode) in gradle.properties")
    }
  }
}

registerVersionBumpTask("bumpPatch", "Increments appVersionCode and patch version (X.Y.Z -> X.Y.Z+1)") { maj, min, pat ->
  Triple(maj, min, pat + 1)
}

registerVersionBumpTask("bumpMinor", "Increments appVersionCode and minor version (X.Y.Z -> X.Y+1.0)") { maj, min, _ ->
  Triple(maj, min + 1, 0)
}

registerVersionBumpTask("bumpMajor", "Increments appVersionCode and major version (X.Y.Z -> X+1.0.0)") { maj, _, _ ->
  Triple(maj + 1, 0, 0)
}

tasks.register("printVersion") {
  group = "versioning"
  description = "Prints the current appVersionName and appVersionCode from gradle.properties"
  val vCode = providers.gradleProperty("appVersionCode")
  val vName = providers.gradleProperty("appVersionName")
  doLast {
    println("LazyFitBike v${vName.get()} (versionCode ${vCode.get()})")
  }
}
