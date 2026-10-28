package id.ac.finresolve.presentation.validation;

import id.ac.finresolve.api.model.ImportMetadata;
import id.ac.finresolve.domain.error.BusinessException;
import id.ac.finresolve.domain.error.BusinessException.Problem;
import id.ac.finresolve.domain.text.UnicodeText;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validates the raw JSON of the multipart {@code metadata} part before Jackson binds it.
 *
 * <p>Part binding normally coerces values (for example {@code 123} or {@code true} into the string
 * {@code "123"/"true"}). Such coercion would let out-of-contract input reach provenance and the
 * replay key, so the contract rules run on the parsed tree first. The two text fields that the
 * metadata validator normalizes are trimmed here as well, keeping URL and filename pattern checks
 * consistent with the stored values.
 */
public final class StrictImportMetadataConverter
        extends AbstractHttpMessageConverter<ImportMetadata> {

    private static final List<String> TRIMMED_FIELDS = List.of("archiveUrl", "sourceFileName");

    private final RequestRules rules;
    private final JsonMapper json;

    public StrictImportMetadataConverter(RequestRules rules) {
        super(MediaType.APPLICATION_JSON);
        this.rules = rules;
        this.json = JsonMapper.builder().build();
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return ImportMetadata.class.isAssignableFrom(clazz);
    }

    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        return false;
    }

    @Override
    protected ImportMetadata readInternal(
            Class<? extends ImportMetadata> clazz, HttpInputMessage inputMessage)
            throws IOException {
        JsonNode body;
        try {
            body = json.readTree(inputMessage.getBody().readAllBytes());
        } catch (RuntimeException failure) {
            throw new HttpMessageNotReadableException(
                    "Metadata is not valid JSON.", failure, inputMessage);
        }
        normalizeText(body);
        validateUnicode(body);
        rules.validateJson("ImportMetadata", body);
        try {
            return json.treeToValue(body, ImportMetadata.class);
        } catch (RuntimeException failure) {
            throw new HttpMessageNotReadableException(
                    "Metadata cannot be bound.", failure, inputMessage);
        }
    }

    @Override
    protected void writeInternal(ImportMetadata metadata, HttpOutputMessage outputMessage) {
        throw new UnsupportedOperationException("Metadata responses use the default converter.");
    }

    private static void normalizeText(JsonNode body) {
        if (!(body instanceof ObjectNode object)) {
            return;
        }
        for (String field : TRIMMED_FIELDS) {
            JsonNode value = object.get(field);
            if (value != null && value.isTextual()) {
                object.put(field, value.asString().strip());
            }
        }
    }

    /**
     * Metadata problems use the public {@code metadata.*} field path, matching the domain
     * validator rather than the unprefixed component-schema names used during raw binding.
     */
    private static void validateUnicode(JsonNode body) {
        if (!(body instanceof ObjectNode object)) {
            return;
        }
        List<Problem> problems = new ArrayList<>();
        Iterator<String> names = object.propertyNames().iterator();
        while (names.hasNext()) {
            String field = names.next();
            JsonNode value = object.get(field);
            if (value.isTextual()) {
                UnicodeText.addProblemIfInvalid(
                        value.asString(), "metadata." + field, problems);
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessException(
                    "VALIDATION_ERROR", "Metadata impor tidak valid.", problems);
        }
    }
}
