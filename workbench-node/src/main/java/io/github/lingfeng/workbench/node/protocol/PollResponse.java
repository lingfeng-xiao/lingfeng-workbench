package io.github.lingfeng.workbench.node.protocol;

public sealed interface PollResponse permits PollResponse.NoCommand, PollResponse.AssignmentCommand {

    record NoCommand() implements PollResponse {
    }

    record AssignmentCommand(Assignment assignment) implements PollResponse {
    }
}
