/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
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

package org.jboss.msc.txn;

import java.util.concurrent.TimeUnit;


/**
 * A transaction.
 * 
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public abstract class Transaction extends SimpleAttachable implements Attachable, TaskFactory {

    /**
     * Indicate whether the transaction was terminated.
     * @return {@code true} if the transaction have been committed or reverted, {@code false} otherwise.
     */
    public abstract boolean isTerminated();

    /**
     * Get the duration of the current transaction.
     *
     * @return the duration of the current transaction
     */
    public abstract long getDuration(TimeUnit unit);
}
