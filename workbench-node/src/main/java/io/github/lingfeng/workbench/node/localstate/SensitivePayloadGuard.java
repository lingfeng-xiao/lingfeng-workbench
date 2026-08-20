package io.github.lingfeng.workbench.node.localstate;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

final class SensitivePayloadGuard {

    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "sessionid", "sessionref", "runtimesessionid", "resumetoken", "localpath", "absolutepath",
            "rawruntimeevent", "stdout", "stderr", "diff", "artifact", "conversation", "toolcall");

    private SensitivePayloadGuard() {
    }

    static void requireControlProjection(JsonNode payload, Path stateDirectory) {
        inspect(payload, stateDirectory.toAbsolutePath().normalize().toString());
    }

    private static void inspect(JsonNode node, String localStatePath) {
        if (node.isObject()) {
            node.properties().forEach(field -> {
                if (FORBIDDEN_FIELDS.contains(field.getKey().toLowerCase(Locale.ROOT))) {
                    throw new LocalStateException("Sensitive field cannot cross the Node protocol boundary");
                }
                inspect(field.getValue(), localStatePath);
            });
        } else if (node.isArray()) {
            node.forEach(value -> inspect(value, localStatePath));
        } else if (node.isTextual() && !localStatePath.isBlank() && node.textValue().contains(localStatePath)) {
            throw new LocalStateException("Local absolute path cannot cross the Node protocol boundary");
        }
    }
}
