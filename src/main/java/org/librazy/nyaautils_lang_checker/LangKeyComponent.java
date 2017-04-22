package org.librazy.nyaautils_lang_checker;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;

@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, PARAMETER, LOCAL_VARIABLE, METHOD, TYPE, ANNOTATION_TYPE, TYPE_USE})
public @interface LangKeyComponent {
    String[] value() default {};
    LangKeyComponentType type() default LangKeyComponentType.PREFIX;
    boolean isInternal() default false;
}