package org.librazy.nclangchecker;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;

@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, PARAMETER, LOCAL_VARIABLE, METHOD, TYPE, ANNOTATION_TYPE, TYPE_USE})
public @interface LangKey {
    String[] value() default {};
    int varArgsPosition() default 0;
    LangKeyType type() default LangKeyType.KEY;
    boolean isInternal() default false;
    boolean skipCheck() default false;
}
