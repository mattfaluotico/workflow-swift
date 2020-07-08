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

import ReactiveSwift
import Workflow

/// Convenience to use `Signal` as a `Workflow`
///
/// `Output` of this `Workflow` can be mapped to a `WorkflowAction`.
///
/// - Important:
/// In a `render()` call, if running multiple `Signal` or if a
/// `Signal` can change in-between render passes, use a `Worker`
/// instead or use an explicit `key` while `running`.
///
/// ```
/// func render(state: State, context: RenderContext<Self>) -> MyScreen {
///     signal
///         .mapOutput { MyAction($0) }
///         .running(in: context, key: "UniqueKeyForSignal")
///
///     return MyScreen()
/// }
/// ```
extension Signal: AnyWorkflowConvertible where Error == Never {
    public func asAnyWorkflow() -> AnyWorkflow<Void, Value> {
        return SignalProducerWorkflow(signalProducer: SignalProducer(self)).asAnyWorkflow()
    }
}

/// A `Worker` that wraps a `Signal`
@available(*, deprecated, message: "Use `Signal` as `Workflow` instead")
public struct SignalWorker<Key: Equatable, Value>: Worker {
    let key: Key
    let signal: Signal<Value, Never>

    public init(key: Key, signal: Signal<Value, Never>) {
        self.key = key
        self.signal = signal
    }

    public func run() -> SignalProducer<Value, Never> {
        return SignalProducer(signal)
    }

    public func isEquivalent(to otherWorker: SignalWorker) -> Bool {
        return key == otherWorker.key
    }

    public func asAnyWorkflow() -> AnyWorkflow<Void, Value> {
        AnyWorkflow(WorkerWorkflow(worker: self), keyPrefix: "\(key)")
    }
}

extension Signal where Error == Never {
    @available(*, deprecated, message: "Use `Signal` as `Workflow` instead")
    public func asWorker<Key: Equatable>(key: Key) -> SignalWorker<Key, Value> {
        return SignalWorker(key: key, signal: self)
    }
}
