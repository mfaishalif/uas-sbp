package id.ac.finresolve.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.ac.finresolve.domain.similarity.SimilarityMode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class SimilarityPropertiesTest {

    @Test
    void propertyBindingKeepsTheConfiguredMode() {
        SimilarityProperties properties = bind(Map.of(
                "finresolve.similarity.mode", "phrases"));

        assertThat(properties.toSettings().mode()).isEqualTo(SimilarityMode.PHRASES);
    }

    @Test
    void propertyBindingRejectsAnUnknownModeWhenSettingsAreCreated() {
        SimilarityProperties properties = bind(Map.ofEntries(
                Map.entry("finresolve.similarity.mode", "hybrid"),
                Map.entry("finresolve.similarity.category-weight", "0.4"),
                Map.entry("finresolve.similarity.text-weight", "0.6"),
                Map.entry("finresolve.similarity.product-weight", "0.35"),
                Map.entry("finresolve.similarity.sub-product-weight", "0.15"),
                Map.entry("finresolve.similarity.issue-weight", "0.35"),
                Map.entry("finresolve.similarity.sub-issue-weight", "0.15"),
                Map.entry("finresolve.similarity.threshold", "0.5"),
                Map.entry("finresolve.similarity.default-limit", "5"),
                Map.entry("finresolve.similarity.max-limit", "20")));

        assertThatThrownBy(properties::toSettings)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown similarity mode");
    }

    private static SimilarityProperties bind(Map<String, Object> values) {
        Binder binder = new Binder(new MapConfigurationPropertySource(values));
        return binder.bind("finresolve.similarity", Bindable.of(SimilarityProperties.class))
                .orElseGet(SimilarityProperties::new);
    }
}
