package id.ac.finresolve.http;

import id.ac.finresolve.api.model.ImportMetadata;
import java.io.IOException;
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
final class StrictImportMetadataConverter extends AbstractHttpMessageConverter<ImportMetadata> {

    private static final List<String> TRIMMED_FIELDS = List.of("archiveUrl", "sourceFileName");

    private final RequestRules rules;
    private final JsonMapper json;

    StrictImportMetadataConverter(RequestRules rules) {
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
}
