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
package com.palantir.atlasdb.timelock.lock;

import java.io.Closeable;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.BadRequestException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.palantir.lock.HeldLocksToken;
import com.palantir.lock.HeldLocksTokens;
import com.palantir.lock.LockRefreshToken;
import com.palantir.lock.SimpleHeldLocksToken;
import com.palantir.logsafe.UnsafeArg;

/**
 * This lock service may be used as a LockService, for the purposes of advisory locking as well as for users to take
 * locks outside of the transaction protocol.
 * <p>
 * However, it does NOT allow transactions to take place, as it throws on attempts to acquire the immutable timestamp.
 * We rely on the previous implementation of SnapshotTransactionManager#getImmutableTimestampInternal (e.g. in 0.48.0),
 * which attempts to acquire the immutable timestamp before transactions begin running.
 */
public class NonTransactionalLockService implements AutoDelegate_AsyncLockService, Closeable {
    private static final Logger log = LoggerFactory.getLogger(NonTransactionalLockService.class);

    private final AsyncLockService delegate;

    public NonTransactionalLockService(AsyncLockService delegate) {
        this.delegate = delegate;
    }

    @Override
    public AsyncLockService delegate() {
        return delegate;
    }

    @Override
    public ListenableFuture<Long> getMinLockedInVersionId(String client) {
        log.warn("Client {} attempted to getMinLockedInVersionId() on a non-transactional lock service!"
                        + " If you are using async timelock, this suggests that one of your AtlasDB clients still"
                        + " expects synchronous lock (i.e. is on a version of AtlasDB prior to 0.49.0). Please check"
                        + " that all AtlasDB clients are using AtlasDB >= 0.49.0.",
                UnsafeArg.of("client", client));
        return Futures.immediateFailedFuture(
                new BadRequestException("getMinLockedInVersionId() not supported on non-transactional lock"
                        + " service. Please consult the server logs for more detail."));
    }

    @Override
    public ListenableFuture<Boolean> unlock(HeldLocksToken token) {
        return delegate().unlockSimple(SimpleHeldLocksToken.fromHeldLocksToken(token));
    }

    @Override
    public ListenableFuture<Boolean> unlock(LockRefreshToken token) {
        return delegate().unlockSimple(SimpleHeldLocksToken.fromLockRefreshToken(token));
    }

    @Override
    public ListenableFuture<Set<HeldLocksToken>> refreshTokens(Iterable<HeldLocksToken> tokens) {
        Set<LockRefreshToken> refreshTokens = ImmutableSet.copyOf(
                Iterables.transform(tokens, HeldLocksTokens.getRefreshTokenFun()));
        return Futures.transform(delegate().refreshLockRefreshTokens(refreshTokens), goodTokens -> {
            Set<HeldLocksToken> ret = Sets.newHashSetWithExpectedSize(refreshTokens.size());
            Map<LockRefreshToken, HeldLocksToken> tokenMap = Maps.uniqueIndex(tokens,
                    HeldLocksTokens.getRefreshTokenFun());
            for (LockRefreshToken goodToken : goodTokens) {
                HeldLocksToken lock = tokenMap.get(goodToken);
                ret.add(goodToken.refreshTokenWithExpriationDate(lock));
            }
            return ret;
        }, MoreExecutors.directExecutor());
    }

    @Override
    public void close() {
        if (delegate() instanceof AutoCloseable) {
            try {
                ((AutoCloseable) delegate()).close();
            } catch (Exception e) {
                throw Throwables.propagate(e);
            }
        }
    }
}
