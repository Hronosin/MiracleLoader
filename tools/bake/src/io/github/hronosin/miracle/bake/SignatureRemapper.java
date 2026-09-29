package io.github.hronosin.miracle.bake;

/**
 * Rewrites the class names inside a generic signature (JVMS 4.7.9.1), e.g.
 * {@code Ljava/util/List<Lchl;>;} to {@code Ljava/util/List<Lnet/minecraft/world/entity/LivingEntity;>;}.
 * A small recursive-descent parser: type variable and type parameter names must stay untouched,
 * which a plain search-and-replace would get wrong.
 */
final class SignatureRemapper {

    interface Names {
        String map(String internalName);
    }

    private final String s;
    private final Names names;
    private final StringBuilder out = new StringBuilder();
    private int i;

    private SignatureRemapper(String s, Names names) {
        this.s = s;
        this.names = names;
    }

    /** Class, method or field signature: all three are handled by the same grammar walk. */
    static String remap(String signature, Names names) {
        SignatureRemapper r = new SignatureRemapper(signature, names);
        r.any();
        return r.out.toString();
    }

    private char peek() {
        return s.charAt(i);
    }

    private void copy() {
        out.append(s.charAt(i++));
    }

    private void any() {
        if (i < s.length() && peek() == '<') {
            typeParameters();
        }
        if (i < s.length() && peek() == '(') {
            copy();
            while (peek() != ')') {
                javaType();
            }
            copy();
            if (peek() == 'V') {
                copy();
            } else {
                javaType();
            }
            while (i < s.length() && peek() == '^') {
                copy();
                referenceType();
            }
            return;
        }
        while (i < s.length()) {
            referenceType();
        }
    }

    private void typeParameters() {
        copy(); // <
        while (peek() != '>') {
            while (peek() != ':') {
                copy(); // identifier, left alone
            }
            copy(); // : class bound (may be empty)
            if (peek() != ':' && peek() != '>') {
                referenceType();
            }
            while (peek() == ':') {
                copy();
                referenceType();
            }
        }
        copy(); // >
    }

    private void javaType() {
        char c = peek();
        if ("BCDFIJSZ".indexOf(c) >= 0) {
            copy();
        } else {
            referenceType();
        }
    }

    private void referenceType() {
        switch (peek()) {
            case 'L' -> classType();
            case 'T' -> {
                while (peek() != ';') {
                    copy();
                }
                copy();
            }
            case '[' -> {
                copy();
                javaType();
            }
            default -> throw new IllegalArgumentException("bad signature at " + i + ": " + s);
        }
    }

    private void classType() {
        i++; // L
        int start = i;
        while (peek() != '<' && peek() != '.' && peek() != ';') {
            i++;
        }
        String full = s.substring(start, i);
        String mapped = names.map(full);
        out.append('L').append(mapped);
        typeArgs();
        while (peek() == '.') {
            i++;
            int st = i;
            while (peek() != '<' && peek() != '.' && peek() != ';') {
                i++;
            }
            String inner = s.substring(st, i);
            full = full + "$" + inner;
            String mappedInner = names.map(full);
            String simple = mappedInner.startsWith(mapped + "$")
                    ? mappedInner.substring(mapped.length() + 1)
                    : mappedInner.substring(mappedInner.lastIndexOf('$') + 1);
            out.append('.').append(simple);
            mapped = mappedInner;
            typeArgs();
        }
        i++; // ;
        out.append(';');
    }

    private void typeArgs() {
        if (peek() != '<') {
            return;
        }
        copy();
        while (peek() != '>') {
            char c = peek();
            if (c == '*') {
                copy();
            } else {
                if (c == '+' || c == '-') {
                    copy();
                }
                referenceType();
            }
        }
        copy();
    }
}
