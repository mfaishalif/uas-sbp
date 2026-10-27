package id.ac.finresolve.presentation.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Length constraint counted in Unicode code points, matching JSON Schema {@code minLength} and
 * {@code maxLength}.
 *
 * <p>Bean Validation's {@code @Size} counts UTF-16 code units, so a supplementary character would
 * otherwise consume two characters and reject a value that is valid under the OpenAPI contract.
 */
@Documented
@Constraint(validatedBy = CodePointSizeValidator.class)
@Target({
    ElementType.FIELD,
    ElementType.METHOD,
    ElementType.PARAMETER,
    ElementType.TYPE_USE,
    ElementType.ANNOTATION_TYPE
})
@Retention(RetentionPolicy.RUNTIME)
public @interface CodePointSize {

    String message() default "Panjang nilai melebihi batas.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    int min() default 0;

    int max() default Integer.MAX_VALUE;
}
