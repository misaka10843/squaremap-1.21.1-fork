import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.kyori.indra.git.IndraGitExtension
import org.eclipse.jgit.lib.Repository
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType
import org.gradle.language.jvm.tasks.ProcessResources

val Project.releaseNotes: Provider<String>
  get() = providers.environmentVariable("RELEASE_NOTES")

fun Project.lastCommitHash(): String {
  val environmentCommit = sequenceOf(
    providers.environmentVariable("GITHUB_SHA").orNull,
    providers.environmentVariable("CI_COMMIT_SHA").orNull,
    providers.environmentVariable("BUILD_VCS_NUMBER").orNull,
  )
    .filterNotNull()
    .map(String::trim)
    .firstOrNull(String::isNotEmpty)

  if (environmentCommit != null) {
    return environmentCommit.take(7)
  }

  val gitCommit = extensions
    .getByType<IndraGitExtension>()
    .commit()
    ?.name
    ?.trim()
    ?.takeIf(String::isNotEmpty)

  return gitCommit?.take(7) ?: "archive"
}

fun Project.decorateVersion() {
  val versionString = version as String
  version = if (versionString.endsWith("-SNAPSHOT")) {
    "$versionString+${lastCommitHash()}"
  } else {
    versionString
  }
}

fun ShadowJar.reloc(pkg: String) {
  relocate(pkg, "squaremap.libraries.$pkg")
}

fun Project.currentBranch(): String {
  System.getenv("GITHUB_HEAD_REF")
    ?.takeIf(String::isNotEmpty)
    ?.let { return it }

  System.getenv("GITHUB_REF")
    ?.takeIf(String::isNotEmpty)
    ?.let { return it.replaceFirst("refs/heads/", "") }

  val indraGit = extensions
    .getByType<IndraGitExtension>()
    .takeIf { it.isPresent }

  val ref = indraGit
    ?.git()
    ?.repository
    ?.exactRef("HEAD")
    ?.target
    ?: return "source-archive"

  return Repository.shortenRefName(ref.name)
}

fun Project.productionJarName(
  mcVer: Provider<String>,
): Provider<String> = extensions
  .getByType<BasePluginExtension>()
  .archivesName
  .zip(mcVer) { archivesName, minecraftVersion ->
    "$archivesName-mc$minecraftVersion-$version.jar"
  }

fun Project.productionJarLocation(
  mcVer: Provider<String>,
): Provider<RegularFile> =
  productionJarName(mcVer).flatMap { fileName ->
    layout.buildDirectory.file("libs/$fileName")
  }

fun ProcessResources.expandIn(
  fileName: String,
  props: Map<String, Any?>,
) {
  inputs.properties(
    props.mapKeys { "${fileName}_${it.key}" },
  )

  filesMatching(fileName) {
    expand(props)
  }
}
