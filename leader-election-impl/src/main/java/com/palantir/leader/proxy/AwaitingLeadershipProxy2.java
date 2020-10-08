/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
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
package com.palantir.leader.proxy;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.hash.Hashing;
import com.google.common.reflect.AbstractInvocationHandler;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.palantir.atlasdb.futures.AtlasFutures;
import com.palantir.common.concurrent.PTExecutors;
import com.palantir.common.remoting.ServiceNotAvailableException;
import com.palantir.common.streams.KeyedStream;
import com.palantir.leader.LeaderElectionService;
import com.palantir.leader.LeaderElectionService.LeadershipToken;
import com.palantir.leader.LeaderElectionService.StillLeadingStatus;
import com.palantir.leader.NotCurrentLeaderException;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.exceptions.SafeIllegalStateException;
import com.palantir.paxos.Client;
import com.palantir.tracing.CloseableTracer;
import com.palantir.tracing.Tracers;

public final class AwaitingLeadershipProxy2<T> extends AbstractInvocationHandler {

    private static final Logger log = LoggerFactory.getLogger(AwaitingLeadershipProxy2.class);

    private static final int MAX_NO_QUORUM_RETRIES = 10;
    private static final Duration GAIN_LEADERSHIP_BACKOFF = Duration.ofMillis(500);
    private static final ListeningScheduledExecutorService schedulingExecutor =
            MoreExecutors.listeningDecorator(PTExecutors.newScheduledThreadPoolExecutor(1));
    private static final ListeningExecutorService staticExecutionExecutor = MoreExecutors.listeningDecorator(
            PTExecutors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()));
    private static final AsyncRetrier<StillLeadingStatus> staticStatusRetrier = new AsyncRetrier<>(
            MAX_NO_QUORUM_RETRIES,
            Duration.ofMillis(700),
            schedulingExecutor,
            staticExecutionExecutor,
            status -> status != StillLeadingStatus.NO_QUORUM);

    private static final Map<Integer, ListeningExecutorService> shardedExecutionExecutors = createShardedExecutors();

    private static Map<Integer, ListeningExecutorService> createShardedExecutors() {
        Stream<Integer> processors = IntStream.range(0, Runtime.getRuntime().availableProcessors()).boxed();
        return KeyedStream.of(processors)
                .map(processorId -> MoreExecutors.listeningDecorator(PTExecutors.newSingleThreadExecutor()))
                .collectToMap();
    }

    public static <U> U newProxyInstance(
            Client client,
            Class<U> interfaceClass,
            Supplier<U> delegateSupplier,
            LeaderElectionService leaderElectionService) {
        AwaitingLeadershipProxy2<U> proxy =
                new AwaitingLeadershipProxy2<>(
                        client,
                        delegateSupplier,
                        leaderElectionService,
                        interfaceClass);
        proxy.tryToGainLeadership();

        return (U) Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class<?>[] {interfaceClass, Closeable.class},
                proxy);
    }

    private final Supplier<T> delegateSupplier;
    private final LeaderElectionService leaderElectionService;
    private final ExecutorService executor;
    private final ListeningExecutorService executionExecutor;
    private final AsyncRetrier<StillLeadingStatus> statusRetrier;
    /**
     * This is used as the handoff point between the executor doing the blocking and the invocation calls.  It is set by
     * the executor after the delegateRef is set. It is cleared out by invoke which will close the delegate and spawn a
     * new blocking task.
     */
    private final AtomicReference<LeadershipToken> leadershipTokenRef;
    private final AtomicReference<T> delegateRef;
    private final Class<T> interfaceClass;
    private volatile boolean isClosed;

    private AwaitingLeadershipProxy2(
            Client client,
            Supplier<T> delegateSupplier,
            LeaderElectionService leaderElectionService,
            Class<T> interfaceClass) {
        com.palantir.logsafe.Preconditions.checkNotNull(delegateSupplier,
                "Unable to create an AwaitingLeadershipProxy with no supplier");
        this.delegateSupplier = delegateSupplier;
        this.leaderElectionService = leaderElectionService;
        this.executor = PTExecutors.newSingleThreadExecutor();
        this.leadershipTokenRef = new AtomicReference<>();
        this.delegateRef = new AtomicReference<>();
        this.interfaceClass = interfaceClass;
        this.isClosed = false;

        int processorId = Math.abs(Hashing.murmur3_32().hashString(client.value(), StandardCharsets.UTF_8).asInt())
                % Runtime.getRuntime().availableProcessors();
        this.executionExecutor = Preconditions.checkNotNull(shardedExecutionExecutors.get(processorId));
        this.statusRetrier = new AsyncRetrier<>(
                MAX_NO_QUORUM_RETRIES,
                Duration.ofMillis(700),
                schedulingExecutor,
                executionExecutor,
                status -> status != StillLeadingStatus.NO_QUORUM);

        Arrays.stream(interfaceClass.getMethods()).filter(this::isNotAsync).forEach(method -> {
            log.info("Found non-async method", SafeArg.of("method", method));
        });
    }

    private void tryToGainLeadership() {
        Optional<LeadershipToken> currentToken = leaderElectionService.getCurrentTokenIfLeading();
        if (currentToken.isPresent()) {
            onGainedLeadership(currentToken.get());
        } else {
            tryToGainLeadershipAsync();
        }
    }

    private void tryToGainLeadershipAsync() {
        try {
            executor.execute(this::gainLeadershipWithRetry);
        } catch (RejectedExecutionException e) {
            if (!isClosed) {
                throw new SafeIllegalStateException("failed to submit task but proxy not closed", e);
            }
        }
    }

    private void gainLeadershipWithRetry() {
        while (!gainLeadershipBlocking()) {
            try {
                Thread.sleep(GAIN_LEADERSHIP_BACKOFF.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("gain leadership backoff interrupted");
                if (isClosed) {
                    log.info("gain leadership with retry terminated as the proxy is closed");
                    return;
                }
            }
        }
    }

    private boolean gainLeadershipBlocking() {
        log.debug("Block until gained leadership");
        try {
            LeadershipToken leadershipToken = leaderElectionService.blockOnBecomingLeader();
            onGainedLeadership(leadershipToken);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("attempt to gain leadership interrupted", e);
        } catch (Throwable e) {
            log.error("problem blocking on leadership", e);
        }
        return false;
    }

    private void onGainedLeadership(LeadershipToken leadershipToken) {
        log.debug("Gained leadership, getting delegate to start serving calls");
        // We are now the leader, we should create a delegate so we can service calls
        T delegate = null;
        while (delegate == null) {
            try {
                delegate = delegateSupplier.get();
            } catch (Throwable t) {
                log.error("problem creating delegate", t);
                if (isClosed) {
                    return;
                }
            }
        }

        // Do not modify, hide, or remove this line without considering impact on correctness.
        delegateRef.set(delegate);

        if (isClosed) {
            clearDelegate();
        } else {
            leadershipTokenRef.set(leadershipToken);
            log.info("Gained leadership for {}", SafeArg.of("leadershipToken", leadershipToken));
        }
    }

    private void clearDelegate() {
        Object delegate = delegateRef.getAndSet(null);
        if (delegate instanceof Closeable) {
            try {
                ((Closeable) delegate).close();
            } catch (IOException ex) {
                // we don't want to rethrow here; we're likely on a background thread
                log.warn("problem closing delegate", ex);
            }
        }
    }

    @Override
    protected Object handleInvocation(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getName().equals("close") && args.length == 0) {
            log.debug("Closing leadership proxy");
            isClosed = true;
            executor.shutdownNow();
            clearDelegate();
            return null;
        }

        final LeadershipToken leadershipToken = getLeadershipToken();

        T maybeValidDelegate = delegateRef.get();

        ListenableFuture<StillLeadingStatus> leadingFuture =
                Tracers.wrapListenableFuture("validate-leadership",
                        () -> statusRetrier.execute(
                                () -> Tracers.wrapListenableFuture("validate-leadership-attempt",
                                        () -> leaderElectionService.isStillLeading(leadershipToken))));

        ListenableFuture<T> delegateFuture = Futures.transformAsync(leadingFuture,
                leading -> {
                    // treat a repeated NO_QUORUM as NOT_LEADING; likely we've been cut off from the other nodes
                    // and should assume we're not the leader
                    if (leading == StillLeadingStatus.NOT_LEADING || leading == StillLeadingStatus.NO_QUORUM) {
                        return Futures.submitAsync(
                                () -> {
                                    markAsNotLeading(leadershipToken, null /* cause */);
                                    throw new AssertionError("should not reach here");
                                },
                                executionExecutor);
                    }

                    if (isClosed) {
                        throw new IllegalStateException("already closed proxy for " + interfaceClass.getName());
                    }

                    Preconditions.checkNotNull(maybeValidDelegate, "%s backing is null", interfaceClass.getName());
                    return Futures.immediateFuture(maybeValidDelegate);
                }, MoreExecutors.directExecutor());

        if (isNotAsync(method)) {
            Object returnValue;
            try (CloseableTracer ignored = CloseableTracer.startSpan("execute-on-delegate")) {
                returnValue = method.invoke(maybeValidDelegate, args);
            } catch (InvocationTargetException e) {
                throw handleDelegateThrewException(leadershipToken, e);
            }

            T delegateFromFuture = AtlasFutures.getUnchecked(delegateFuture);
            return checkDelegateAndReturn(maybeValidDelegate, delegateFromFuture, returnValue);
        } else {
            FluentFuture<Object> operationFuture = FluentFuture.from(
                    Tracers.wrapListenableFuture("execute-on-delegate-async",
                            () -> Futures.submitAsync(() -> {
                                try {
                                    return (ListenableFuture<Object>) method.invoke(maybeValidDelegate, args);
                                } catch (IllegalAccessException | InvocationTargetException e) {
                                    return Futures.immediateFailedFuture(e);
                                }
                            }, executionExecutor)))
                    .catchingAsync(InvocationTargetException.class, e -> {
                        throw handleDelegateThrewException(leadershipToken, e);
                    }, MoreExecutors.directExecutor());

            SettableFuture<Object> returnValueFuture = SettableFuture.create();

            Futures.addCallback(operationFuture, new FutureCallback<Object>() {
                @Override
                public void onSuccess(Object returnValue) {
                    returnValueFuture.setFuture(Futures.transform(delegateFuture,
                            delegateFromFuture -> checkDelegateAndReturn(maybeValidDelegate, delegateFromFuture,
                                    returnValue), MoreExecutors.directExecutor()));
                }

                @Override
                public void onFailure(Throwable t) {
                    returnValueFuture.setException(t);
                    // TODO: Maybe cancel?
                    // delegateFuture.cancel(false);
                }
            }, MoreExecutors.directExecutor());

            Futures.addCallback(delegateFuture, new FutureCallback<T>() {
                @Override
                public void onSuccess(T delegateFromFuture) {
                    returnValueFuture.setFuture(Futures.transform(operationFuture,
                            returnValue -> checkDelegateAndReturn(maybeValidDelegate, delegateFromFuture,
                                    returnValue), MoreExecutors.directExecutor()));
                }

                @Override
                public void onFailure(Throwable t) {
                    returnValueFuture.setException(t);
                    // TODO: Maybe cancel?
                    // operationFuture.cancel(false);
                }
            }, MoreExecutors.directExecutor());
            
            return returnValueFuture;
        }
    }

    private boolean isNotAsync(Method method) {
        return !method.getReturnType().equals(ListenableFuture.class);
    }

    private Object checkDelegateAndReturn(T maybeValidDelegate, T delegateFromFuture, Object returnValue) {
        Preconditions.checkArgument(delegateFromFuture == maybeValidDelegate);
        return returnValue;
    }

    private RuntimeException handleDelegateThrewException(
            LeadershipToken leadershipToken, InvocationTargetException exception) throws Exception {
        if (exception.getTargetException() instanceof ServiceNotAvailableException
                || exception.getTargetException() instanceof NotCurrentLeaderException) {
            markAsNotLeading(leadershipToken, exception.getCause());
        }
        // Prevent blocked lock requests from receiving a non-retryable 500 on interrupts
        // in case of a leader election.
        if (exception.getTargetException() instanceof InterruptedException && !isStillCurrentToken(leadershipToken)) {
            throw notCurrentLeaderException("received an interrupt due to leader election.",
                    exception.getTargetException());
        }
        Throwables.propagateIfPossible(exception.getTargetException(), Exception.class);
        throw new RuntimeException(exception.getTargetException());
    }

    @VisibleForTesting
    LeadershipToken getLeadershipToken() {
        LeadershipToken leadershipToken = leadershipTokenRef.get();

        if (leadershipToken == null) {
            NotCurrentLeaderException notCurrentLeaderException = notCurrentLeaderException(
                    "method invoked on a non-leader");

            if (notCurrentLeaderException.getServiceHint().isPresent()) {
                // There's a chance that we can gain leadership while generating this exception.
                // In this case, we should be able to get a leadership token after all
                leadershipToken = leadershipTokenRef.get();
                // If leadershipToken is still null, then someone's the leader, but it isn't us.
            }

            if (leadershipToken == null) {
                throw notCurrentLeaderException;
            }
        }

        return leadershipToken;
    }

    private boolean isStillCurrentToken(LeadershipToken leadershipToken) {
        return leadershipTokenRef.get() == leadershipToken;
    }

    private NotCurrentLeaderException notCurrentLeaderException(String message, @Nullable Throwable cause) {
        return leaderElectionService.getRecentlyPingedLeaderHost()
                .map(hostAndPort -> new NotCurrentLeaderException(message, cause, hostAndPort))
                .orElseGet(() -> new NotCurrentLeaderException(message, cause));
    }

    private NotCurrentLeaderException notCurrentLeaderException(String message) {
        return notCurrentLeaderException(message, null /* cause */);
    }

    private void markAsNotLeading(final LeadershipToken leadershipToken, @Nullable Throwable cause) {
        log.warn("Lost leadership", cause);
        if (leadershipTokenRef.compareAndSet(leadershipToken, null)) {
            // this is fine in the case that this node has been elected leader again (i.e. with a different leadership
            // token). `onGainedLeadership` guarantees that the delegate will be refreshed *before* we get a new
            // leadershipToken. We're closing here instead of relying on the close in `onGainedLeadership` to reclaim
            // resources and does not affect correctness.

            // if we were to move this above or below the CAS, we could race with `onGainedLeadership` and end up
            // clearing `delegateRef`.
            try {
                clearDelegate();
            } catch (Throwable t) {
                // If close fails we should still try to gain leadership
            }
            tryToGainLeadership();
        }
        throw notCurrentLeaderException("method invoked on a non-leader (leadership lost)", cause);
    }

}