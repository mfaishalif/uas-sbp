package id.ac.finresolve.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import id.ac.finresolve.http.BusinessException.Problem;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.openapitools.jackson.nullable.JsonNullableJackson3Module;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract-derived request validation that runs before Jackson binds a request DTO.
 *
 * <p>The source OpenAPI document is copied to the classpath by the Maven build. Presence,
 * explicit-null, unknown-property, raw string length, enum, date, UUID, and collection rules are
 * read from that document rather than duplicated in a hand-written schema. Generated DTO defaults
 * therefore cannot hide an explicit {@code null} for a nonnullable property.
 */
@ControllerAdvice
public final class RequestRules implements RequestBodyAdvice {

    static final String CONTRACT_RESOURCE = "/openapi.json";

    private static final String VALIDATION_MESSAGE = "Input tidak valid.";

    private final JsonNode schemas;
    private final JsonMapper json;

    public RequestRules() {
        this(loadContract());
    }

    RequestRules(JsonNode contract) {
        JsonNode componentSchemas = contract.path("components").path("schemas");
        if (!componentSchemas.isObject() || componentSchemas.isEmpty()) {
            throw new IllegalStateException("The OpenAPI contract has no component schemas.");
        }
        this.schemas = componentSchemas;
        this.json = JsonMapper.builder()
                .addModule(new JsonNullableJackson3Module())
                .changeDefaultPropertyInclusion(inclusion -> inclusion
                        .withValueInclusion(JsonInclude.Include.ALWAYS)
                        .withContentInclusion(JsonInclude.Include.ALWAYS))
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /** Returns whether the contract contains a schema with this exact name. */
    public boolean hasSchema(String schemaName) {
        return schemaName != null && schemas.has(schemaName);
    }

    /** Validates one raw JSON body against the named contract request schema. */
    public void validateJson(String schemaName, String rawJson) {
        validateJson(schemaName, rawJson == null ? null : rawJson.getBytes(StandardCharsets.UTF_8));
    }

    /** Validates one raw JSON body against the named contract request schema. */
    public void validateJson(String schemaName, byte[] rawJson) {
        JsonNode schema = schema(schemaName);
        if (rawJson == null || rawJson.length == 0) {
            throw malformed("Badan permintaan wajib diisi.");
        }

        JsonNode body;
        try {
            body = json.readTree(rawJson);
        } catch (RuntimeException failure) {
            throw malformed("Badan permintaan tidak dapat dibaca.");
        }
        if (body == null || !body.isObject()) {
            throw malformed("Badan permintaan harus berupa objek JSON.");
        }

        List<Problem> problems = new ArrayList<>();
        validateObject("", body, schema, problems);
        if (!problems.isEmpty()) {
            throw new BusinessException(400, "VALIDATION_ERROR", VALIDATION_MESSAGE, problems);
        }
    }

    @Override
    public boolean supports(
            MethodParameter methodParameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return methodParameter.hasParameterAnnotation(RequestBody.class)
                && targetType instanceof Class<?> type
                && hasSchema(type.getSimpleName());
    }

    @Override
    public HttpInputMessage beforeBodyRead(
            HttpInputMessage inputMessage,
            MethodParameter parameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        byte[] body = inputMessage.getBody().readAllBytes();
        String schemaName = ((Class<?>) targetType).getSimpleName();
        validateJson(schemaName, body);
        return new BufferedInputMessage(inputMessage.getHeaders(), body);
    }

    @Override
    public Object afterBodyRead(
            Object body,
            HttpInputMessage inputMessage,
            MethodParameter parameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return body;
    }

    @Override
    public Object handleEmptyBody(
            Object body,
            HttpInputMessage inputMessage,
            MethodParameter parameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return body;
    }

    private JsonNode schema(String schemaName) {
        if (schemaName == null || !schemas.has(schemaName)) {
            throw new IllegalStateException("Unknown contract schema: " + schemaName);
        }
        return resolve(schemas.get(schemaName));
    }

    private JsonNode resolve(JsonNode schema) {
        JsonNode current = schema;
        Set<String> seen = new HashSet<>();
        while (current != null && current.has("$ref")) {
            String reference = current.get("$ref").asString();
            if (!reference.startsWith("#/components/schemas/")
                    || !seen.add(reference)) {
                throw new IllegalStateException("Only local component schema references are supported.");
            }
            String name = reference.substring("#/components/schemas/".length());
            current = schemas.get(name);
        }
        if (current == null) {
            throw new IllegalStateException("Unresolvable contract schema reference.");
        }
        return current;
    }

    private void validateObject(
            String path, JsonNode value, JsonNode schema, List<Problem> problems) {
        JsonNode properties = schema.path("properties");
        boolean rejectUnknown = schema.path("additionalProperties").isBoolean()
                && !schema.path("additionalProperties").asBoolean();

        Iterator<String> names = value.propertyNames().iterator();
        while (names.hasNext()) {
            String name = names.next();
            String fieldPath = path.isEmpty() ? name : path + "." + name;
            JsonNode propertySchema = properties.get(name);
            if (propertySchema == null) {
                if (rejectUnknown) {
                    problems.add(new Problem(
                            fieldPath,
                            "UNKNOWN_FIELD",
                            "Properti tidak dikenal dan harus dihilangkan."));
                }
                continue;
            }
            validateValue(fieldPath, value.get(name), resolve(propertySchema), problems);
        }

        JsonNode required = schema.path("required");
        if (required.isArray()) {
            for (JsonNode requiredName : required) {
                String name = requiredName.asString();
                if (!value.has(name)) {
                    problems.add(new Problem(
                            path.isEmpty() ? name : path + "." + name,
                            "REQUIRED",
                            "Field wajib diisi."));
                }
            }
        }
    }

    private void validateValue(
            String path, JsonNode value, JsonNode schema, List<Problem> problems) {
        if (value.isNull()) {
            if (allowsNull(schema)) {
                return;
            }
            problems.add(new Problem(
                    path,
                    "INVALID_VALUE",
                    "Field tidak boleh null."));
            return;
        }

        if (!matchesType(value, schema)) {
            problems.add(new Problem(
                    path, "INVALID_FORMAT", "Tipe nilai tidak sesuai dengan kontrak."));
            return;
        }

        if (value.isTextual()) {
            validateString(path, value.asString(), schema, problems);
        } else if (value.isIntegralNumber() || value.isFloatingPointNumber()) {
            validateNumber(path, value, schema, problems);
        } else if (value.isArray()) {
            validateArray(path, value, schema, problems);
        } else if (value.isObject()) {
            validateObject(path, value, schema, problems);
        }
    }

    private void validateString(
            String path, String text, JsonNode schema, List<Problem> problems) {
        int length = text.codePointCount(0, text.length());
        JsonNode minLength = schema.get("minLength");
        if (minLength != null && minLength.isIntegralNumber() && length < minLength.asInt()) {
            problems.add(new Problem(
                    path,
                    "INVALID_VALUE",
                    "Panjang minimal " + minLength.asInt() + " karakter."));
        }
        JsonNode maxLength = schema.get("maxLength");
        if (maxLength != null && maxLength.isIntegralNumber() && length > maxLength.asInt()) {
            problems.add(new Problem(
                    path,
                    "TOO_LONG",
                    "Panjang maksimal " + maxLength.asInt() + " karakter."));
        }

        JsonNode values = schema.get("enum");
        if (values != null && values.isArray()) {
            boolean found = false;
            for (JsonNode allowed : values) {
                if (allowed.isTextual() && allowed.asString().equals(text)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                problems.add(new Problem(path, "INVALID_VALUE", "Nilai tidak dikenal."));
            }
        }

        JsonNode pattern = schema.get("pattern");
        if (pattern != null && pattern.isTextual()
                && !Pattern.compile(pattern.asString()).matcher(text).find()) {
            problems.add(new Problem(path, "INVALID_FORMAT", "Format nilai tidak sesuai."));
        }

        String format = schema.path("format").asString("");
        switch (format) {
            case "uuid" -> {
                if (!CanonicalUuid.isCanonical(text)) {
                    problems.add(new Problem(
                            path,
                            "INVALID_FORMAT",
                            "UUID harus memakai format lengkap 8-4-4-4-12."));
                }
            }
            case "date" -> {
                try {
                    LocalDate.parse(text);
                } catch (DateTimeParseException failure) {
                    problems.add(new Problem(
                            path, "INVALID_FORMAT", "Tanggal harus berformat YYYY-MM-DD."));
                }
            }
            case "uri" -> {
                try {
                    URI.create(text);
                } catch (IllegalArgumentException failure) {
                    problems.add(new Problem(path, "INVALID_FORMAT", "URI tidak valid."));
                }
            }
            default -> {
                // No additional format rule is defined by the request schemas.
            }
        }
    }

    private void validateNumber(String path, JsonNode value, JsonNode schema, List<Problem> problems) {
        if (schema.has("minimum") && value.decimalValue().compareTo(schema.get("minimum").decimalValue()) < 0) {
            problems.add(new Problem(
                    path, "OUT_OF_RANGE", "Nilai di bawah batas minimum."));
        }
        if (schema.has("maximum") && value.decimalValue().compareTo(schema.get("maximum").decimalValue()) > 0) {
            problems.add(new Problem(
                    path, "OUT_OF_RANGE", "Nilai di atas batas maksimum."));
        }
    }

    private void validateArray(String path, JsonNode value, JsonNode schema, List<Problem> problems) {
        JsonNode maxItems = schema.get("maxItems");
        if (maxItems != null && maxItems.isIntegralNumber() && value.size() > maxItems.asInt()) {
            problems.add(new Problem(
                    path, "OUT_OF_RANGE", "Jumlah item maksimal " + maxItems.asInt() + "."));
        }

        if (schema.path("uniqueItems").asBoolean(false)) {
            Set<String> seen = new HashSet<>();
            for (int index = 0; index < value.size(); index++) {
                if (!seen.add(value.get(index).toString())) {
                    problems.add(new Problem(
                            path + "[" + index + "]", "DUPLICATE", "Item tidak boleh duplikat."));
                }
            }
        }

        JsonNode itemSchema = schema.get("items");
        if (itemSchema != null) {
            JsonNode resolved = resolve(itemSchema);
            for (int index = 0; index < value.size(); index++) {
                validateValue(path + "[" + index + "]", value.get(index), resolved, problems);
            }
        }
    }

    private static boolean allowsNull(JsonNode schema) {
        JsonNode type = schema.get("type");
        if (type == null) {
            return false;
        }
        if (type.isTextual()) {
            return "null".equals(type.asString());
        }
        if (type.isArray()) {
            for (JsonNode member : type) {
                if (member.isTextual() && "null".equals(member.asString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesType(JsonNode value, JsonNode schema) {
        JsonNode type = schema.get("type");
        if (type == null) {
            return true;
        }
        if (type.isTextual()) {
            return matchesType(value, type.asString());
        }
        if (type.isArray()) {
            for (JsonNode member : type) {
                if (member.isTextual() && matchesType(value, member.asString())) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static boolean matchesType(JsonNode value, String type) {
        return switch (type) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isIntegralNumber() || value.isFloatingPointNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    private static BusinessException malformed(String message) {
        return new BusinessException(400, "MALFORMED_REQUEST", message, List.of());
    }

    private static JsonNode loadContract() {
        try (InputStream input = RequestRules.class.getResourceAsStream(CONTRACT_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing classpath contract resource " + CONTRACT_RESOURCE + ".");
            }
            return JsonMapper.builder().build().readTree(input);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read the classpath OpenAPI contract.", failure);
        }
    }

    private record BufferedInputMessage(HttpHeaders headers, byte[] body) implements HttpInputMessage {

        @Override
        public InputStream getBody() {
            return new java.io.ByteArrayInputStream(body);
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }
}
