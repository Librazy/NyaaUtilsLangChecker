package org.librazy.nyaautils_lang_checker;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;
import static java.lang.annotation.ElementType.*;

@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, PARAMETER, LOCAL_VARIABLE})
public @interface LangKey {
    String[] value() default {};
}
