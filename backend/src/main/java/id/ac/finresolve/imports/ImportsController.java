package id.ac.finresolve.imports;

import id.ac.finresolve.api.ImportsApi;
import id.ac.finresolve.api.model.ImportMetadata;
import id.ac.finresolve.api.model.ImportReport;
import id.ac.finresolve.http.ApiMapper;
import id.ac.finresolve.http.BusinessException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.Errors;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The contract-generated CFPB import endpoint. */
@RestController
@RequestMapping("/api")
public class ImportsController implements ImportsApi {

    private final ImportService imports;
    private final ApiMapper mapper;
    private final Validator validator;

    public ImportsController(ImportService imports, ApiMapper mapper, Validator validator) {
        this.imports = imports;
        this.mapper = mapper;
        this.validator = validator;
    }

    /**
     * Metadata is normalized before its contract constraints run, so values such as a padded
     * replay filename or archive URL are judged in the same trimmed form used by the replay key.
     */
    @InitBinder("metadata")
    void normalizeMetadata(WebDataBinder binder) {
        binder.setValidator(new MetadataValidator(validator));
    }

    @Override
    public ResponseEntity<ImportReport> importCfpb(MultipartFile file, ImportMetadata metadata) {
        ImportMetadataValue value = new ImportMetadataValue(
                metadata.getArchiveUrl(),
                metadata.getSourceFileName(),
                metadata.getPeriodStart(),
                metadata.getPeriodEnd());
        try (InputStream input = file.getInputStream()) {
            ImportService.Result result = imports.importCsv(input, value);
            return ResponseEntity.status(result.replayed() ? 200 : 201)
                    .body(mapper.importReport(result.batch(), result.replayed()));
        } catch (IOException failure) {
            throw new BusinessException(
                    400,
                    "INVALID_CSV",
                    "Berkas CSV tidak dapat dibaca.",
                    List.of());
        }
    }

    private static final class MetadataValidator implements org.springframework.validation.Validator {

        private final Validator validator;

        private MetadataValidator(Validator validator) {
            this.validator = validator;
        }

        @Override
        public boolean supports(Class<?> type) {
            return ImportMetadata.class.isAssignableFrom(type);
        }

        @Override
        public void validate(Object target, Errors errors) {
            ImportMetadata metadata = (ImportMetadata) target;
            metadata.setArchiveUrl(trim(metadata.getArchiveUrl()));
            metadata.setSourceFileName(trim(metadata.getSourceFileName()));
            for (ConstraintViolation<ImportMetadata> violation : validator.validate(metadata)) {
                String field = violation.getPropertyPath().toString();
                String code = violation.getConstraintDescriptor()
                        .getAnnotation()
                        .annotationType()
                        .getSimpleName();
                errors.rejectValue(field, code, violation.getMessage());
            }
        }

        private static String trim(String value) {
            return value == null ? null : value.strip();
        }
    }
}
