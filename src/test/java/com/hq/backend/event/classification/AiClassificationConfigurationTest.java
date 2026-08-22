package com.hq.backend.event.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AiClassificationConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues(
                    "openai.model=gpt-4o-mini-2024-07-18",
                    "openai.api-key=",
                    "openai.connect-timeout-ms=3000",
                    "openai.read-timeout-ms=10000",
                    "openai.classification.enabled=false",
                    "openai.classification.rollout-percent=0",
                    "openai.classification.max-per-sync=5",
                    "openai.classification.max-concurrency=2",
                    "openai.classification.privacy-policy-version=",
                    "openai.classification.classifier-version=event-online-review-v1",
                    "openai.classification.prompt-version=event-online-ko-v1",
                    "openai.classification.schema-version=event-online-v1")
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(
                    AiClassificationConfig.class, OpenAiClientConfig.class,
                    NoOpEventClassifier.class, AiClassificationMetrics.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void safe_defaults_keep_the_no_op_classifier_and_exact_pinned_rollout_settings() {
        contextRunner.run(context -> {
            AiClassificationProperties properties = context.getBean(AiClassificationProperties.class);
            assertThat(properties.baseUrl().toString()).isEqualTo("https://api.openai.com/v1");
            assertThat(properties.model()).isEqualTo("gpt-4o-mini-2024-07-18");
            assertThat(properties.connectTimeoutMs()).isEqualTo(3000);
            assertThat(properties.readTimeoutMs()).isEqualTo(10000);
            assertThat(properties.classification()).extracting(
                    AiClassificationProperties.Classification::enabled,
                    AiClassificationProperties.Classification::rolloutPercent,
                    AiClassificationProperties.Classification::maxPerSync,
                    AiClassificationProperties.Classification::maxConcurrency,
                    AiClassificationProperties.Classification::privacyPolicyVersion,
                    AiClassificationProperties.Classification::classifierVersion,
                    AiClassificationProperties.Classification::promptVersion,
                    AiClassificationProperties.Classification::schemaVersion)
                    .containsExactly(false, 0, 5, 2, "", "event-online-review-v1", "event-online-ko-v1", "event-online-v1");
            assertThat(context.getBean(EventClassifier.class)).isInstanceOf(NoOpEventClassifier.class);
        });
    }

    @Test
    void enabled_mode_stays_no_op_without_key_or_policy_and_selects_openai_only_when_both_exist() {
        contextRunner.withPropertyValues("openai.classification.enabled=true", "openai.api-key=test-key")
                .run(context -> assertThat(context.getBean(EventClassifier.class)).isInstanceOf(NoOpEventClassifier.class));
        contextRunner.withPropertyValues("openai.classification.enabled=true", "openai.classification.privacy-policy-version=privacy-v1")
                .run(context -> assertThat(context.getBean(EventClassifier.class)).isInstanceOf(NoOpEventClassifier.class));
        contextRunner.withPropertyValues(
                        "openai.classification.enabled=true", "openai.api-key=test-key",
                        "openai.classification.privacy-policy-version=privacy-v1")
                .run(context -> assertThat(context.getBean(EventClassifier.class)).isInstanceOf(OpenAiEventClassifier.class));
    }
}
