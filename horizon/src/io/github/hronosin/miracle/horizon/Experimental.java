package io.github.hronosin.miracle.horizon;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Not frozen: may change or go away in any release, unlike MiracleToolChain's and MiracleLoader's
 * API. All of Event Horizon is this, for now.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.PACKAGE, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface Experimental {
}
