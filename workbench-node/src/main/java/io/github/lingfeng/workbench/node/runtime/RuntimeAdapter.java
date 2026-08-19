package io.github.lingfeng.workbench.node.runtime;

public interface RuntimeAdapter {

    RuntimeProbe probe();

    RuntimeCapabilities capabilities();

    void start(RuntimeExecutionContext context, RuntimeEventSink eventSink);

    void resume(RuntimeResumeContext context, RuntimeEventSink eventSink);

    void cancel();
}
