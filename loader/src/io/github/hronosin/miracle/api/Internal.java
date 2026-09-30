package io.github.hronosin.miracle.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Public for technical reasons only (patched game classes call it, or the loader reaches it from
 * another package), and not part of the API: it may change in any release, 1.x included. Mods
 * mustn't call it.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface Internal {
}
