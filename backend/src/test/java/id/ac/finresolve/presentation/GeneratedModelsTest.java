package id.ac.finresolve.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import id.ac.finresolve.api.CasesApi;
import id.ac.finresolve.api.CategoriesApi;
import id.ac.finresolve.api.ImportsApi;
import id.ac.finresolve.api.SearchApi;
import id.ac.finresolve.api.SystemApi;
import id.ac.finresolve.api.model.AnalystCase;
import id.ac.finresolve.api.model.CaseDetail;
import id.ac.finresolve.api.model.CfpbCase;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullableJackson3Module;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

class GeneratedModelsTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .addModule(new JsonNullableJackson3Module())
            .changeDefaultPropertyInclusion(inclusion -> inclusion
                    .withValueInclusion(JsonInclude.Include.ALWAYS)
                    .withContentInclusion(JsonInclude.Include.ALWAYS))
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    void deserializesAndRoundTripsTheCfpbCaseDetailExample() throws Exception {
        CaseDetail detail = mapper.readValue(example("cfpb"), CaseDetail.class);

        assertThat(detail).isInstanceOf(CfpbCase.class);
        CfpbCase cfpb = (CfpbCase) detail;
        assertThat(cfpb.getSource()).isEqualTo(CfpbCase.SourceEnum.CFPB);
        assertThat(cfpb.getSubProduct().isPresent()).isTrue();
        assertThat(cfpb.getSubProduct().get()).isEqualTo("Checking account");
        assertThat(cfpb.getSubIssue().isPresent()).isTrue();
        assertThat(cfpb.getSubIssue().get()).isNull();
        assertThat(cfpb.getReceivedDate().isPresent()).isTrue();
        assertThat(cfpb.getReceivedDate().get()).hasToString("2024-11-12");
        assertThat(cfpb.getAnalystNotes()).isNull();
        assertThat(cfpb.getReferenceCaseIds()).isEmpty();

        String serialized = mapper.writeValueAsString(detail);
        JsonNode roundTrip = mapper.readTree(serialized);

        assertThat(sourceKeyCount(serialized)).isEqualTo(1);
        assertThat(roundTrip.get("source").asString()).isEqualTo("CFPB");
        assertThat(roundTrip.has("subProduct")).isTrue();
        assertThat(roundTrip.get("subProduct").asString()).isEqualTo("Checking account");
        assertThat(roundTrip.has("subIssue")).isTrue();
        assertThat(roundTrip.get("subIssue").isNull()).isTrue();
        assertThat(roundTrip.has("analystNotes")).isTrue();
        assertThat(roundTrip.get("analystNotes").isNull()).isTrue();
        assertThat(roundTrip.has("provenance")).isTrue();
        assertThat(roundTrip.get("provenance").isObject()).isTrue();
        assertThat(roundTrip.get("receivedDate").asString()).isEqualTo("2024-11-12");
        assertThat(roundTrip.get("referenceCaseIds").isArray()).isTrue();
        assertThat(roundTrip.get("referenceCaseIds")).isEmpty();
    }

    @Test
    void deserializesAndRoundTripsTheAnalystCaseDetailExample() throws Exception {
        CaseDetail detail = mapper.readValue(example("analyst"), CaseDetail.class);

        assertThat(detail).isInstanceOf(AnalystCase.class);
        AnalystCase analyst = (AnalystCase) detail;
        assertThat(analyst.getSource()).isEqualTo(AnalystCase.SourceEnum.ANALYST);
        assertThat(analyst.getProvenance()).isNull();
        assertThat(analyst.getAnalystNotes()).isNotNull();
        assertThat(analyst.getAnalystNotes().getHandlingNotes())
                .isEqualTo("Reviewed transfer evidence and requested a trace.");
        assertThat(analyst.getReceivedDate().isPresent()).isTrue();
        assertThat(analyst.getReceivedDate().get()).isNull();
        assertThat(analyst.getReferenceCaseIds()).hasSize(1);

        String serialized = mapper.writeValueAsString(detail);
        JsonNode roundTrip = mapper.readTree(serialized);

        assertThat(sourceKeyCount(serialized)).isEqualTo(1);
        assertThat(roundTrip.get("source").asString()).isEqualTo("ANALYST");
        assertThat(roundTrip.has("provenance")).isTrue();
        assertThat(roundTrip.get("provenance").isNull()).isTrue();
        assertThat(roundTrip.has("receivedDate")).isTrue();
        assertThat(roundTrip.get("receivedDate").isNull()).isTrue();
        assertThat(roundTrip.has("subIssue")).isTrue();
        assertThat(roundTrip.get("subIssue").isNull()).isTrue();
        assertThat(roundTrip.get("analystNotes").get("handlingNotes").asString())
                .isEqualTo("Reviewed transfer evidence and requested a trace.");
        assertThat(roundTrip.get("referenceCaseIds").isArray()).isTrue();
        assertThat(roundTrip.get("referenceCaseIds")).hasSize(1);
    }

    @Test
    void generatedEndpointInterfacesHaveNoDefaultImplementations() {
        List<Class<?>> apis = List.of(
                SystemApi.class,
                CategoriesApi.class,
                CasesApi.class,
                SearchApi.class,
                ImportsApi.class);

        for (Class<?> api : apis) {
            for (Method method : api.getDeclaredMethods()) {
                assertThat(method.isDefault())
                        .as("%s.%s must stay abstract", api.getSimpleName(), method.getName())
                        .isFalse();
            }
        }

        assertThat(Stream.of(CasesApi.class.getDeclaredMethods()).map(Method::getName))
                .contains("listCases", "getCase", "retainCase", "replaceAnalystCase");
        assertThat(Stream.of(SearchApi.class.getDeclaredMethods()).map(Method::getName))
                .contains("searchCases");
        assertThat(Stream.of(ImportsApi.class.getDeclaredMethods()).map(Method::getName))
                .contains("importCfpb");
    }

    private String example(String name) throws Exception {
        String contract = System.getProperty("finresolve.contract");
        assertThat(contract).as("finresolve.contract system property").isNotBlank();
        JsonNode root = mapper.readTree(Files.readString(Path.of(contract)));
        JsonNode value = root.at(
                "/paths/~1cases~1{id}/get/responses/200/content/application~1json/examples/"
                        + name
                        + "/value");
        assertThat(value.isMissingNode())
                .as("contract must contain the %s CaseDetail example", name)
                .isFalse();
        return mapper.writeValueAsString(value);
    }

    private static long sourceKeyCount(String serializedJson) {
        return Pattern.compile("\"source\"\\s*:").matcher(serializedJson).results().count();
    }
}
