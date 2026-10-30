import java.security.MessageDigest

plugins {
  id("squaremap.common")
}

minecraft {
  version().set(libs.versions.minecraft)
  accessWideners(layout.projectDirectory.file("src/main/resources/squaremap-common.accesswidener"))
}

dependencies {
  api(projects.squaremapApi)
  api(libs.guice) {
    exclude("com.google.guava") // provided by minecraft
  }
  api(libs.guiceAssistedInject) {
    exclude("com.google.guava") // provided by minecraft
  }

  api(platform(libs.adventureBom))
  compileOnlyApi(libs.adventureApi)
  compileOnlyApi(libs.adventureTextSerializerPlain)
  compileOnly(libs.adventureTextSerializerGson)
  compileOnlyApi(libs.miniMessage)

  api(platform(libs.cloudBom))
  api(platform(libs.cloudMinecraftBom))
  api(platform(libs.cloudProcessorsBom))
  api(libs.cloudCore)
  api(libs.cloudConfirmation)
  compileOnly(libs.cloudBrigadier)
  api(libs.cloudMinecraftExtras)

  api(platform(libs.configurateBom))
  api(libs.configurateYaml)

  api(libs.undertow)
  api(libs.webpImageIo)

  api(libs.htmlSanitizer) {
    isTransitive = false // depends on guava, provided by mc at runtime
  }
  api(libs.htmlSanitizerJ8) {
    isTransitive = false // depends on guava, provided by mc at runtime
  }
  api(libs.htmlSanitizerJ10) {
    isTransitive = false // depends on guava, provided by mc at runtime
  }

  compileOnly("curse.maven:moonrise-1096335:5815098")
  testImplementation(platform("org.junit:junit-bom:5.11.4"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testRuntimeOnly("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
  testRuntimeOnly(platform(libs.adventureBom))
  testRuntimeOnly(libs.adventureApi)
  testRuntimeOnly(libs.miniMessage)
  testRuntimeOnly(libs.adventureTextSerializerPlain)
}

tasks.test {
  useJUnitPlatform()
}

tasks.processResources {
  exclude("web/node_modules/**", "web/package*.json")
  doLast {
    val source = layout.projectDirectory.dir("src/main/resources/web").asFile
    val digest = MessageDigest.getInstance("SHA-256")
    source.walkTopDown().filter { it.isFile && it.extension in setOf("js", "css", "html") && !it.relativeTo(source).invariantSeparatorsPath.contains("node_modules/") }
      .sortedBy { it.relativeTo(source).invariantSeparatorsPath }.forEach {
        digest.update(it.relativeTo(source).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
        digest.update(it.readBytes())
      }
    val version = digest.digest().take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val web = destinationDir.resolve("web")
    web.walkTopDown().filter { it.isFile && it.extension == "js" && !it.relativeTo(web).invariantSeparatorsPath.contains("node_modules/") }.forEach { file ->
      val content = file.readText(Charsets.UTF_8).replace(Regex("""(['"])(\.\.?/[^'"]+\.js)(?:\?[^'"]*)?\1""")) {
        "${it.groupValues[1]}${it.groupValues[2]}?v=$version${it.groupValues[1]}"
      }
      file.writeText(content, Charsets.UTF_8)
    }
    val index = web.resolve("index.html")
    index.writeText(index.readText(Charsets.UTF_8).replace(Regex("""((?:src|href)=["'])([^"']+\.(?:js|css))(?:\?[^"']*)?(["'])""")) {
      "${it.groupValues[1]}${it.groupValues[2]}?v=$version${it.groupValues[3]}"
    }, Charsets.UTF_8)
  }
}

tasks.jar {
  exclude("web/node_modules/**", "web/package*.json")
}
