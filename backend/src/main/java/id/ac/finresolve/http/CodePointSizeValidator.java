package id.ac.finresolve.http;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Validates {@link CodePointSize} using Unicode code points, not UTF-16 code units. */
public final class CodePointSizeValidator
        implements ConstraintValidator<CodePointSize, CharSequence> {

    private int minimum;
    private int maximum;

    @Override
    public void initialize(CodePointSize constraint) {
        this.minimum = constraint.min();
        this.maximum = constraint.max();
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        int length = Character.codePointCount(value, 0, value.length());
        return length >= minimum && length <= maximum;
    }
}
