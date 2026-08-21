package io.github.lingfeng.workbench.node.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface OpenCodeClient extends AutoCloseable {

    Health health();

    Session createSession(Path workspace, String title);

    Session getSession(Path workspace, String sessionId);

    Map<String, String> sessionStatuses(Path workspace);

    List<JsonNode> messages(Path workspace, String sessionId);

    List<JsonNode> permissions(Path workspace);

    List<JsonNode> questions(Path workspace);

    boolean supportsPromptTarget(OpenCodePromptTarget target);

    void promptAsync(Path workspace, String sessionId, OpenCodePromptTarget target, String prompt);

    void replyPermission(Path workspace, String requestId, boolean approved);

    void replyQuestion(Path workspace, String requestId, String answer);

    void rejectQuestion(Path workspace, String requestId);

    void abort(Path workspace, String sessionId);

    Subscription subscribe(
            Path workspace, Consumer<JsonNode> eventSink, Consumer<Throwable> failureSink);

    @Override
    void close();

    record Health(boolean healthy, String version) {
    }

    record Session(String id, String directory) {
    }

    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
