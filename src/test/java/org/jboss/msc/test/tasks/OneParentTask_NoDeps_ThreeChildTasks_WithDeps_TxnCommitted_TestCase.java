/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
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

package org.jboss.msc.test.tasks;

import org.jboss.msc.txn.AbstractTransactionTest;
import org.jboss.msc.txn.TestExecutable;
import org.jboss.msc.txn.TestExecuteContext;
import org.jboss.msc.txn.TestTaskController;
import org.jboss.msc.txn.UpdateTransaction;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public final class OneParentTask_NoDeps_ThreeChildTasks_WithDeps_TxnCommitted_TestCase extends AbstractTransactionTest {

    /**
     * Scenario:
     * <UL>
     * <LI>parent completes at EXECUTE</LI>
     * <LI>child0 completes at EXECUTE</LI>
     * <LI>child1 completes at EXECUTE</LI>
     * <LI>child2 completes at EXECUTE, depends on child1</LI>
     * <LI>transaction committed</LI>
     * </UL>
     */
    @Test
    public void usecase1() {
        final UpdateTransaction transaction = newUpdateTransaction();
        // preparing child0 task
        final TestExecutable<Void> child0e = new TestExecutable<>();
        // preparing child1 task
        final TestExecutable<Void> child1e = new TestExecutable<>();
        // preparing child2 task
        final TestExecutable<Void> child2e = new TestExecutable<>();
        // installing parent task
        final TestExecutable<Void> parent0e = new TestExecutable<Void>() {
            @Override
            public void executeInternal(final TestExecuteContext<Void> ctx) {
                // installing child0 task
                final TestTaskController<Void> child0Controller = newTask(ctx, child0e);
                assertNotNull(child0Controller);
                // installing child1 task
                final TestTaskController<Void> child1Controller = newTask(ctx, child1e);
                assertNotNull(child1Controller);
                // installing child2 task
                final TestTaskController<Void> child2Controller = newTask(ctx, child2e, child1Controller);
                assertNotNull(child2Controller);
            }
        };
        final TestTaskController<Void> parentController = newTask(transaction, parent0e);
        assertNotNull(parentController);
        // preparing transaction
        prepare(transaction);
        // assert parent0 calls
        assertCalled(parent0e);
        // assert child0 calls
        assertCalled(child0e);
        // assert child1 calls
        assertCalled(child1e);
        // assert child2 calls
        assertCalled(child2e);
        // assert tasks ordering
        assertCallOrder(parent0e, child0e);
        assertCallOrder(parent0e, child1e, child2e);
        // committing transaction
        assertTrue(canCommit(transaction));
        commit(transaction);
    }

    /**
     * Scenario:
     * <UL>
     * <LI>parent completes at EXECUTE</LI>
     * <LI>child0 completes at EXECUTE</LI>
     * <LI>child1 completes at EXECUTE</LI>
     * <LI>child2 completes at EXECUTE, depends on child1 and child0</LI>
     * <LI>transaction committed</LI>
     * </UL>
     */
    @Test
    public void usecase2() {
        final UpdateTransaction transaction = newUpdateTransaction();
        // preparing child0 task
        final TestExecutable<Void> child0e = new TestExecutable<>();
        // preparing child1 task
        final TestExecutable<Void> child1e = new TestExecutable<>();
        // preparing child2 task
        final TestExecutable<Void> child2e = new TestExecutable<>();
        // installing parent task
        final TestExecutable<Void> parent0e = new TestExecutable<Void>() {
            @Override
            public void executeInternal(final TestExecuteContext<Void> ctx) {
                // installing child0 task
                final TestTaskController<Void> child0Controller = newTask(ctx, child0e);
                assertNotNull(child0Controller);
                // installing child1 task
                final TestTaskController<Void> child1Controller = newTask(ctx, child1e);
                assertNotNull(child1Controller);
                // installing child2 task
                final TestTaskController<Void> child2Controller = newTask(ctx, child2e, child0Controller, child1Controller);
                assertNotNull(child2Controller);
            }
        };
        final TestTaskController<Void> parentController = newTask(transaction, parent0e);
        assertNotNull(parentController);
        // preparing transaction
        prepare(transaction);
        // assert parent0 calls
        assertCalled(parent0e);
        // assert child0 calls
        assertCalled(child0e);
        // assert child1 calls
        assertCalled(child1e);
        // assert child2 calls
        assertCalled(child2e);
        // assert tasks ordering
        assertCallOrder(parent0e, child0e);
        assertCallOrder(parent0e, child1e);
        assertCallOrder(parent0e, child0e, child2e);
        assertCallOrder(parent0e, child1e, child2e);
        // committing transaction
        assertTrue(canCommit(transaction));
        commit(transaction);
    }

    /**
     * Scenario:
     * <UL>
     * <LI>parent completes at EXECUTE</LI>
     * <LI>child0 completes at EXECUTE</LI>
     * <LI>child1 completes at EXECUTE, depends on child0</LI>
     * <LI>child2 completes at EXECUTE, depends on child1</LI>
     * <LI>transaction committed</LI>
     * </UL>
     */
    @Test
    public void usecase3() {
        final UpdateTransaction transaction = newUpdateTransaction();
        // preparing child0 task
        final TestExecutable<Void> child0e = new TestExecutable<>();
        // preparing child1 task
        final TestExecutable<Void> child1e = new TestExecutable<>();
        // preparing child2 task
        final TestExecutable<Void> child2e = new TestExecutable<>();
        // installing parent task
        final TestExecutable<Void> parent0e = new TestExecutable<Void>() {
            @Override
            public void executeInternal(final TestExecuteContext<Void> ctx) {
                // installing child0 task
                final TestTaskController<Void> child0Controller = newTask(ctx, child0e);
                assertNotNull(child0Controller);
                // installing child1 task
                final TestTaskController<Void> child1Controller = newTask(ctx, child1e, child0Controller);
                assertNotNull(child1Controller);
                // installing child2 task
                final TestTaskController<Void> child2Controller = newTask(ctx, child2e, child1Controller);
                assertNotNull(child2Controller);
            }
        };
        final TestTaskController<Void> parentController = newTask(transaction, parent0e);
        assertNotNull(parentController);
        // preparing transaction
        prepare(transaction);
        // assert parent0 calls
        assertCalled(parent0e);
        // assert child0 calls
        assertCalled(child0e);
        // assert child1 calls
        assertCalled(child1e);
        // assert child2 calls
        assertCalled(child2e);
        // assert tasks ordering
        assertCallOrder(parent0e, child0e, child1e, child2e);
        // committing transaction
        assertTrue(canCommit(transaction));
        commit(transaction);
    }

    /**
     * Scenario:
     * <UL>
     * <LI>parent completes at EXECUTE</LI>
     * <LI>child0 completes at EXECUTE</LI>
     * <LI>child1 completes at EXECUTE, depends on child0</LI>
     * <LI>child2 completes at EXECUTE, depends on child1 and child0</LI>
     * <LI>transaction committed</LI>
     * </UL>
     */
    @Test
    public void usecase4() {
        final UpdateTransaction transaction = newUpdateTransaction();
        // preparing child0 task
        final TestExecutable<Void> child0e = new TestExecutable<>();
        // preparing child1 task
        final TestExecutable<Void> child1e = new TestExecutable<>();
        // preparing child2 task
        final TestExecutable<Void> child2e = new TestExecutable<>();
        // installing parent task
        final TestExecutable<Void> parent0e = new TestExecutable<Void>() {
            @Override
            public void executeInternal(final TestExecuteContext<Void> ctx) {
                // installing child0 task
                final TestTaskController<Void> child0Controller = newTask(ctx, child0e);
                assertNotNull(child0Controller);
                // installing child1 task
                final TestTaskController<Void> child1Controller = newTask(ctx, child1e, child0Controller);
                assertNotNull(child1Controller);
                // installing child2 task
                final TestTaskController<Void> child2Controller = newTask(ctx, child2e, child0Controller, child1Controller);
                assertNotNull(child2Controller);
            }
        };
        final TestTaskController<Void> parentController = newTask(transaction, parent0e);
        assertNotNull(parentController);
        // preparing transaction
        prepare(transaction);
        // assert parent0 calls
        assertCalled(parent0e);
        // assert child0 calls
        assertCalled(child0e);
        // assert child1 calls
        assertCalled(child1e);
        // assert child2 calls
        assertCalled(child2e);
        // assert tasks ordering
        assertCallOrder(parent0e, child0e, child1e, child2e);
        // committing transaction
        assertTrue(canCommit(transaction));
        commit(transaction);
    }

}
