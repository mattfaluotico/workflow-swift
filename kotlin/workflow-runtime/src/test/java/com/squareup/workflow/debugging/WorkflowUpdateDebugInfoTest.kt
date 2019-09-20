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
package com.squareup.workflow.debugging

import com.squareup.workflow.debugging.WorkflowUpdateDebugInfo.Kind
import com.squareup.workflow.debugging.WorkflowUpdateDebugInfo.Source
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowUpdateDebugInfoTest {

  @Test fun `passthrough toDescriptionString`() {
    val update = WorkflowUpdateDebugInfo(
        "root type",
        Kind.Passthrough(
            "key",
            WorkflowUpdateDebugInfo(
                "child type",
                Kind.Updated(Source.Sink)
            )
        )
    )
    val expected = """
      root type passing through from workflow[key=key]
      ↳ child type updated from sink
    """.trimIndent()

    assertEquals(expected, update.toDescriptionString())
  }

  @Test fun `updated worker toDescriptionString`() {
    val update = WorkflowUpdateDebugInfo(
        "root type",
        Kind.Updated(Source.Worker("key", "output"))
    )
    val expected = """
      root type updated from worker[key=key]: output
    """.trimIndent()

    assertEquals(expected, update.toDescriptionString())
  }

  @Test fun `updated subtree toDescriptionString`() {
    val update = WorkflowUpdateDebugInfo(
        "root type",
        Kind.Updated(
            Source.Subtree(
                "key", "output",
                WorkflowUpdateDebugInfo(
                    "child type",
                    Kind.Updated(Source.Sink)
                )
            )
        )
    )
    val expected = """
      root type updated from workflow[key=key]: output
      ↳ child type updated from sink
    """.trimIndent()

    assertEquals(expected, update.toDescriptionString())
  }
}
