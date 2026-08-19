package io.github.lingfeng.workbench.node.runtime;

@FunctionalInterface
public interface RuntimeEventSink {

    void emit(RuntimeEvent event);
}
