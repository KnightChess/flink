/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.checkpoint;

import org.apache.flink.runtime.execution.ExecutionState;
import org.apache.flink.runtime.executiongraph.ExecutionGraph;
import org.apache.flink.runtime.executiongraph.ExecutionGraphCheckpointPlanCalculatorContext;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.jobgraph.tasks.CheckpointCoordinatorConfiguration;
import org.apache.flink.runtime.state.SharedStateRegistry;
import org.apache.flink.runtime.state.memory.MemoryStateBackend;
import org.apache.flink.util.TestLogger;
import org.apache.flink.util.concurrent.Executors;
import org.apache.flink.util.concurrent.ManuallyTriggeredScheduledExecutor;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/** Tests for actions of {@link CheckpointCoordinator} on task failures. */
public class FailoverStrategyCheckpointCoordinatorTest extends TestLogger {
    private ManuallyTriggeredScheduledExecutor manualThreadExecutor;

    @Before
    public void setUp() {
        manualThreadExecutor = new ManuallyTriggeredScheduledExecutor();
    }

    /**
     * Tests that {@link CheckpointCoordinator#abortPendingCheckpoints(CheckpointException)} called
     * on job failover could handle the {@code currentPeriodicTrigger} null case well.
     */
    @Test
    public void testAbortPendingCheckpointsWithTriggerValidation() throws Exception {
        final int maxConcurrentCheckpoints = ThreadLocalRandom.current().nextInt(10) + 1;
        ExecutionGraph graph =
                new CheckpointCoordinatorTestingUtils.CheckpointExecutionGraphBuilder()
                        .addJobVertex(new JobVertexID())
                        .setTransitToRunning(false)
                        .build();
        CheckpointCoordinatorConfiguration checkpointCoordinatorConfiguration =
                new CheckpointCoordinatorConfiguration(
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE,
                        0,
                        maxConcurrentCheckpoints,
                        CheckpointRetentionPolicy.NEVER_RETAIN_AFTER_TERMINATION,
                        true,
                        false,
                        0,
                        0);
        CheckpointCoordinator checkpointCoordinator =
                new CheckpointCoordinator(
                        graph.getJobID(),
                        checkpointCoordinatorConfiguration,
                        Collections.emptyList(),
                        new StandaloneCheckpointIDCounter(),
                        new StandaloneCompletedCheckpointStore(1),
                        new MemoryStateBackend(),
                        Executors.directExecutor(),
                        new CheckpointsCleaner(),
                        manualThreadExecutor,
                        SharedStateRegistry.DEFAULT_FACTORY,
                        mock(CheckpointFailureManager.class),
                        new DefaultCheckpointPlanCalculator(
                                graph.getJobID(),
                                new ExecutionGraphCheckpointPlanCalculatorContext(graph),
                                graph.getVerticesTopologically(),
                                false),
                        new ExecutionAttemptMappingProvider(graph.getAllExecutionVertices()));

        // switch current execution's state to running to allow checkpoint could be triggered.
        graph.transitionToRunning();
        graph.getAllExecutionVertices()
                .forEach(
                        task ->
                                task.getCurrentExecutionAttempt()
                                        .transitionState(ExecutionState.RUNNING));

        checkpointCoordinator.startCheckpointScheduler();
        assertTrue(checkpointCoordinator.isCurrentPeriodicTriggerAvailable());
        // only trigger the periodic scheduling
        // we can't trigger all scheduled task, because there is also a cancellation scheduled
        manualThreadExecutor.triggerPeriodicScheduledTasks();
        manualThreadExecutor.triggerAll();
        assertEquals(1, checkpointCoordinator.getNumberOfPendingCheckpoints());

        for (int i = 1; i < maxConcurrentCheckpoints; i++) {
            checkpointCoordinator.triggerCheckpoint(false);
            manualThreadExecutor.triggerAll();
            assertEquals(i + 1, checkpointCoordinator.getNumberOfPendingCheckpoints());
            assertTrue(checkpointCoordinator.isCurrentPeriodicTriggerAvailable());
        }

        // as we only support limited concurrent checkpoints, after checkpoint triggered more than
        // the limits,
        // the currentPeriodicTrigger would been assigned as null.
        checkpointCoordinator.triggerCheckpoint(false);
        manualThreadExecutor.triggerAll();
        assertEquals(
                maxConcurrentCheckpoints, checkpointCoordinator.getNumberOfPendingCheckpoints());

        checkpointCoordinator.abortPendingCheckpoints(
                new CheckpointException(CheckpointFailureReason.JOB_FAILOVER_REGION));
        // after aborting checkpoints, we ensure currentPeriodicTrigger still available.
        assertTrue(checkpointCoordinator.isCurrentPeriodicTriggerAvailable());
        assertEquals(0, checkpointCoordinator.getNumberOfPendingCheckpoints());
    }
}
