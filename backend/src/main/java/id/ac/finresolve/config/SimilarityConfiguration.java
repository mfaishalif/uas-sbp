package id.ac.finresolve.config;

import id.ac.finresolve.domain.similarity.SimilaritySettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds external similarity properties and exposes the validated domain model. */
@Configuration(proxyBeanMethods = false)
public class SimilarityConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "finresolve.similarity")
    public SimilarityProperties similarityProperties() {
        return new SimilarityProperties();
    }

    @Bean
    public SimilaritySettings similaritySettings(SimilarityProperties properties) {
        return properties.toSettings();
    }
}
