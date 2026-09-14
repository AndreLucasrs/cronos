package dev.cronos.assistant;

import dev.aegis4j.core.engine.Aegis4jEngine;
import dev.aegis4j.core.engine.ChatRequest;
import dev.aegis4j.core.guard.GuardChain;
import dev.aegis4j.core.provider.ProviderRegistry;
import dev.aegis4j.core.skill.SkillRegistry;
import dev.aegis4j.guardrails.builtin.MaxLengthGuard;
import dev.aegis4j.guardrails.builtin.RegexPiiGuard;
import dev.aegis4j.skills.markdown.MarkdownSkillLoader;
import dev.cronos.config.Env;

import java.nio.file.Path;

/**
 * Wires the embedded {@link Aegis4jEngine}: this is the same discover
 * -&gt; guard chain -&gt; skill registry -&gt; build pattern used by
 * {@code Aegis4jServerApp} in the aegis4j sidecar, but embedded directly in
 * Cronos's own process instead of running as a separate HTTP service.
 */
public final class CronosAssistant {

    private final Aegis4jEngine engine;
    private final String providerId;
    private final String model;

    public CronosAssistant() {
        ProviderRegistry providerRegistry = new ProviderRegistry();
        providerRegistry.discover(Thread.currentThread().getContextClassLoader());

        SkillRegistry skillRegistry = SkillRegistry.inMemory();
        loadSkills(skillRegistry);

        this.engine = Aegis4jEngine.builder()
                .providerRegistry(providerRegistry)
                .guardChain(GuardChain.of(
                        MaxLengthGuard.forInput(Env.getInt("CRONOS_MAX_INPUT_CHARS", 4000)),
                        RegexPiiGuard.allPatterns()))
                .skillRegistry(skillRegistry)
                .build();
        this.providerId = Env.get("CRONOS_PROVIDER_ID", "ollama");
        this.model = Env.get("CRONOS_OLLAMA_MODEL", "llama3.1:8b");
    }

    private void loadSkills(SkillRegistry skillRegistry) {
        Path skillsDir = Path.of(Env.get("CRONOS_SKILLS_DIR", "skills"));
        new MarkdownSkillLoader().loadDirectory(skillsDir).forEach(skillRegistry::register);
    }

    public String chat(String userInput) {
        ChatRequest request = ChatRequest.builder()
                .providerId(providerId)
                .model(model)
                .userInput(userInput)
                .build();
        return engine.chat(request).content();
    }
}
