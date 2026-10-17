package com.aepsy.voucher;

import com.aepsy.voucher.common.BatchingProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class BatchingConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class);

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BatchingProperties.class)
    static class PropertiesConfiguration { }

    @ParameterizedTest
    @CsvSource({"0,500", "10001,500", "500,0", "500,10001"})
    void invalidSettingsPreventStartup(int window, int chunk) {
        runner.withPropertyValues("voucher.batching.window-ms=" + window,
                "voucher.batching.processing-chunk-size=" + chunk)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @CsvSource({"100,1", "500,5", "10000,10000"})
    void validSettingsBindFromConfiguration(int window, int chunk) {
        runner.withPropertyValues("voucher.batching.window-ms=" + window,
                "voucher.batching.processing-chunk-size=" + chunk)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(BatchingProperties.class).windowMs()).isEqualTo(window);
                    assertThat(context.getBean(BatchingProperties.class).processingChunkSize()).isEqualTo(chunk);
                });
    }
}
