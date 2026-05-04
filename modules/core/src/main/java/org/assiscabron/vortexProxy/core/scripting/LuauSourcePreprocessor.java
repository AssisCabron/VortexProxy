package org.assiscabron.vortexProxy.core.scripting;

import java.util.regex.Pattern;

final class LuauSourcePreprocessor {
    private static final Pattern LOCAL_TYPE_ANNOTATION = Pattern.compile("(local\\s+[A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*[A-Za-z_][A-Za-z0-9_<>?,. \\t\\[\\]|&]*(\\s*=)");
    private static final Pattern PARAM_TYPE_ANNOTATION = Pattern.compile("(:\\s*[A-Za-z_][A-Za-z0-9_<>?,. \\t\\[\\]|&]*)(?=\\s*[,)=])");
    private static final Pattern RETURN_TYPE_ANNOTATION = Pattern.compile("(\\))\\s*:\\s*[A-Za-z_][A-Za-z0-9_<>?,. \\t\\[\\]|&]*(\\s*)");
    private static final Pattern COMPOUND_ASSIGN = Pattern.compile("^([\\t ]*)([A-Za-z_][A-Za-z0-9_\\.\\[\\]\"']*)\\s*([+\\-*/%])=\\s*(.+)$", Pattern.MULTILINE);

    private LuauSourcePreprocessor() {
    }

    static String toLua51(String source) {
        var output = source
                .replaceAll("(?m)^\\s*--!\\w+\\s*$", "")
                .replaceAll("\\s*::\\s*[A-Za-z_][A-Za-z0-9_<>?,. \\t\\[\\]|&]*", "");
        output = LOCAL_TYPE_ANNOTATION.matcher(output).replaceAll("$1 =");
        output = RETURN_TYPE_ANNOTATION.matcher(output).replaceAll("$1$2");
        output = PARAM_TYPE_ANNOTATION.matcher(output).replaceAll("");
        output = COMPOUND_ASSIGN.matcher(output).replaceAll("$1$2 = $2 $3 ($4)");
        return output;
    }
}
