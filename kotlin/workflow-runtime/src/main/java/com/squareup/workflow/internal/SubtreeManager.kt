/*
 * Copyright 2019 Square Inc.
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
package com.squareup.workflow.internal

import com.squareup.workflow.Snapshot
import com.squareup.workflow.StatefulWorkflow
import com.squareup.workflow.Workflow
import com.squareup.workflow.WorkflowAction
import com.squareup.workflow.WorkflowAction.Companion.noAction
import com.squareup.workflow.debugging.WorkflowUpdateDebugInfo.Kind
import com.squareup.workflow.debugging.WorkflowUpdateDebugInfo.Source
import com.squareup.workflow.internal.Behavior.WorkflowOutputCase
import com.squareup.workflow.parse
import com.squareup.workflow.readByteStringWithLength
import com.squareup.workflow.writeByteStringWithLength
import kotlinx.coroutines.selects.SelectBuilder
import kotlin.coroutines.CoroutineContext

/**
 * Responsible for tracking child workflows, starting them and tearing them down when necessary. Also
 * manages restoring children from snapshots.
 */
internal class SubtreeManager<StateT, OutputT : Any>(
  private val contextForChildren: CoroutineContext
) : RealRenderContext.Renderer<StateT, OutputT> {

  /**
   * When this manager's host is restored from a snapshot, its children snapshots are extracted into
   * this cache. Then, when those children are started for the first time, they are also restored
   * from their snapshots.
   */
  private val snapshotCache = mutableMapOf<AnyId, Snapshot>()

  private val hostLifetimeTracker =
    LifetimeTracker<WorkflowOutputCase<*, *, StateT, OutputT>, AnyId, WorkflowNode<*, *, *, *>>(
        getKey = { it.id },
        start = { it.createNode() },
        dispose = { case, host ->
          host.cancel()
          snapshotCache -= case.id
        }
    )

  // @formatter:off
  override fun <ChildPropsT, ChildOutputT : Any, ChildRenderingT>
      render(
        case: WorkflowOutputCase<ChildPropsT, ChildOutputT, StateT, OutputT>,
        child: Workflow<ChildPropsT, ChildOutputT, ChildRenderingT>,
        id: WorkflowId<ChildPropsT, ChildOutputT, ChildRenderingT>,
        props: ChildPropsT
      ): RenderingEnvelope<ChildRenderingT> {
  // @formatter:on
    // Start tracking this case so we can be ready to render it.
    @Suppress("UNCHECKED_CAST")
    val childNode = hostLifetimeTracker.ensure(case) as
        WorkflowNode<ChildPropsT, *, ChildOutputT, ChildRenderingT>
    return childNode.render(child.asStatefulWorkflow(), props)
  }

  /**
   * Ensures that a [WorkflowNode] is running for every workflow in [cases], and cancels any
   * running workflows that are not in the list.
   */
  fun track(cases: List<WorkflowOutputCase<*, *, StateT, OutputT>>) {
    // Add new children and remove old ones.
    hostLifetimeTracker.track(cases)
  }

  /**
   * Uses [selector] to invoke [WorkflowNode.tick] for every running child workflow this instance
   * is managing.
   *
   * If one of this workflow's children emits an output, [handler] will be called with the action
   * assigned to that workflow and a [Source.Subtree] describing the child that triggered the
   * update.
   *
   * Note that if [handler] is called with [Kind.Passthrough], the workflow action that gets
   * passed in will always be [noAction]. This is the only logical value because
   * [Kind.Passthrough] specifically means that this workflow did not actually get updated
   * itself, but one of its descendants did and this one is just part of the path down the tree.
   */
  fun <T : Any> tickChildren(
    selector: SelectBuilder<OutputEnvelope<T>>,
    handler: (WorkflowAction<StateT, OutputT>, Kind) -> OutputEnvelope<T>
  ) {
    for ((case, host) in hostLifetimeTracker.lifetimes) {
      host.tick(selector) { output ->
        return@tick if (output.output != null) {
          val componentUpdate = case.acceptChildOutput(output.output)
          // This workflow's child emitted a non-null output: the WorkflowNode will execute the
          // WorkflowAction, and we need to pass DidUpdate to indicate that this workflow actually
          // got updated.
          handler(
              componentUpdate,
              Kind.Updated(Source.Subtree(case.id.name, output.output, output.debugInfo))
          )
        } else {
          // The child didn't actually emit an output, which means this workflow has nothing to do,
          // which means we will only report our child updating.
          handler(noAction(), Kind.Passthrough(case.id.name, output.debugInfo))
        }
      }
    }
  }

  /**
   * Returns a [Snapshot] that contains snapshots of all children, associated with their IDs.
   */
  fun createChildrenSnapshot(): Snapshot {
    val childSnapshots = hostLifetimeTracker.lifetimes
        .map { (case, host) -> host.id to host.snapshot(case.workflow.asStatefulWorkflow()) }

    return Snapshot.write { sink ->
      sink.writeInt(childSnapshots.size)
      for ((id, snapshot) in childSnapshots) {
        sink.writeByteStringWithLength(id.toByteString())
        sink.writeByteStringWithLength(snapshot.bytes)
      }
    }
  }

  /**
   * Extracts child snapshots and IDs from [snapshot] and caches them, so the next time those state
   * machines are asked to be started, they are restored from their snapshots.
   */
  fun restoreChildrenFromSnapshot(snapshot: Snapshot) {
    snapshot.bytes.parse { source ->
      val childCount = source.readInt()
      val snapshots = List(childCount) {
        val id = restoreId(source.readByteStringWithLength())
        val childSnapshot = source.readByteStringWithLength()
        Pair(id, Snapshot.of(childSnapshot))
      }
      // Populate the snapshot cache so when we are asked to create hosts for the snapshotted IDs,
      // they will be restored from their snapshots.
      snapshotCache.putAll(snapshots.toMap())
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun <IC, OC : Any> WorkflowOutputCase<IC, OC, StateT, OutputT>.createNode():
      WorkflowNode<IC, *, OC, *> =
    WorkflowNode(
        id,
        workflow.asStatefulWorkflow() as StatefulWorkflow<IC, *, OC, *>,
        props,
        snapshotCache[id],
        contextForChildren
    )
}
