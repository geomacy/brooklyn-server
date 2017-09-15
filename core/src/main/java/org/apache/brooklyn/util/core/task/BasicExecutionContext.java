/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.util.core.task;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.mgmt.ExecutionContext;
import org.apache.brooklyn.api.mgmt.ExecutionManager;
import org.apache.brooklyn.api.mgmt.HasTaskChildren;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.mgmt.TaskAdaptable;
import org.apache.brooklyn.api.mgmt.entitlement.EntitlementContext;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.mgmt.BrooklynTaskTags;
import org.apache.brooklyn.core.mgmt.BrooklynTaskTags.WrappedEntity;
import org.apache.brooklyn.core.mgmt.entitlement.Entitlements;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.collections.MutableSet;
import org.apache.brooklyn.util.core.task.ImmediateSupplier.ImmediateUnsupportedException;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.time.CountdownTimer;
import org.apache.brooklyn.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.common.collect.Iterables;

/**
 * A means of executing tasks against an ExecutionManager with a given bucket/set of tags pre-defined
 * (so that it can look like an {@link Executor} and also supply {@link ExecutorService#submit(Callable)}
 */
public class BasicExecutionContext extends AbstractExecutionContext {
    
    private static final Logger log = LoggerFactory.getLogger(BasicExecutionContext.class);
    
    static final ThreadLocal<BasicExecutionContext> perThreadExecutionContext = new ThreadLocal<BasicExecutionContext>();
    
    public static BasicExecutionContext getCurrentExecutionContext() { return perThreadExecutionContext.get(); }

    final ExecutionManager executionManager;
    final Set<Object> tags = new LinkedHashSet<Object>();

    public BasicExecutionContext(ExecutionManager executionManager) {
        this(executionManager, null);
    }
    
    /**
     * As {@link #BasicExecutionContext(ExecutionManager, Iterable)} but taking a flags map.
     * Supported flags are {@code tag} and {@code tags}
     * 
     * @see ExecutionManager#submit(Map, TaskAdaptable)
     * @deprecated since 0.12.0 use {@link #BasicExecutionContext(ExecutionManager, Iterable)}
     */
    @Deprecated
    public BasicExecutionContext(Map<?, ?> flags, ExecutionManager executionManager) {
        this(executionManager, MutableSet.of().put(flags.remove("tag")).putAll((Iterable<?>)flags.remove("tag")));
        if (!flags.isEmpty()) {
            log.warn("Unexpected flags passed to execution context ("+tags+"): "+flags,
                new Throwable("Trace for unexpected flags passed to execution context"));
        }
    }
    
    /**
     * Creates an execution context which wraps {@link ExecutionManager}
     * adding the given tags to all tasks submitted through this context.
     */
    public BasicExecutionContext(ExecutionManager executionManager, Iterable<Object> tagsForThisContext) {
        this.executionManager = executionManager;
        if (tagsForThisContext!=null) Iterables.addAll(tags, tagsForThisContext);

        // brooklyn-specific check, just for sanity
        // the context tag should always be a non-proxy entity, because that is what is passed to effector tasks
        // which may require access to internal methods
        // (could remove this check if generalizing; it has been here for a long time and the problem seems gone)
        for (Object tag: tags) {
            if (tag instanceof BrooklynTaskTags.WrappedEntity) {
                if (Proxy.isProxyClass(((WrappedEntity)tag).entity.getClass())) {
                    log.warn(""+this+" has entity proxy in "+tag);
                }
            }
        }
    }

    public ExecutionManager getExecutionManager() {
        return executionManager;
    }
    
    /** returns tasks started by this context (or tasks which have all the tags on this object) */
    @Override
    public Set<Task<?>> getTasks() { return executionManager.getTasksWithAllTags(tags); }

    @Override
    public <T> T get(TaskAdaptable<T> task) {
        final TaskInternal<T> t = (TaskInternal<T>) task.asTask();
        
        if (t.isQueuedOrSubmitted()) {
            if (t.isDone()) {
                return t.getUnchecked();
            } else {
                throw new ImmediateUnsupportedException("Task is in progress and incomplete: "+t);
            }
        }

        return runInSameThread(t, new Callable<Maybe<T>>() {
            public Maybe<T> call() {
                try {
                    return Maybe.of(t.getJob().call());
                } catch (Exception e) {
                    Exceptions.propagateIfFatal(e);
                    return Maybe.absent(e);
                }
            }
        }).get();
    }
    
    private static class SimpleFuture<T> implements Future<T> {
        boolean cancelled = false;
        boolean done = false;
        Maybe<T> result;
        
        public synchronized Maybe<T> set(Maybe<T> result) {
            this.result = result;
            done = true;
            notifyAll();
            return result;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            return result.get();
        }

        @Override
        public synchronized T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            if (isDone()) return get();
            CountdownTimer time = CountdownTimer.newInstanceStarted( Duration.of(timeout, unit) );
            while (!time.isExpired()) {
                wait(time.getDurationRemaining().lowerBound(Duration.ONE_MILLISECOND).toMilliseconds());
                if (isDone()) return get();
            }
            throw new TimeoutException();
        }
    }
    
    private <T> Maybe<T> runInSameThread(final Task<T> task, Callable<Maybe<T>> job) {
        ((TaskInternal<T>)task).getMutableTags().addAll(tags);
        
        Task<?> previousTask = BasicExecutionManager.getPerThreadCurrentTask().get();
        BasicExecutionContext oldExecutionContext = getCurrentExecutionContext();
        registerPerThreadExecutionContext();
        ((BasicExecutionManager)executionManager).beforeSubmitInSameThreadTask(null, task);
        
        SimpleFuture<T> future = new SimpleFuture<>();
        Throwable error = null;
        try {
            ((BasicExecutionManager)executionManager).afterSubmitRecordFuture(task, future);
            ((BasicExecutionManager)executionManager).beforeStartInSameThreadTask(null, task);

            // TODO this does not apply the same context-switching logic as submit
            
            return future.set(job.call());
            
        } catch (Exception e) {
            error = e;
            future.set(Maybe.absent(e));
            throw Exceptions.propagate(e);
            
        } finally {
            try {
                ((BasicExecutionManager)executionManager).afterEndInSameThreadTask(null, task, error);
            } finally {
                BasicExecutionManager.getPerThreadCurrentTask().set(previousTask);
                perThreadExecutionContext.set(oldExecutionContext);
            }
        }
    }
    
    /** performs execution without spawning a new task thread, though it does temporarily set a fake task for the purpose of getting context;
     * currently supports {@link Supplier}, {@link Callable}, {@link Runnable}, or {@link Task} instances; 
     * with tasks if it is submitted or in progress,
     * it fails if not completed; with unsubmitted, unqueued tasks, it gets the {@link Callable} job and 
     * uses that; with such a job, or any other callable/supplier/runnable, it runs that
     * in an {@link InterruptingImmediateSupplier}, with as much metadata as possible (eg task name if
     * given a task) set <i>temporarily</i> in the current thread context */
    @SuppressWarnings("unchecked")
    @Override
    public <T> Maybe<T> getImmediately(Object callableOrSupplier) {
        BasicTask<T> fakeTaskForContext;
        if (callableOrSupplier instanceof BasicTask) {
            fakeTaskForContext = (BasicTask<T>)callableOrSupplier;
            if (fakeTaskForContext.isQueuedOrSubmitted()) {
                if (fakeTaskForContext.isDone()) {
                    return Maybe.of((T)fakeTaskForContext.getUnchecked());
                } else {
                    throw new ImmediateUnsupportedException("Task is in progress and incomplete: "+fakeTaskForContext);
                }
            }
            callableOrSupplier = fakeTaskForContext.getJob();
        } else {
            fakeTaskForContext = new BasicTask<T>(MutableMap.of("displayName", "immediate evaluation"));
        }
        final ImmediateSupplier<T> job = callableOrSupplier instanceof ImmediateSupplier ? (ImmediateSupplier<T>) callableOrSupplier 
            : InterruptingImmediateSupplier.<T>of(callableOrSupplier);
        fakeTaskForContext.tags.add(BrooklynTaskTags.IMMEDIATE_TASK_TAG);
        fakeTaskForContext.tags.add(BrooklynTaskTags.TRANSIENT_TASK_TAG);

        return runInSameThread(fakeTaskForContext, new Callable<Maybe<T>>() {
            public Maybe<T> call() {
                fakeTaskForContext.cancel();
                
                boolean wasAlreadyInterrupted = Thread.interrupted();
                try {
                    return job.getImmediately();
                } finally {
                    if (wasAlreadyInterrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            } });
    }
    
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    protected <T> Task<T> submitInternal(Map<?,?> propertiesQ, final Object task) {
        if (task instanceof TaskAdaptable<?> && !(task instanceof Task<?>)) 
            return submitInternal(propertiesQ, ((TaskAdaptable<?>)task).asTask());
        
        Map properties = MutableMap.copyOf(propertiesQ);
        Collection taskTags;
        if (properties.get("tags")==null) {
            taskTags = new ArrayList();
        } else {
            taskTags = new ArrayList((Collection)properties.get("tags"));
        }
        properties.put("tags", taskTags);
        
        // FIXME some of this is brooklyn-specific logic, should be moved to a BrooklynExecContext subclass;
        // the issue is that we want to ensure that cross-entity calls switch execution contexts;
        // previously it was all very messy how that was handled (and it didn't really handle it in many cases)
        if (task instanceof Task<?>) taskTags.addAll( ((Task<?>)task).getTags() ); 
        Entity target = BrooklynTaskTags.getWrappedEntityOfType(taskTags, BrooklynTaskTags.TARGET_ENTITY);

        checkUserSuppliedContext(task, taskTags);

        if (target!=null && !tags.contains(BrooklynTaskTags.tagForContextEntity(target))) {
            // task is switching execution context boundaries
            /* 
             * longer notes:
             * you fall in to this block if the caller requests a target entity different to the current context 
             * (e.g. where entity X is invoking an effector on Y, it will start in X's context, 
             * but the effector should run in Y's context).
             * 
             * if X is invoking an effector on himself in his own context, or a sensor or other task, it will not come in to this block.
             */
            final ExecutionContext tc = ((EntityInternal)target).getExecutionContext();
            if (log.isDebugEnabled())
                log.debug("Switching task context on execution of "+task+": from "+this+" to "+target+" (in "+Tasks.current()+")");
            
            if (task instanceof Task<?>) {
                final Task<T> t = (Task<T>)task;
                if (!Tasks.isQueuedOrSubmitted(t) && (!(Tasks.current() instanceof HasTaskChildren) || 
                        !Iterables.contains( ((HasTaskChildren)Tasks.current()).getChildren(), t ))) {
                    // this task is switching execution context boundaries _and_ it is not a child and not yet queued,
                    // so wrap it in a task running in this context to keep a reference to the child
                    // (this matters when we are navigating in the GUI; without it we lose the reference to the child 
                    // when browsing in the context of the parent)
                    return submit(Tasks.<T>builder().displayName("Cross-context execution: "+t.getDescription()).dynamic(true).body(new Callable<T>() {
                        @Override
                        public T call() { 
                            return DynamicTasks.get(t); 
                        }
                    }).build());
                } else {
                    // if we are already tracked by parent, just submit it 
                    return tc.submit(t);
                }
            } else {
                // as above, but here we are definitely not a child (what we are submitting isn't even a task)
                // (will only come here if properties defines tags including a target entity, which probably never happens) 
                submit(Tasks.<T>builder().displayName("Cross-context execution").dynamic(true).body(() -> {
                    if (task instanceof Callable) {
                        return DynamicTasks.queue( Tasks.<T>builder().dynamic(false).body((Callable<T>)task).build() ).getUnchecked();
                    } else if (task instanceof Runnable) {
                        return DynamicTasks.queue( Tasks.<T>builder().dynamic(false).body((Runnable)task).build() ).getUnchecked();
                    } else {
                        throw new IllegalArgumentException("Unhandled task type: "+task+"; type="+(task!=null ? task.getClass() : "null"));
                    }
                }).build());
            }
        }

        EntitlementContext entitlementContext = BrooklynTaskTags.getEntitlement(taskTags);
        if (entitlementContext==null)
        entitlementContext = Entitlements.getEntitlementContext();
        if (entitlementContext!=null) {
            taskTags.add(BrooklynTaskTags.tagForEntitlement(entitlementContext));
        }

        taskTags.addAll(tags);

        if (Tasks.current()!=null && BrooklynTaskTags.isTransient(Tasks.current()) 
                && !taskTags.contains(BrooklynTaskTags.NON_TRANSIENT_TASK_TAG) && !taskTags.contains(BrooklynTaskTags.TRANSIENT_TASK_TAG)) {
            // tag as transient if submitter is transient, unless explicitly tagged as non-transient
            taskTags.add(BrooklynTaskTags.TRANSIENT_TASK_TAG);
        }
        
        final Object startCallback = properties.get("newTaskStartCallback");
        properties.put("newTaskStartCallback", new Function<Task<?>,Void>() {
            @Override
            public Void apply(Task<?> it) {
                registerPerThreadExecutionContext();
                if (startCallback!=null) BasicExecutionManager.invokeCallback(startCallback, it);
                return null;
            }});
        
        final Object endCallback = properties.get("newTaskEndCallback");
        properties.put("newTaskEndCallback", new Function<Task<?>,Void>() {
            @Override
            public Void apply(Task<?> it) {
                try {
                    if (endCallback!=null) BasicExecutionManager.invokeCallback(endCallback, it);
                } finally {
                    clearPerThreadExecutionContext();
                }
                return null;
            }});
        
        if (task instanceof Task) {
            return executionManager.submit(properties, (Task)task);
        } else if (task instanceof Callable) {
            return executionManager.submit(properties, (Callable)task);
        } else if (task instanceof Runnable) {
            return (Task<T>) executionManager.submit(properties, (Runnable)task);
        } else {
            throw new IllegalArgumentException("Unhandled task type: task="+task+"; type="+(task!=null ? task.getClass() : "null"));
        }
    }

    private void registerPerThreadExecutionContext() { perThreadExecutionContext.set(this); }

    private void clearPerThreadExecutionContext() { perThreadExecutionContext.remove(); }

    private void checkUserSuppliedContext(Object task, Collection<Object> taskTags) {
        Entity taskContext = BrooklynTaskTags.getWrappedEntityOfType(taskTags, BrooklynTaskTags.CONTEXT_ENTITY);
        Entity defaultContext = BrooklynTaskTags.getWrappedEntityOfType(tags, BrooklynTaskTags.CONTEXT_ENTITY);
        if (taskContext != null) {
            if (log.isWarnEnabled()) {
                String msg = "Deprecated since 0.10.0. Task " + task + " is submitted for execution but has context " +
                        "entity (" + taskContext + ") tag set by the caller. ";
                if (taskContext != defaultContext) {
                    msg += "The context entity of the execution context (" + this + ") the task is submitted on is " +
                            defaultContext + " which is different. This will cause any of them to be used at random at " +
                            "runtime. ";
                    if (task instanceof BasicTask) {
                        msg += "Fixing the context entity to the latter. ";
                    }
                }
                msg += "Setting the context entity by the caller is not allowed. See the documentation on " +
                        "BrooklynTaskTags.tagForContextEntity(Entity) method for more details. Future Apache Brooklyn " +
                        "releases will throw an exception instead of logging a warning.";

                /**
                 * @deprecated since 0.10.0
                 */
                // Should we rate limit?
                log.warn(msg);
            }

            WrappedEntity contextTag = BrooklynTaskTags.tagForContextEntity(taskContext);
            while(taskTags.remove(contextTag)) {};
            if (task instanceof BasicTask) {
                Set<?> mutableTags = BasicTask.class.cast(task).getMutableTags();
                mutableTags.remove(contextTag);
            }
        }
    }

    @Override
    public boolean isShutdown() {
        return getExecutionManager().isShutdown();
    }

    @Override
    public String toString() {
        return super.toString()+"("+tags+")";
    }
}
