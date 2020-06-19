/*
 * Copyright 2020 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import Workflow

/// A set of expectations for use with the `WorkflowRenderTester`. All of the expectations must be fulfilled
/// for a `render` test to pass.
public struct RenderExpectations<WorkflowType: Workflow> {
    var expectedState: ExpectedState<WorkflowType>?
    var expectedOutput: ExpectedOutput<WorkflowType>?
    var expectedWorkflows: [ExpectedWorkflow]
    var expectedSideEffects: [AnyHashable: ExpectedSideEffect<WorkflowType>]

    public init(
        expectedState: ExpectedState<WorkflowType>? = nil,
        expectedOutput: ExpectedOutput<WorkflowType>? = nil,
        expectedWorkflows: [ExpectedWorkflow] = [],
        expectedSideEffects: [ExpectedSideEffect<WorkflowType>] = []
    ) {
        self.expectedState = expectedState
        self.expectedOutput = expectedOutput
        self.expectedWorkflows = expectedWorkflows
        self.expectedSideEffects = expectedSideEffects.reduce(into: [AnyHashable: ExpectedSideEffect<WorkflowType>]()) { res, expectedSideEffect in
            res[expectedSideEffect.key] = expectedSideEffect
        }
    }
}

public struct ExpectedOutput<WorkflowType: Workflow> {
    let output: WorkflowType.Output
    let isEquivalent: (WorkflowType.Output, WorkflowType.Output) -> Bool

    public init<Output>(output: Output, isEquivalent: @escaping (Output, Output) -> Bool) where Output == WorkflowType.Output {
        self.output = output
        self.isEquivalent = isEquivalent
    }

    public init<Output>(output: Output) where Output == WorkflowType.Output, Output: Equatable {
        self.init(output: output, isEquivalent: { expected, actual in
            expected == actual
        })
    }
}

public struct ExpectedState<WorkflowType: Workflow> {
    let state: WorkflowType.State
    let isEquivalent: (WorkflowType.State, WorkflowType.State) -> Bool

    /// Create a new expected state from a state with an equivalence block. `isEquivalent` will be
    /// called to validate that the expected state matches the actual state after a render pass.
    public init<State>(state: State, isEquivalent: @escaping (State, State) -> Bool) where State == WorkflowType.State {
        self.state = state
        self.isEquivalent = isEquivalent
    }

    public init<State>(state: State) where WorkflowType.State == State, State: Equatable {
        self.init(state: state, isEquivalent: { expected, actual in
            expected == actual
        })
    }
}

public struct ExpectedSideEffect<WorkflowType: Workflow> {
    let key: AnyHashable
    let action: ((RenderContext<WorkflowType>) -> Void)?
}

extension ExpectedSideEffect {
    public init(key: AnyHashable) {
        self.init(key: key) { _ in }
    }

    public init<ActionType: WorkflowAction>(key: AnyHashable, action: ActionType) where ActionType.WorkflowType == WorkflowType {
        self.init(key: key) { context in
            let sink = context.makeSink(of: ActionType.self)
            sink.send(action)
        }
    }
}

public struct ExpectedWorkflow {
    let workflowType: Any.Type
    let key: String
    let rendering: Any
    let output: Any?
    let assertions: (Any) -> Void

    public init<WorkflowType: Workflow>(
        type: WorkflowType.Type,
        key: String = "",
        rendering: WorkflowType.Rendering,
        output: WorkflowType.Output? = nil,
        assertions: @escaping (WorkflowType) -> Void = { _ in }
    ) {
        self.workflowType = type
        self.key = key
        self.rendering = rendering
        self.output = output
        self.assertions = { workflow in
            guard let workflow = workflow as? WorkflowType else {
                fatalError("RenderTester is broken")
            }
            assertions(workflow)
        }
    }
}

import XCTest
// bc: This extension should probably go into a WorkflowReactiveSwiftTesting
@testable import WorkflowReactiveSwift

extension ExpectedWorkflow {
    public init<WorkerType: ReactiveSwiftWorker>(
        worker: WorkerType,
        key: String = "",
        output: WorkerType.Output? = nil,
        // bc: Maybe we don’t need this, but being able to do additional assertions might be handy?
        assertions: @escaping (WorkerType) -> Void = { _ in }
    ) {
        self.init(
            type: SignalProducerWorkerWorkflow<WorkerType>.self,
            key: key,
            rendering: (),
            output: output,
            assertions: { workerWorkflow in
                let actualWorker = workerWorkflow.worker
                XCTAssertTrue(worker.isEquivalent(to: actualWorker))
                assertions(actualWorker)
            }
        )
    }
}
