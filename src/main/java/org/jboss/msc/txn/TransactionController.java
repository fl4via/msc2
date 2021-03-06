/*
 * JBoss, Home of Professional Open Source
 *
 * Copyright 2013 Red Hat, Inc. and/or its affiliates.
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
package org.jboss.msc.txn;

import org.jboss.msc._private.MSCLogger;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceContext;

import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.jboss.msc._private.MSCLogger.TXN;
import static org.jboss.msc.txn.Helper.getAbstractTransaction;

/**
 * A transaction controller, creates transactions and manages them.
 
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 * @author <a href="mailto:frainone@redhat.com">Flavia Rainone</a>
 */
public final class TransactionController extends SimpleAttachable {

    private static final RuntimePermission TXN_CONTROLLER_CREATE_PERM = new RuntimePermission("canCreateTransactionController");

    private final ServiceContext serviceContext = new ServiceContextImpl(this);
    // TXN administration lock
    private final Object txnLock = new Object();
    // whether currently running TXNs are read-only or updating. There can be only single updating TXN at a time.
    private static boolean updatingTxnRunning;
    // count of running TXNs in this round
    private static int runningTxns;
    // TXNs that are pending execution, each item is either single updating TXN or set of reading TXNs
    private static final Deque<PendingTxnEntry> pendingTxns = new LinkedList<>();

    private TransactionController() {}

    public static TransactionController createInstance() {
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(TXN_CONTROLLER_CREATE_PERM);
        }
        return new TransactionController();
    }

    /**
     * Creates a new read-only transaction. This method is equivalent to calling
     * {@link #createReadTransaction(java.util.concurrent.Executor, org.jboss.msc.txn.Problem.Severity, Listener)}
     * with <B>WARNING</B> problem serverity.
     *
     * @param executor the executor to use to run tasks
     * @param listener transaction creation completion listener
     * @throws IllegalArgumentException if any parameter is null
     */
    public void createReadTransaction(final Executor executor, final Listener<ReadTransaction> listener) throws IllegalArgumentException {
        createReadTransaction(executor, Problem.Severity.WARNING, listener);
    }

    /**
     * Creates a new read-only transaction. This method is asynchronous in its nature.
     * The completion <B>listener</B> is called when read-only transaction is created and ready to be used.
     *
     * @param executor the executor to use to run tasks
     * @param maxSeverity the maximum severity to allow
     * @param listener transaction creation completion listener
     * @throws IllegalArgumentException if any parameter is {@code null} or if <B>maxSeverity</B> is equal or higher than <B>CRITICAL</B>
     */
    public void createReadTransaction(final Executor executor, final Problem.Severity maxSeverity, final Listener<ReadTransaction> listener) throws IllegalArgumentException {
        if (executor == null) {
            throw TXN.methodParameterIsNull("executor");
        }
        if (maxSeverity == null) {
            throw TXN.methodParameterIsNull("maxSeverity");
        }
        if (maxSeverity.compareTo(Problem.Severity.CRITICAL) >= 0) {
            throw TXN.illegalSeverity("maxSeverity");
        }
        if (listener == null) {
            throw TXN.methodParameterIsNull("listener");
        }
        registerReadTransaction(new BasicReadTransaction(this, executor, maxSeverity), listener);
    }

    /**
     * Creates a new updating transaction. This method is equivalent to calling
     * {@link #createUpdateTransaction(java.util.concurrent.Executor, org.jboss.msc.txn.Problem.Severity, Listener)}
     * with <B>WARNING</B> problem serverity.
     *
     * @param executor the executor to use to run tasks
     * @param listener transaction creation completion listener
     * @throws IllegalArgumentException if any parameter is null
     */
    public void createUpdateTransaction(final Executor executor, final Listener<UpdateTransaction> listener) throws IllegalArgumentException {
        createUpdateTransaction(executor, Problem.Severity.WARNING, listener);
    }

    /**
     * Creates a new updating transaction. This method is asynchronous in its nature.
     * The completion <B>listener</B> is called when updating transaction is created and ready to be used.
     *
     * @param executor the executor to use to run tasks
     * @param maxSeverity the maximum severity to allow
     * @param listener transaction creation completion listener
     * @throws IllegalArgumentException if any parameter is {@code null} or if <B>maxSeverity</B> is equal or higher than <B>CRITICAL</B>
     */
    public void createUpdateTransaction(final Executor executor, final Problem.Severity maxSeverity, final Listener<UpdateTransaction> listener) throws IllegalArgumentException {
        if (executor == null) {
            throw TXN.methodParameterIsNull("executor");
        }
        if (maxSeverity == null) {
            throw TXN.methodParameterIsNull("maxSeverity");
        }
        if (maxSeverity.compareTo(Problem.Severity.CRITICAL) >= 0) {
            throw TXN.illegalSeverity("maxSeverity");
        }
        if (listener == null) {
            throw TXN.methodParameterIsNull("listener");
        }
        registerUpdateTransaction(new BasicUpdateTransaction(new BasicReadTransaction(this, executor, maxSeverity)), listener);
    }

    /**
     * Restarts prepared update transaction.
     * @param txn to be restarted.
     * @throws IllegalArgumentException if any parameter is null
     * @throws SecurityException if there's a <B>TransactionController</B> mismatch
     * @throws InvalidTransactionStateException if transaction is not in prepared state or on attempt to restart it more than once
     */
    public void restart(final UpdateTransaction txn) throws IllegalArgumentException, SecurityException, InvalidTransactionStateException {
        final BasicUpdateTransaction basicUpdateTxn = validateTransaction(txn);
        basicUpdateTxn.getDelegate().restart();
    }

    /**
     * Downgrades updating <B>updateTxn</B> transaction to read-only transaction.
     * This operation succeeds iff <B>updateTxn</B> didn't modify anything in MSC runtime.
     * If downgrade is not successful, the completion listener will never be called.
     * But once downgrade is successful (indicated by returning <B>true</B> from the method)
     * <B>updateTxn</B> is invalidated and cannot be used by user anymore. Instead,
     * user have to wait for completion <B>listener</B> to be called to get reference to transformed
     * read-only transaction and use this new reference instead of previous <B>updateTxn</B> reference.<P/><P/>
     * Sample usage:
     * <PRE>
     *     private void executeSomeReadOnlyTasks(final ReadTransaction txn) {
     *         // ... some code using read-only txn
     *     }
     *
     *     private void foo() {
     *         UpdateTransaction updateTxn = ...
     *         Listener&lt;ReadTransaction&gt; completionListener = new Listener&lt;&gt;() {
     *             public void handleEvent(final ReadTransaction txn) {
     *                 executeSomeReadOnlyTasks(txn);
     *             }
     *         };
     *         final boolean success = TransactionController.downgradeTransaction(updateTxn, completionListener);
     *         if (success) {
     *             // code in completion listener will execute sometime ...
     *         } else {
     *             executeSomeReadOnlyTasks(updateTxn); // UpdateTransaction also implements ReadTransaction, executing code now ...
     *         }
     *     }
     * </PRE>
     * @param updateTxn transaction to be transformed to read-only transaction
     * @param listener transaction transformation completion listener
     * @return {@code true} if downgrade was successful, {@code false} otherwise
     * @throws IllegalArgumentException if any parameter is null
     * @throws SecurityException if there's a <B>TransactionController</B> mismatch
     */
    public boolean downgradeTransaction(final UpdateTransaction updateTxn, final Listener<ReadTransaction> listener) throws IllegalArgumentException, SecurityException {
        final BasicUpdateTransaction basicUpdateTxn = validateTransaction(updateTxn);
        if (listener == null) {
            throw TXN.methodParameterIsNull("listener");
        }
        // if basicUpdateTxn didn't modify anything, convert it
        BasicReadTransaction basicReadTxn = null;
        synchronized (basicUpdateTxn) {
            if (basicUpdateTxn.isModified()) {
                // if transaction modified anything we cannot downgrade
                return false;
            }
            basicReadTxn = basicUpdateTxn.getDelegate();
            basicUpdateTxn.invalidate();
        }
        List<PendingTxnEntry> notifications = null;
        synchronized (txnLock) {
            assert runningTxns == 1;
            updatingTxnRunning = false;
            if (pendingTxns.size() > 0) {
                pendingTxns.addFirst(new PendingTxnEntry(basicReadTxn, listener));
                runningTxns--;
                notifications = getNotifications();
            }
        }
        if (notifications != null) {
            for (final PendingTxnEntry notification : notifications) {
                safeCallListener(notification.listener, notification.txn);
            }
        } else {
            safeCallListener(listener, basicReadTxn);
        }
        return true;
    }

    /**
     * Upgrades read-only <B>readTxn</B> transaction to updating transaction.
     * This operation succeeds iff  there's no pending <B>UpdateTransaction</B>
     * (waiting in the transaction creation request queue).
     * If upgrade is not successful, the completion listener will never be called.
     * If upgrade is successful (indicated by returning <B>true</B> from the method)
     * <B>readTxn</B> remains still valid and can be used by user anytime. Further more
     * user can wait for completion <B>listener</B> to be called and obtain the reference to transformed
     * updating transaction and use this new reference to do some modification tasks.<P/><P/>
     * Sample usage:
     * <PRE>
     *     private void executeSomeModifyingTasks(final UpdateTransaction txn) {
     *         // ... some code using updating txn
     *     }
     *
     *     private void foo() {
     *         ReadTransaction readTxn = ...
     *         Listener&lt;UpdateTransaction&gt; completionListener = new Listener&lt;&gt;() {
     *             public void handleEvent(final UpdateTransaction txn) {
     *                 executeSomeModifyingTasks(txn);
     *             }
     *         };
     *         final boolean success = TransactionController.upgradeTransaction(readTxn, completionListener);
     *         if (success) {
     *             // code in completion listener will execute sometime ...
     *         } else {
     *             // we cannot execute modifying tasks with read-only transaction :(
     *             throw new RuntimeException();
     *         }
     *     }
     * </PRE>
     * @param readTxn transaction to be transformed to updating transaction
     * @param listener transaction transformation completion listener
     * @return {@code true} if upgrade was successful, {@code false} otherwise
     * @throws IllegalArgumentException if any parameter is null
     * @throws SecurityException if there's a <B>TransactionController</B> mismatch
     */
    public boolean upgradeTransaction(final ReadTransaction readTxn, final Listener<UpdateTransaction> listener) throws IllegalArgumentException, SecurityException {
        final BasicReadTransaction basicReadTxn = validateTransaction(readTxn);
        if (listener == null) {
            throw TXN.methodParameterIsNull("listener");
        }
        if (readTxn instanceof UpdateTransaction) {
            safeCallListener(listener, readTxn);
            return true;
        }
        synchronized (txnLock) {
            assert runningTxns > 0;
            if (!pendingTxns.isEmpty()) {
                // cannot be upgraded because there are some pending updating txns registered already
                return false;
            }
            if (runningTxns == 1) {
                updatingTxnRunning = true;
            } else {
                pendingTxns.add(new PendingTxnEntry(new BasicUpdateTransaction(basicReadTxn), listener));
                runningTxns--;
                return true;
            }
        }
        safeCallListener(listener, new BasicUpdateTransaction(basicReadTxn));
        return true;
    }

    private static final class PendingTxnEntry {
        private final Transaction txn;
        private final Listener<? extends Transaction> listener;

        private PendingTxnEntry(final Transaction txn, final Listener<? extends Transaction> listener) {
            this.txn = txn;
            this.listener = listener;
        }
    }

    private void registerUpdateTransaction(final UpdateTransaction updateTxn, final Listener<UpdateTransaction> listener) {
        synchronized (txnLock) {
            if (runningTxns == 0) {
                updatingTxnRunning = true;
                runningTxns++;
            } else {
                pendingTxns.add(new PendingTxnEntry(updateTxn, listener));
                return;
            }
        }
        safeCallListener(listener, updateTxn);
    }

    private void registerReadTransaction(final ReadTransaction readTxn, final Listener<ReadTransaction> listener) {
        synchronized (txnLock) {
            if (runningTxns == 0) {
                runningTxns++;
            } else if (!updatingTxnRunning && pendingTxns.isEmpty()) {
                runningTxns++;
            } else {
                pendingTxns.add(new PendingTxnEntry(readTxn, listener));
                return;
            }
        }
        safeCallListener(listener, readTxn);
    }

    void unregister() {
        List<PendingTxnEntry> notifications = null;
        synchronized (txnLock) {
            assert runningTxns > 0;
            runningTxns--;
            if (runningTxns == 0) {
                updatingTxnRunning = false;
                if (pendingTxns.isEmpty()) return;
                notifications = getNotifications();
            }
        }
        for (final PendingTxnEntry notification : notifications) {
            safeCallListener(notification.listener, notification.txn);
        }
    }

    private List<PendingTxnEntry> getNotifications() {
        assert Thread.holdsLock(txnLock);
        final List<PendingTxnEntry> notifications = new LinkedList<>();
        PendingTxnEntry entry = pendingTxns.removeFirst();
        notifications.add(entry);
        runningTxns++;
        if (entry.txn instanceof UpdateTransaction) {
            // process single updating transaction at the head
            updatingTxnRunning = true;
        } else {
            // process remaining read-only transactions at the head
            final Iterator<PendingTxnEntry> i = pendingTxns.iterator();
            while (i.hasNext()) {
                entry = i.next();
                if (entry.txn instanceof UpdateTransaction) break;
                i.remove();
                notifications.add(entry);
                runningTxns++;
            }
        }
        return notifications;
    }

    @SuppressWarnings("unchecked")
    private static void safeCallListener(final Listener<? extends Transaction> completionListener, final Transaction txn) {
        try {
            ((Listener<Transaction>)completionListener).handleEvent(txn);
        } catch (final Throwable t) {
            MSCLogger.ROOT.transactionCreationCompletionListenerFailed(t);
        }
    }

    /**
     * Create a new service container.
     *
     * @return new service container.
     */
    public ServiceContainer createServiceContainer() {
        return new ServiceContainerImpl(this);
    }

    /**
     * Get the transaction executor.
     * 
     * @param transaction the transaction
     * @return the transaction executor
     * @throws SecurityException if transaction was not created by this controller
     */
    public Executor getExecutor(final Transaction transaction) throws SecurityException {
        validateTransaction(transaction);
        return getAbstractTransaction(transaction).getExecutor();
    }

    /**
     * Get the transaction problem report.
     * 
     * @param transaction the transaction
     * @return the transaction report
     * @throws SecurityException if transaction was not created by this controller
     */
    public ProblemReport getReport(final Transaction transaction) throws SecurityException {
        validateTransaction(transaction);
        return transaction.getReport();
    }

    /**
     * Returns the service context, for creating and removing services.
     * 
     * @return the service context
     */
    public ServiceContext getServiceContext() {
        return serviceContext;
    }
    
    /**
     * Adds a task with an executable component to {@code transaction}.
     *
     * @param transaction the transaction
     * @param task        the task
     * @return the subtask builder
     * @throws IllegalStateException if the transaction is not open
     * @throws SecurityException if transaction was not created by this controller
     */
    <T> TaskBuilder<T> newTask(final Transaction transaction, final Executable<T> task) throws IllegalStateException, SecurityException {
        validateTransaction(transaction);
        return getAbstractTransaction(transaction).getTaskFactory().newTask(task);
    }

    /**
     * Prepare {@code transaction}.  It is an error to prepare a transaction with unreleased tasks.
     * Once this method returns, either {@link #commit(Transaction, Listener)} or {@link #restart(UpdateTransaction)} must be called.
     * After calling this method (regardless of its outcome), the transaction can not be directly modified before termination.
     *
     *
     * @param transaction        the transaction to be prepared
     * @param completionListener the listener to call when the prepare is complete or has failed
     * @throws InvalidTransactionStateException if the transaction has already been rolled back, prepared or committed
     * @throws SecurityException if transaction was not created by this controller
     */
    @SuppressWarnings("unchecked")
    public <T extends Transaction> void prepare(final T transaction, final Listener<? super PrepareResult<T>> completionListener) throws InvalidTransactionStateException, SecurityException {
        validateTransaction(transaction);
        getAbstractTransaction(transaction).prepare((Listener<? super PrepareResult<? extends Transaction>>)completionListener);
    }

    /**
     * Commit the work done by {@link #prepare(Transaction, Listener)} and terminate {@code transaction}.
     *
     * @param transaction        the transaction to be committed
     * @param completionListener the listener to call when the commit is complete
     * @throws InvalidTransactionStateException if the transaction has already been committed or has not yet been prepared
     * @throws SecurityException if transaction was not created by this controller
     */
    @SuppressWarnings("unchecked")
    public <T extends Transaction> void commit(final T transaction, final Listener<? super CommitResult<T>> completionListener) throws InvalidTransactionStateException, SecurityException {
        validateTransaction(transaction);
        getAbstractTransaction(transaction).commit((Listener<? super CommitResult<? extends Transaction>>)completionListener);
    }

    /**
     * Determine whether a prepared transaction can be committed.  If it cannot, it must be aborted.
     *
     * @param transaction the transaction
     * @return {@code true} if the transaction can be committed, {@code false} if it must be aborted
     * @throws InvalidTransactionStateException if the transaction is not prepared
     * @throws SecurityException if transaction was not created by this controller
     */
    public boolean canCommit(final Transaction transaction) throws InvalidTransactionStateException, SecurityException {
        validateTransaction(transaction);
        return getAbstractTransaction(transaction).canCommit();
    }

    /**
     * Determines whether the specified transaction have been created by this controller.
     * @param transaction to be checked
     * @return <code>true</code> if {@code transaction} have been created by this controller, <code>false</code> otherwise
     */
    public boolean owns(final Transaction transaction) {
        if (transaction == null) {
            throw TXN.methodParameterIsNull("transaction");
        }
        final boolean isUpdateTransaction = transaction instanceof BasicUpdateTransaction && ((BasicUpdateTransaction)transaction).getDelegate().txnController == this;
        final boolean isReadTransaction = transaction instanceof BasicReadTransaction && ((BasicReadTransaction)transaction).txnController == this;
        return isUpdateTransaction || isReadTransaction;
    }

    private void validateTransaction(final Transaction transaction) throws SecurityException {
        if (transaction == null) {
            throw TXN.methodParameterIsNull("transaction");
        }
        if (!owns(transaction)) {
            throw new SecurityException("Transaction not created by this controller");
        }
    }

    private BasicReadTransaction validateTransaction(final ReadTransaction readTxn) throws SecurityException {
        if (readTxn == null) {
            throw TXN.methodParameterIsNull("readTxn");
        }
        final boolean isReadTxn = readTxn instanceof BasicReadTransaction;
        final boolean isUpdateTxn = readTxn instanceof BasicUpdateTransaction;
        if (!isReadTxn && !isUpdateTxn) {
            throw new SecurityException("Transaction not created by this controller");
        }
        final BasicReadTransaction basicReadTxn = isUpdateTxn ? ((BasicUpdateTransaction)readTxn).getDelegate() : (BasicReadTransaction) readTxn;
        if (basicReadTxn.txnController != this) {
            throw new SecurityException("Transaction not created by this controller");
        }
        return basicReadTxn;
    }

    private BasicUpdateTransaction validateTransaction(final UpdateTransaction updateTxn) throws IllegalArgumentException, SecurityException {
        if (updateTxn == null) {
            throw TXN.methodParameterIsNull("updateTxn");
        }
        if (!(updateTxn instanceof BasicUpdateTransaction)) {
            throw new SecurityException("Transaction not created by this controller");
        }
        final BasicUpdateTransaction basicUpdateTxn = (BasicUpdateTransaction)updateTxn;
        if (basicUpdateTxn.getController() != this) {
            throw new SecurityException("Transaction not created by this controller");
        }
        return basicUpdateTxn;
    }
}
