plugins {
  id("squaremap.platform.loom")
}

repositories {
  maven("https://maven.neoforged.net/releases/")
  maven("https://maven.createmod.net/")
  maven("https://maven.ithundxr.dev/snapshots")
}

/*
 * cloud-neoforge is normally distributed as a nested NeoForge mod. Connector
 * may load nested cloud jars as isolated Java modules, which prevents them
 * from reading Minecraft or the cloud classes already shaded into squaremap.
 *
 * We therefore unpack cloud-neoforge itself and shade every cloud library used
 * by squaremap into the main squaremap module. No cloud library is left as a
 * Jar-in-Jar module.
 */
val embeddedCloudNeoForge by configurations.creating {
  isTransitive = false
}

dependencies {
  minecraft(libs.minecraft)
  mappings(loom.officialMojangMappings())
  neoForge(libs.neoforge)

  // Optional Create 6.x compile-time API. Nothing from Create is bundled.
  compileOnly("com.simibubi.create:create-1.21.1:6.0.9-216:slim") {
    isTransitive = false
  }
  compileOnly("net.createmod.ponder:ponder-neoforge:1.0.81+mc1.21.1")
  compileOnly("dev.engine-room.flywheel:flywheel-neoforge-api-1.21.1:1.0.6")
  compileOnly("com.tterrag.registrate:Registrate:MC1.21-1.3.0+67")

  shade(projects.squaremapCommon) {
    // These are supplied explicitly below so that all cloud classes are placed
    // in the main squaremap jar instead of becoming isolated nested modules.
    exclude("org.incendo", "cloud-core")
    exclude("org.incendo", "cloud-minecraft-extras")
    exclude("org.incendo", "cloud-processors-confirmation")
    exclude("io.leangen.geantyref")
  }

  implementation(platform(libs.adventureBom))
  include(platform(libs.adventureBom))
  implementation(libs.adventureApi)
  include(libs.adventureApi)
  include(libs.examinationApi)
  include(libs.examinationString)
  include(libs.option)
  include(libs.adventureKey)
  include(libs.miniMessage)
  implementation(libs.adventureTextSerializerGson)
  include(libs.adventureTextSerializerGson)
  include(libs.adventureTextSerializerJson)
  include(libs.adventureTextSerializerPlain)

  // Compile against the NeoForge adapter, then unpack only its own classes.
  // Its bundled Jar-in-Jar dependencies are excluded in shadowJar below.
  compileOnly(libs.cloudNeoForge)
  embeddedCloudNeoForge(libs.cloudNeoForge)

  // Keep the complete Cloud command stack inside squaremap's own module.
  // shade extends implementation, so these are also available at compile time.
  // The BOMs must also be attached to shade because the version catalog leaves
  // several Cloud module versions to dependency constraints.
  shade(platform(libs.cloudBom))
  shade(platform(libs.cloudMinecraftBom))
  shade(platform(libs.cloudProcessorsBom))
  shade(libs.cloudCore)
  shade(libs.cloudBrigadier) {
    isTransitive = false
  }
  shade(libs.cloudMinecraftExtras) {
    isTransitive = false
  }
  shade(libs.cloudConfirmation) {
    isTransitive = false
  }
  shade(libs.cloudProcessorsCommon) {
    isTransitive = false
  }
  shade("org.incendo:cloud-minecraft-modded-common:${libs.versions.cloudModded.get()}") {
    isTransitive = false
  }
}

tasks.shadowJar {
  mergeServiceFiles()
  // Cloud uses generated builders and several indirect code paths. Keep the
  // complete shaded Cloud stack instead of letting Shadow minimize it away.
  minimize {
    exclude(dependency("org.sejda.imageio:.*:.*"))
    exclude(dependency("org.incendo:.*:.*"))
    exclude(dependency("io.leangen.geantyref:.*:.*"))
  }

  from({
    embeddedCloudNeoForge.files.map { zipTree(it) }
  }) {
    // Keep squaremap as the only NeoForge mod in the produced jar.
    exclude("META-INF/neoforge.mods.toml")
    // squaremap supplies its own access transformer. The adapter's AT only
    // opens command-environment enum constants and would otherwise overwrite
    // ours because both files have the same fixed NeoForge path.
    exclude("META-INF/accesstransformer.cfg")
    exclude("META-INF/MANIFEST.MF")
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    exclude("META-INF/jarjar/**")
    exclude("META-INF/jars/**")
    exclude("module-info.class")
    exclude("META-INF/versions/**/module-info.class")
    exclude("pack.mcmeta")
    exclude("cloud.mixins.json")

    // Replaced by the patched source file included in this project.
    exclude("org/incendo/cloud/neoforge/CloudNeoForgeEntrypoint.class")

    // squaremap only creates a server command manager. Excluding these also
    // prevents accidental client-class linkage on a dedicated server.
    exclude("org/incendo/cloud/neoforge/NeoForgeClientCommandManager.class")
    exclude("org/incendo/cloud/neoforge/NeoForgeCommandRegistrationHandler\$Client.class")
  }

  // Only the NeoForge adapter is relocated. The rest of Cloud stays in its
  // original package, but is physically contained in the squaremap module.
  relocate(
    "org.incendo.cloud.neoforge",
    "squaremap.libraries.org.incendo.cloud.neoforge"
  )
}

tasks.remapJar {
  archiveFileName = productionJarName(libs.versions.minecraft)
}

squaremapPlatform.loom.modInfoFilePath = "META-INF/neoforge.mods.toml"

publishMods.modrinth {
  minecraftVersions.add(libs.versions.minecraft)
  modLoaders.add("neoforge")
}
