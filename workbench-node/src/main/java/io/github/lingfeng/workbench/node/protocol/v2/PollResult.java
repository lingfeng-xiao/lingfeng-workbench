package io.github.lingfeng.workbench.node.protocol.v2;

public sealed interface PollResult permits PollResult.NoCommand, PollResult.Command {

    record NoCommand() implements PollResult {
    }

    record Command(NodeCommand command) implements PollResult {
    }
}
