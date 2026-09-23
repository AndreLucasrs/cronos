plugins {
    application
}

group = "dev.cronos"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-core:v0.2.2")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-guardrails-builtin:v0.2.2")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-skills:v0.2.2")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-provider-ollama:v0.2.2")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-rag-jdbc-pgvector:v0.2.2")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-routing:v0.2.2")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-mcp:v0.2.2")

    implementation("io.javalin:javalin:6.3.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.1")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.1")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.pgvector:pgvector:0.1.6")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("dev.cronos.CronosApp")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:all")
}
