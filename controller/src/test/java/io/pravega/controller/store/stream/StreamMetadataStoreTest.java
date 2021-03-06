/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.controller.store.stream;

import com.google.common.collect.Lists;
import io.pravega.client.stream.RetentionPolicy;
import io.pravega.client.stream.ScalingPolicy;
import io.pravega.client.stream.StreamConfiguration;
import io.pravega.common.Exceptions;
import io.pravega.common.concurrent.ExecutorServiceHelpers;
import io.pravega.common.concurrent.Futures;
import io.pravega.controller.server.eventProcessor.requesthandlers.TaskExceptions;
import io.pravega.controller.server.retention.BucketChangeListener;
import io.pravega.controller.store.stream.tables.EpochTransitionRecord;
import io.pravega.controller.store.stream.tables.State;
import io.pravega.controller.store.stream.tables.StreamTruncationRecord;
import io.pravega.controller.store.stream.tables.TableHelper;
import io.pravega.controller.store.task.TxnResource;
import io.pravega.controller.stream.api.grpc.v1.Controller.DeleteScopeStatus;
import io.pravega.test.common.AssertExtensions;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.AbstractMap.SimpleEntry;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/**
 * Stream metadata test.
 */
public abstract class StreamMetadataStoreTest {

    //Ensure each test completes within 10 seconds.
    @Rule
    public Timeout globalTimeout = new Timeout(10, TimeUnit.SECONDS);
    protected StreamMetadataStore store;
    protected final ScheduledExecutorService executor = Executors.newScheduledThreadPool(10);
    protected final String scope = "scope";
    protected final String stream1 = "stream1";
    protected final String stream2 = "stream2";
    protected final ScalingPolicy policy1 = ScalingPolicy.fixed(2);
    protected final ScalingPolicy policy2 = ScalingPolicy.fixed(3);
    protected final StreamConfiguration configuration1 = StreamConfiguration.builder().scope(scope).streamName(stream1).scalingPolicy(policy1).build();
    protected final StreamConfiguration configuration2 = StreamConfiguration.builder().scope(scope).streamName(stream2).scalingPolicy(policy2).build();

    @Before
    public abstract void setupTaskStore() throws Exception;

    @After
    public abstract void cleanupTaskStore() throws IOException;

    @After
    public void tearDown() {
        ExecutorServiceHelpers.shutdown(executor);
    }

    @Test
    public void testStreamMetadataStore() throws InterruptedException, ExecutionException {

        // region createStream
        store.createScope(scope).get();

        long start = System.currentTimeMillis();
        store.createStream(scope, stream1, configuration1, start, null, executor).get();
        store.setState(scope, stream1, State.ACTIVE, null, executor).get();
        store.createStream(scope, stream2, configuration2, start, null, executor).get();
        store.setState(scope, stream2, State.ACTIVE, null, executor).get();

        assertEquals(stream1, store.getConfiguration(scope, stream1, null, executor).get().getStreamName());
        // endregion

        // region checkSegments
        List<Segment> segments = store.getActiveSegments(scope, stream1, null, executor).get();
        assertEquals(2, segments.size());

        List<Integer> historicalSegments = store.getActiveSegments(scope, stream1, 10L, null, executor).get();
        assertEquals(2, historicalSegments.size());

        segments = store.getActiveSegments(scope, stream2, null, executor).get();
        assertEquals(3, segments.size());

        historicalSegments = store.getActiveSegments(scope, stream2, 10L, null, executor).get();
        assertEquals(3, historicalSegments.size());

        // endregion

        // region scaleSegments
        long scaleTs = System.currentTimeMillis();
        SimpleEntry<Double, Double> segment1 = new SimpleEntry<>(0.5, 0.75);
        SimpleEntry<Double, Double> segment2 = new SimpleEntry<>(0.75, 1.0);
        List<Integer> sealedSegments = Collections.singletonList(1);
        StartScaleResponse response = store.startScale(scope, stream1, sealedSegments, Arrays.asList(segment1, segment2), scaleTs, false, null, executor).join();
        List<Segment> segmentsCreated = response.getSegmentsCreated();
        store.setState(scope, stream1, State.SCALING, null, executor).join();
        store.scaleCreateNewSegments(scope, stream1, null, executor).join();
        store.scaleNewSegmentsCreated(scope, stream1, null, executor).join();
        store.scaleSegmentsSealed(scope, stream1, sealedSegments.stream().collect(Collectors.toMap(x -> x, x -> 0L)),
                null, executor).join();

        segments = store.getActiveSegments(scope, stream1, null, executor).get();
        assertEquals(3, segments.size());

        historicalSegments = store.getActiveSegments(scope, stream1, System.currentTimeMillis(), null, executor).get();
        assertEquals(3, historicalSegments.size());

        historicalSegments = store.getActiveSegments(scope, stream1, scaleTs - 1, null, executor).get();
        assertEquals(2, historicalSegments.size());

        SimpleEntry<Double, Double> segment3 = new SimpleEntry<>(0.0, 0.5);
        SimpleEntry<Double, Double> segment4 = new SimpleEntry<>(0.5, 0.75);
        SimpleEntry<Double, Double> segment5 = new SimpleEntry<>(0.75, 1.0);
        sealedSegments = Arrays.asList(0, 1, 2);
        long scaleTs2 = System.currentTimeMillis();
        response = store.startScale(scope, stream2, sealedSegments, Arrays.asList(segment3, segment4, segment5), scaleTs2, false, null, executor).get();
        segmentsCreated = response.getSegmentsCreated();
        store.setState(scope, stream2, State.SCALING, null, executor).join();
        store.scaleCreateNewSegments(scope, stream2, null, executor).get();
        store.scaleNewSegmentsCreated(scope, stream2, null, executor).get();
        store.scaleSegmentsSealed(scope, stream2, sealedSegments.stream().collect(Collectors.toMap(x -> x, x -> 0L)),
                null, executor).get();

        segments = store.getActiveSegments(scope, stream1, null, executor).get();
        assertEquals(3, segments.size());

        historicalSegments = store.getActiveSegments(scope, stream2, System.currentTimeMillis(), null, executor).get();
        assertEquals(3, historicalSegments.size());

        // endregion

        // region seal stream

        assertFalse(store.isSealed(scope, stream1, null, executor).get());
        assertNotEquals(0, store.getActiveSegments(scope, stream1, null, executor).get().size());
        Boolean sealOperationStatus = store.setSealed(scope, stream1, null, executor).get();
        assertTrue(sealOperationStatus);
        assertTrue(store.isSealed(scope, stream1, null, executor).get());
        assertEquals(0, store.getActiveSegments(scope, stream1, null, executor).get().size());

        //Sealing an already seal stream should return success.
        Boolean sealOperationStatus1 = store.setSealed(scope, stream1, null, executor).get();
        assertTrue(sealOperationStatus1);
        assertTrue(store.isSealed(scope, stream1, null, executor).get());
        assertEquals(0, store.getActiveSegments(scope, stream1, null, executor).get().size());

        // seal a non-existent stream.
        try {
            store.setSealed(scope, "streamNonExistent", null, executor).join();
        } catch (CompletionException e) {
            assertEquals(StoreException.DataNotFoundException.class, e.getCause().getClass());
        }
        // endregion

        // region delete scope and stream
        assertEquals(DeleteScopeStatus.Status.SCOPE_NOT_EMPTY, store.deleteScope(scope).join().getStatus());

        // Deleting a stream should succeed.
        assertNull(store.deleteStream(scope, stream1, null, executor).join());

        // Delete a deleted stream, should fail with node not found error.
        AssertExtensions.assertThrows("Should throw StoreException",
                store.deleteStream(scope, stream1, null, executor),
                (Throwable t) -> t instanceof StoreException.DataNotFoundException);

        // Delete other stream from the scope.
        assertNull(store.deleteStream(scope, stream2, null, executor).join());

        // Delete scope should succeed now.
        assertEquals(DeleteScopeStatus.Status.SUCCESS, store.deleteScope(scope).join().getStatus());

        // Deleting deleted scope should return Scope_Not_Found.
        assertEquals(DeleteScopeStatus.Status.SCOPE_NOT_FOUND, store.deleteScope(scope).join().getStatus());

        // Deleting non-existing stream should return null.
        AssertExtensions.assertThrows("Should throw StoreException",
                store.deleteStream(scope, "nonExistent", null, executor),
                (Throwable t) -> t instanceof StoreException.DataNotFoundException);
        // endregion
    }

    @Test
    public void listStreamsInScope() throws Exception {
        // list stream in scope
        store.createScope("Scope").get();
        store.createStream("Scope", stream1, configuration1, System.currentTimeMillis(), null, executor).get();
        store.setState("Scope", stream1, State.ACTIVE, null, executor).get();
        store.createStream("Scope", stream2, configuration2, System.currentTimeMillis(), null, executor).get();
        store.setState("Scope", stream2, State.ACTIVE, null, executor).get();
        List<StreamConfiguration> streamInScope = store.listStreamsInScope("Scope").get();
        assertEquals("List streams in scope", 2, streamInScope.size());
        assertEquals("List streams in scope", stream1, streamInScope.get(0).getStreamName());
        assertEquals("List streams in scope", stream2, streamInScope.get(1).getStreamName());

        // List streams in non-existent scope 'Scope1'
        try {
            store.listStreamsInScope("Scope1").join();
        } catch (StoreException se) {
            assertTrue("List streams in non-existent scope Scope1",
                    se instanceof StoreException.DataNotFoundException);
        } catch (CompletionException ce) {
            assertTrue("List streams in non-existent scope Scope1",
                    ce.getCause() instanceof StoreException.DataNotFoundException);
        }
    }

    @Test
    public void listScopes() throws Exception {
        // list scopes test
        List<String> list = store.listScopes().get();
        assertEquals("List Scopes size", 0, list.size());

        store.createScope("Scope1").get();
        store.createScope("Scope2").get();
        store.createScope("Scope3").get();
        store.createScope("Scope4").get();

        list = store.listScopes().get();
        assertEquals("List Scopes size", 4, list.size());

        store.deleteScope("Scope1").get();
        store.deleteScope("Scope2").get();
        list = store.listScopes().get();
        assertEquals("List Scopes size", 2, list.size());
    }

    @Test
    public void getScopeTest() throws Exception {
        final String scope1 = "Scope1";
        final String scope2 = "Scope2";
        String scopeName;

        // get existent scope
        store.createScope(scope1).get();
        scopeName = store.getScopeConfiguration(scope1).get();
        assertEquals("Get existent scope", scope1, scopeName);

        // get non-existent scope
        AssertExtensions.assertThrows("Should throw StoreException",
                store.getScopeConfiguration(scope2),
                (Throwable t) -> t instanceof StoreException.DataNotFoundException);
    }

    @Test
    public void txnHostIndexTest() {
        String host1 = "host1";
        String host2 = "host2";

        TxnResource txn1 = new TxnResource(scope, stream1, UUID.randomUUID());
        TxnResource txn2 = new TxnResource(scope, stream1, UUID.randomUUID());

        addTxnToHost(host1, txn1, 0);
        Assert.assertEquals(1, store.listHostsOwningTxn().join().size());
        Optional<TxnResource> txn = store.getRandomTxnFromIndex(host1).join();
        Assert.assertTrue(txn.isPresent());
        Assert.assertEquals(txn1.getTxnId().toString(), txn.get().getTxnId().toString());

        // Adding a txn again should not fail.
        addTxnToHost(host1, txn1, 0);
        addTxnToHost(host1, txn2, 5);
        Assert.assertEquals(1, store.listHostsOwningTxn().join().size());

        // Fetching version of txn not existing in the index should return null.
        Assert.assertNull(store.getTxnVersionFromIndex(host1, new TxnResource(scope, stream1, UUID.randomUUID())).join());

        txn = store.getRandomTxnFromIndex(host1).join();
        Assert.assertTrue(txn.isPresent());
        UUID randomTxnId = txn.get().getTxnId();
        Assert.assertTrue(randomTxnId.equals(txn1.getTxnId()) || randomTxnId.equals(txn2.getTxnId()));
        Assert.assertEquals(scope, txn.get().getScope());
        Assert.assertEquals(stream1, txn.get().getStream());

        // Test remove txn from index.
        store.removeTxnFromIndex(host1, txn1, true).join();
        // Test remove is idempotent operation.
        store.removeTxnFromIndex(host1, txn1, true).join();
        // Test remove last txn from the index.
        store.removeTxnFromIndex(host1, txn2, false).join();
        Assert.assertEquals(1, store.listHostsOwningTxn().join().size());
        // Test remove is idempotent operation.
        store.removeTxnFromIndex(host1, txn2, true).join();
        Assert.assertEquals(0, store.listHostsOwningTxn().join().size());
        // Test removal of txn that was never added.
        store.removeTxnFromIndex(host1, new TxnResource(scope, stream1, UUID.randomUUID()), true).join();

        // Test host removal.
        store.removeHostFromIndex(host1).join();
        Assert.assertEquals(0, store.listHostsOwningTxn().join().size());
        // Test host removal is idempotent.
        store.removeHostFromIndex(host1).join();
        Assert.assertEquals(0, store.listHostsOwningTxn().join().size());
        // Test removal of host that was never added.
        store.removeHostFromIndex(host2).join();
        Assert.assertEquals(0, store.listHostsOwningTxn().join().size());
    }

    private void addTxnToHost(String host, TxnResource txnResource, int version) {
        store.addTxnToIndex(host, txnResource, version).join();
        Assert.assertEquals(version, store.getTxnVersionFromIndex(host, txnResource).join().intValue());
    }

    @Test
    public void scaleTest() throws Exception {
        final String scope = "ScopeScale";
        final String stream = "StreamScale";
        final ScalingPolicy policy = ScalingPolicy.fixed(2);
        final StreamConfiguration configuration = StreamConfiguration.builder().scope(scope).streamName(stream).scalingPolicy(policy).build();

        long start = System.currentTimeMillis();
        store.createScope(scope).get();

        store.createStream(scope, stream, configuration, start, null, executor).get();
        store.setState(scope, stream, State.ACTIVE, null, executor).get();

        // region idempotent

        long scaleTs = System.currentTimeMillis();
        SimpleEntry<Double, Double> segment1 = new SimpleEntry<>(0.5, 0.75);
        SimpleEntry<Double, Double> segment2 = new SimpleEntry<>(0.75, 1.0);
        List<Integer> scale1SealedSegments = Collections.singletonList(1);

        // test run only if started
        AssertExtensions.assertThrows("", () ->
                store.startScale(scope, stream, scale1SealedSegments,
                        Arrays.asList(segment1, segment2), scaleTs, true, null, executor).join(),
                e -> Exceptions.unwrap(e) instanceof TaskExceptions.StartException);

        // 1. start scale
        StartScaleResponse response = store.startScale(scope, stream, scale1SealedSegments,
                Arrays.asList(segment1, segment2), scaleTs, false, null, executor).join();
        final List<Segment> scale1SegmentsCreated = response.getSegmentsCreated();
        final int scale1ActiveEpoch = response.getActiveEpoch();

        // rerun start scale
        response = store.startScale(scope, stream, scale1SealedSegments,
                Arrays.asList(segment1, segment2), scaleTs, false, null, executor).join();
        assertEquals(response.getSegmentsCreated(), scale1SegmentsCreated);

        store.setState(scope, stream, State.SCALING, null, executor).get();
        // 2. scale new segments created
        store.scaleCreateNewSegments(scope, stream, null, executor).join();
        store.scaleNewSegmentsCreated(scope, stream, null, executor).join();

        // rerun start scale and new segments created
        response = store.startScale(scope, stream, scale1SealedSegments,
                Arrays.asList(segment1, segment2), scaleTs, false, null, executor).join();
        assertEquals(response.getSegmentsCreated(), scale1SegmentsCreated);

        store.scaleCreateNewSegments(scope, stream, null, executor).join();
        store.scaleNewSegmentsCreated(scope, stream, null, executor).join();

        // 3. scale segments sealed -- this will complete scale
        store.scaleSegmentsSealed(scope, stream, scale1SealedSegments.stream().collect(Collectors.toMap(x -> x, x -> 0L)),
                null, executor).join();

        // rerun -- illegal state exception
        AssertExtensions.assertThrows("", () ->
                        store.scaleNewSegmentsCreated(scope, stream, null, executor).join(),
                e -> Exceptions.unwrap(e) instanceof StoreException.IllegalStateException);

        // rerun  -- illegal state exception
        AssertExtensions.assertThrows("", () ->
                        store.scaleSegmentsSealed(scope, stream, scale1SealedSegments.stream().collect(Collectors.toMap(x -> x, x -> 0L)),
                                null, executor).join(),
                e -> Exceptions.unwrap(e) instanceof StoreException.IllegalStateException);

        // rerun start scale -- should fail with precondition failure
        AssertExtensions.assertThrows("", () ->
                store.startScale(scope, stream, scale1SealedSegments,
                        Arrays.asList(segment1, segment2), scaleTs, false, null, executor).join(),
                e -> Exceptions.unwrap(e) instanceof ScaleOperationExceptions.ScalePreConditionFailureException);

        // endregion

        // 2 different conflicting scale operations
        // region run concurrent conflicting scale
        SimpleEntry<Double, Double> segment3 = new SimpleEntry<>(0.0, 0.5);
        SimpleEntry<Double, Double> segment4 = new SimpleEntry<>(0.5, 0.75);
        SimpleEntry<Double, Double> segment5 = new SimpleEntry<>(0.75, 1.0);
        List<Integer> scale2SealedSegments = Arrays.asList(0, 2, 3);
        long scaleTs2 = System.currentTimeMillis();
        response = store.startScale(scope, stream, scale2SealedSegments, Arrays.asList(segment3, segment4, segment5), scaleTs2, false, null, executor).get();
        final List<Segment> scale2SegmentsCreated = response.getSegmentsCreated();
        final int scale2ActiveEpoch = response.getActiveEpoch();
        store.setState(scope, stream, State.SCALING, null, executor).get();

        // rerun of scale 1 -- should fail with precondition failure
        AssertExtensions.assertThrows("", () ->
                store.startScale(scope, stream, scale1SealedSegments,
                        Arrays.asList(segment1, segment2), scaleTs, false, null, executor).join(),
                e -> Exceptions.unwrap(e) instanceof ScaleOperationExceptions.ScaleConflictException);

        store.scaleCreateNewSegments(scope, stream, null, executor).get();
        store.scaleNewSegmentsCreated(scope, stream, null, executor).get();

        store.scaleSegmentsSealed(scope, stream, scale1SealedSegments.stream().collect(Collectors.toMap(x -> x, x -> 0L)),
                null, executor).get();

        store.setState(scope, stream, State.ACTIVE, null, executor).get();
        // endregion

        // region concurrent start scale requests
        // run two concurrent startScale operations such that after doing a getEpochTransition, we create a new epoch
        // transition node. We should get ScaleConflict in such a case.
        // mock createEpochTransition
        SimpleEntry<Double, Double> segment6 = new SimpleEntry<>(0.0, 1.0);
        List<Integer> scale3SealedSegments = Arrays.asList(4, 5, 6);
        long scaleTs3 = System.currentTimeMillis();

        @SuppressWarnings("unchecked")
        PersistentStreamBase<Integer> streamObj = (PersistentStreamBase<Integer>) ((AbstractStreamMetadataStore) store).getStream(scope, stream, null);
        PersistentStreamBase<Integer> streamObjSpied = spy(streamObj);

        CompletableFuture<Void> latch = new CompletableFuture<>();
        CompletableFuture<Void> createEpochTransitionCalled = new CompletableFuture<>();

        doAnswer(x -> CompletableFuture.runAsync(() -> {
            // wait until we create epoch transition outside of this method
            createEpochTransitionCalled.complete(null);
            latch.join();
        }).thenCompose(v -> streamObj.createEpochTransitionNode(new byte[0]))).when(streamObjSpied).createEpochTransitionNode(any());

        doAnswer(x -> streamObj.getEpochTransitionNode()).when(streamObjSpied).getEpochTransitionNode();

        ((AbstractStreamMetadataStore) store).setStream(streamObjSpied);

        // the following should be stuck at createEpochTransition
        CompletableFuture<StartScaleResponse> resp = store.startScale(scope, stream, scale3SealedSegments, Arrays.asList(segment6), scaleTs3, false, null, executor);
        createEpochTransitionCalled.join();
        streamObj.createEpochTransitionNode(new byte[0]).join();
        latch.complete(null);

        AssertExtensions.assertThrows("", resp, e -> Exceptions.unwrap(e) instanceof ScaleOperationExceptions.ScaleConflictException);
        // endregion
    }

    @Test
    public void concurrentStartScaleTest() throws Exception {
        final String scope = "ScopeScale";
        final String stream = "StreamScale";
        final ScalingPolicy policy = ScalingPolicy.fixed(2);
        final StreamConfiguration configuration = StreamConfiguration.builder().scope(scope).streamName(stream).scalingPolicy(policy).build();

        long start = System.currentTimeMillis();
        store.createScope(scope).get();

        store.createStream(scope, stream, configuration, start, null, executor).get();
        store.setState(scope, stream, State.ACTIVE, null, executor).get();

        // region concurrent start scale
        // Test scenario where one request starts and completes as the other is waiting on StartScale.createEpochTransition
        SimpleEntry<Double, Double> segment2 = new SimpleEntry<>(0.0, 1.0);
        List<Integer> segmentsToSeal = Arrays.asList(0, 1);
        long scaleTs = System.currentTimeMillis();

        @SuppressWarnings("unchecked")
        PersistentStreamBase<Integer> streamObj = (PersistentStreamBase<Integer>) ((AbstractStreamMetadataStore) store)
                .getStream(scope, stream, null);
        PersistentStreamBase<Integer> streamObjSpied = spy(streamObj);

        CompletableFuture<Void> latch = new CompletableFuture<>();
        CompletableFuture<Void> createEpochTransitionCalled = new CompletableFuture<>();

        doAnswer(x -> streamObj.getEpochTransitionNode()).when(streamObjSpied).getEpochTransitionNode();
        doAnswer(x -> streamObj.deleteEpochTransitionNode()).when(streamObjSpied).deleteEpochTransitionNode();

        doAnswer(x -> CompletableFuture.runAsync(() -> {
            EpochTransitionRecord record = EpochTransitionRecord.parse(x.getArgument(0));

            if (record.getSegmentsToSeal().containsAll(segmentsToSeal)) {
                // wait until we create epoch transition outside of this method
                createEpochTransitionCalled.complete(null);
                latch.join();
            }
        }).thenCompose(v -> streamObj.createEpochTransitionNode(x.getArgument(0))))
                .when(streamObjSpied).createEpochTransitionNode(any());

        ((AbstractStreamMetadataStore) store).setStream(streamObjSpied);

        // the following should be stuck at createEpochTransition
        CompletableFuture<StartScaleResponse> response = store.startScale(scope, stream, segmentsToSeal,
                Arrays.asList(segment2), scaleTs, false, null, executor);
        createEpochTransitionCalled.join();

        // update history and segment table with a new scale as the previous scale waits to create epoch transition record
        SimpleEntry<Double, Double> segment2p = new SimpleEntry<>(0.0, 0.5);
        List<Integer> segmentsToSeal2 = Arrays.asList(0);
        long scaleTs2 = System.currentTimeMillis();

        streamObjSpied.getHistoryIndex()
            .thenCompose(historyIndex -> streamObjSpied.getHistoryTable()
                .thenCompose(historyTable -> streamObjSpied.getSegmentIndex()
                    .thenCompose(segmentIndex -> streamObjSpied.getSegmentTable()
                        .thenCompose(segmentTable -> streamObjSpied.createEpochTransitionNode(
                                TableHelper.computeEpochTransition(historyIndex.getData(), historyTable.getData(),
                                        segmentIndex.getData(), segmentTable.getData(), segmentsToSeal2,
                                        Arrays.asList(segment2p), scaleTs2).toByteArray())))))
                .thenCompose(x -> store.setState(scope, stream, State.SCALING, null, executor))
                .thenCompose(x -> store.scaleCreateNewSegments(scope, stream, null, executor))
                .thenCompose(x -> store.scaleNewSegmentsCreated(scope, stream, null, executor))
                .thenCompose(x -> store.scaleSegmentsSealed(scope, stream,
                        segmentsToSeal2.stream().collect(Collectors.toMap(r -> r, r -> 0L)), null, executor)).join();

        latch.complete(null);

        // first startScale should also complete with epoch transition record that matches first scale request
        assertTrue(Futures.await(response));
        EpochTransitionRecord epochTransitionRecord = EpochTransitionRecord.parse(streamObj.getEpochTransitionNode().join().getData());
        assertEquals(0, epochTransitionRecord.getActiveEpoch());
        assertEquals(1, epochTransitionRecord.getNewEpoch());
        assertTrue(epochTransitionRecord.getSegmentsToSeal().size() == 2 &&
                epochTransitionRecord.getSegmentsToSeal().contains(0) &&
                epochTransitionRecord.getSegmentsToSeal().contains(1));
        // now that start scale succeeded, we should set the state to scaling.
        store.setState(scope, stream, State.SCALING, null, executor).join();
        // now call first step of scaling -- createNewSegments.. this should throw exception
        AssertExtensions.assertThrows("epoch transition was supposed to be invalid",
                store.scaleCreateNewSegments(scope, stream, null, executor),
                e -> Exceptions.unwrap(e) instanceof IllegalArgumentException);
        // verify that state is reset to ACTIVE
        assertEquals(State.ACTIVE, store.getState(scope, stream, true, null, executor).join());
        // verify that epoch transition is cleaned up
        AssertExtensions.assertThrows("epoch transition was supposed to be invalid",
                streamObj.getEpochTransitionNode(),
                e -> Exceptions.unwrap(e) instanceof StoreException.DataNotFoundException);
        // endregion
    }

    @Test
    public void updateTest() throws Exception {
        final String scope = "ScopeUpdate";
        final String stream = "StreamUpdate";
        final ScalingPolicy policy = ScalingPolicy.fixed(2);
        final StreamConfiguration configuration = StreamConfiguration.builder().scope(scope).streamName(stream).scalingPolicy(policy).build();

        long start = System.currentTimeMillis();
        store.createScope(scope).get();

        store.createStream(scope, stream, configuration, start, null, executor).get();
        store.setState(scope, stream, State.ACTIVE, null, executor).get();

        final StreamConfiguration configuration2 = StreamConfiguration.builder().scope(scope).streamName(stream).scalingPolicy(policy).build();

        StreamProperty<StreamConfiguration> configProperty = store.getConfigurationProperty(scope, stream, true, null, executor).join();
        assertFalse(configProperty.isUpdating());
        // run update configuration multiple times
        assertTrue(Futures.await(store.startUpdateConfiguration(scope, stream, configuration2, null, executor)));
        configProperty = store.getConfigurationProperty(scope, stream, true, null, executor).join();

        assertTrue(configProperty.isUpdating());

        final StreamConfiguration configuration3 = StreamConfiguration.builder().scope(scope).streamName(stream).scalingPolicy(policy).build();

        assertFalse(Futures.await(store.startUpdateConfiguration(scope, stream, configuration3, null, executor)));

        assertTrue(Futures.await(store.completeUpdateConfiguration(scope, stream, null, executor)));

        configProperty = store.getConfigurationProperty(scope, stream, true, null, executor).join();
        assertEquals(configuration2, configProperty.getProperty());

        assertTrue(Futures.await(store.startUpdateConfiguration(scope, stream, configuration3, null, executor)));
        assertTrue(Futures.await(store.completeUpdateConfiguration(scope, stream, null, executor)));
    }

    @Test
    public void deleteTest() throws Exception {
        final String scope = "ScopeDelete";
        final String stream = "StreamDelete";
        final ScalingPolicy policy = ScalingPolicy.fixed(2);
        final StreamConfiguration configuration = StreamConfiguration.builder().scope(scope).streamName(stream).scalingPolicy(policy).build();

        long start = System.currentTimeMillis();
        store.createScope(scope).get();

        store.createStream(scope, stream, configuration, start, null, executor).get();
        store.setState(scope, stream, State.ACTIVE, null, executor).get();
        assertTrue(store.checkStreamExists(scope, stream).join());

        store.deleteStream(scope, stream, null, executor).get();
        assertFalse(store.checkStreamExists(scope, stream).join());
        DeleteScopeStatus status = store.deleteScope(scope).join();
        assertEquals(status.getStatus(), DeleteScopeStatus.Status.SUCCESS);
    }

    @Test
    public void scaleWithTxTest() throws Exception {
        final String scope = "ScopeScaleWithTx";
        final String stream = "StreamScaleWithTx";
        final ScalingPolicy policy = ScalingPolicy.fixed(2);
        final StreamConfiguration configuration = StreamConfiguration.builder().scope(scope).streamName(stream).scalingPolicy(policy).build();

        long start = System.currentTimeMillis();
        store.createScope(scope).get();

        store.createStream(scope, stream, configuration, start, null, executor).get();
        store.setState(scope, stream, State.ACTIVE, null, executor).get();

        long scaleTs = System.currentTimeMillis();
        SimpleEntry<Double, Double> segment2 = new SimpleEntry<>(0.5, 0.75);
        SimpleEntry<Double, Double> segment3 = new SimpleEntry<>(0.75, 1.0);
        List<Integer> scale1SealedSegments = Collections.singletonList(1);

        // region Txn created before scale and during scale
        // scale with transaction test
        VersionedTransactionData tx1 = store.createTransaction(scope, stream, UUID.randomUUID(),
                100, 100, 100, null, executor).get();
        assertEquals(0, tx1.getEpoch());
        StartScaleResponse response = store.startScale(scope, stream, scale1SealedSegments,
                Arrays.asList(segment2, segment3), scaleTs, false, null, executor).join();
        final List<Segment> scale1SegmentsCreated = response.getSegmentsCreated();
        final int epoch = response.getActiveEpoch();
        assertEquals(0, epoch);
        assertNotNull(scale1SegmentsCreated);
        store.setState(scope, stream, State.SCALING, null, executor).join();

        // assert that txn is created on old epoch
        VersionedTransactionData tx2 = store.createTransaction(scope, stream, UUID.randomUUID(),
                100, 100, 100, null, executor).get();
        assertEquals(0, tx2.getEpoch());

        store.scaleCreateNewSegments(scope, stream, null, executor).join();
        store.scaleNewSegmentsCreated(scope, stream, null, executor).join();

        VersionedTransactionData tx3 = store.createTransaction(scope, stream, UUID.randomUUID(),
                100, 100, 100, null, executor).get();
        assertEquals(1, tx3.getEpoch());

        DeleteEpochResponse deleteResponse = store.tryDeleteEpochIfScaling(scope, stream, 0, null, executor).get(); // should not delete epoch
        assertEquals(false, deleteResponse.isDeleted());
        assertEquals(null, deleteResponse.getSegmentsCreated());
        assertEquals(null, deleteResponse.getSegmentsSealed());

        store.sealTransaction(scope, stream, tx2.getId(), true, Optional.of(tx2.getVersion()), null, executor).get();
        store.commitTransaction(scope, stream, tx2.getEpoch(), tx2.getId(), null, executor).get(); // should not happen

        deleteResponse = store.tryDeleteEpochIfScaling(scope, stream, 0, null, executor).get(); // should not delete epoch
        assertEquals(false, deleteResponse.isDeleted());

        store.sealTransaction(scope, stream, tx1.getId(), true, Optional.of(tx1.getVersion()), null, executor).get();
        store.commitTransaction(scope, stream, tx1.getEpoch(), tx1.getId(), null, executor).get(); // should not happen
        deleteResponse = store.tryDeleteEpochIfScaling(scope, stream, 0, null, executor).get(); // should not delete epoch
        assertEquals(true, deleteResponse.isDeleted());

        store.sealTransaction(scope, stream, tx3.getId(), true, Optional.of(tx3.getVersion()), null, executor).get();
        store.commitTransaction(scope, stream, tx3.getEpoch(), tx3.getId(), null, executor).get(); // should not happen

        store.scaleSegmentsSealed(scope, stream, scale1SealedSegments.stream().collect(Collectors.toMap(x -> x, x -> 0L)),
                null, executor).join();

        deleteResponse = store.tryDeleteEpochIfScaling(scope, stream, 1, null, executor).get(); // should not delete epoch
        assertEquals(false, deleteResponse.isDeleted());
        // endregion

        // region Txn created and deleted after scale starts
        List<Integer> scale2SealedSegments = Collections.singletonList(0);
        long scaleTs2 = System.currentTimeMillis();
        SimpleEntry<Double, Double> segment4 = new SimpleEntry<>(0.0, 0.25);
        SimpleEntry<Double, Double> segment5 = new SimpleEntry<>(0.25, 0.5);

        StartScaleResponse response2 = store.startScale(scope, stream, scale2SealedSegments,
                Arrays.asList(segment4, segment5), scaleTs2, false, null, executor).join();
        final List<Segment> scale2SegmentsCreated = response2.getSegmentsCreated();
        final int epoch2 = response2.getActiveEpoch();
        assertEquals(1, epoch2);
        assertNotNull(scale2SegmentsCreated);

        VersionedTransactionData txn = store.createTransaction(scope, stream, UUID.randomUUID(),
                100, 100, 100, null, executor).get();
        assertEquals(1, txn.getEpoch());

        store.sealTransaction(scope, stream, txn.getId(), true, Optional.of(txn.getVersion()), null, executor).get();
        store.commitTransaction(scope, stream, txn.getEpoch(), txn.getId(), null, executor).get(); // should not happen
        deleteResponse = store.tryDeleteEpochIfScaling(scope, stream, 1, null, executor).get(); // should not delete epoch
        // verify that epoch is not deleted as new epoch is not yet created
        assertEquals(false, deleteResponse.isDeleted());

        // verify that new txns can be created and are created on old epoch
        VersionedTransactionData txn2 = store.createTransaction(scope, stream, UUID.randomUUID(),
                100, 100, 100, null, executor).get();
        assertEquals(1, txn2.getEpoch());

        store.setState(scope, stream, State.SCALING, null, executor).get();

        store.scaleCreateNewSegments(scope, stream, null, executor).join();
        store.scaleNewSegmentsCreated(scope, stream, null, executor).join();

        store.sealTransaction(scope, stream, txn2.getId(), true, Optional.of(txn2.getVersion()), null, executor).get();
        store.commitTransaction(scope, stream, txn2.getEpoch(), txn2.getId(), null, executor).get(); // should not happen
        deleteResponse = store.tryDeleteEpochIfScaling(scope, stream, 1, null, executor).get(); // should not delete epoch
        // now that new segments are created, we should be able to delete old epoch.
        assertEquals(true, deleteResponse.isDeleted());
    }

    @Test
    public void truncationTest() throws Exception {
        final String scope = "ScopeTruncate";
        final String stream = "ScopeTruncate";
        final ScalingPolicy policy = ScalingPolicy.fixed(2);
        final StreamConfiguration configuration = StreamConfiguration.builder().scope(scope).streamName(stream).scalingPolicy(policy).build();

        long start = System.currentTimeMillis();
        store.createScope(scope).get();

        store.createStream(scope, stream, configuration, start, null, executor).get();
        store.setState(scope, stream, State.ACTIVE, null, executor).get();

        Map<Integer, Long> truncation = new HashMap<>();
        truncation.put(0, 0L);
        truncation.put(1, 0L);
        assertTrue(Futures.await(store.startTruncation(scope, stream, truncation, null, executor)));

        StreamProperty<StreamTruncationRecord> truncationProperty = store.getTruncationProperty(scope, stream, true, null, executor).join();
        assertTrue(truncationProperty.isUpdating());

        Map<Integer, Long> truncation2 = new HashMap<>();
        truncation2.put(0, 0L);
        truncation2.put(1, 0L);

        assertFalse(Futures.await(store.startTruncation(scope, stream, truncation2, null, executor)));
        assertTrue(Futures.await(store.completeTruncation(scope, stream, null, executor)));

        truncationProperty = store.getTruncationProperty(scope, stream, true, null, executor).join();
        assertEquals(truncation, truncationProperty.getProperty().getStreamCut());

        assertTrue(truncationProperty.getProperty().getCutEpochMap().size() == 2);

        Map<Integer, Long> truncation3 = new HashMap<>();
        truncation3.put(0, 0L);
        truncation3.put(1, 0L);

        assertTrue(Futures.await(store.startTruncation(scope, stream, truncation3, null, executor)));
        assertTrue(Futures.await(store.completeUpdateConfiguration(scope, stream, null, executor)));
    }

    @Test
    public void retentionSetTest() throws Exception {
        final String scope = "ScopeRetain";
        final String stream = "StreamRetain";
        final ScalingPolicy policy = ScalingPolicy.fixed(2);
        final RetentionPolicy retentionPolicy = RetentionPolicy.builder()
                .retentionType(RetentionPolicy.RetentionType.TIME)
                .retentionParam(Duration.ofDays(2).toMillis())
                .build();
        final StreamConfiguration configuration = StreamConfiguration.builder().scope(scope).streamName(stream)
                .scalingPolicy(policy).retentionPolicy(retentionPolicy).build();

        long start = System.currentTimeMillis();
        store.createScope(scope).get();

        store.createStream(scope, stream, configuration, start, null, executor).get();
        store.setState(scope, stream, State.ACTIVE, null, executor).get();

        AtomicReference<BucketChangeListener.StreamNotification> notificationRef = new AtomicReference<>();

        store.registerBucketChangeListener(0, notificationRef::set);
        store.unregisterBucketListener(0);

        store.addUpdateStreamForAutoStreamCut(scope, stream, retentionPolicy, null, executor).get();
        List<String> streams = store.getStreamsForBucket(0, executor).get();
        assertTrue(streams.contains(String.format("%s/%s", scope, stream)));

        Map<Integer, Long> map1 = new HashMap<>();
        map1.put(0, 0L);
        map1.put(1, 0L);
        long recordingTime = System.currentTimeMillis();
        StreamCutRecord streamCut1 = new StreamCutRecord(recordingTime, Long.MIN_VALUE, map1);
        store.addStreamCutToRetentionSet(scope, stream, streamCut1, null, executor).get();

        Map<Integer, Long> map2 = new HashMap<>();
        map2.put(0, 10L);
        map2.put(1, 10L);
        StreamCutRecord streamCut2 = new StreamCutRecord(recordingTime + 10, Long.MIN_VALUE, map2);
        store.addStreamCutToRetentionSet(scope, stream, streamCut2, null, executor).get();

        Map<Integer, Long> map3 = new HashMap<>();
        map3.put(0, 20L);
        map3.put(1, 20L);
        StreamCutRecord streamCut3 = new StreamCutRecord(recordingTime + 20, Long.MIN_VALUE, map3);
        store.addStreamCutToRetentionSet(scope, stream, streamCut3, null, executor).get();

        List<StreamCutRecord> list = store.getStreamCutsFromRetentionSet(scope, stream, null, executor).get();
        assertTrue(list.contains(streamCut1));
        assertTrue(list.contains(streamCut2));
        assertTrue(list.contains(streamCut3));

        store.deleteStreamCutBefore(scope, stream, streamCut2, null, executor).get();

        list = store.getStreamCutsFromRetentionSet(scope, stream, null, executor).get();
        assertTrue(!list.contains(streamCut1));
        assertTrue(!list.contains(streamCut2));
        assertTrue(list.contains(streamCut3));

        store.removeStreamFromAutoStreamCut(scope, stream, null, executor).get();
        streams = store.getStreamsForBucket(0, executor).get();
        assertTrue(!streams.contains(String.format("%s/%s", scope, stream)));
    }

    @Test
    public void sizeTest() throws Exception {
        final String scope = "ScopeSize";
        final String stream = "StreamSize";
        final ScalingPolicy policy = ScalingPolicy.fixed(2);
        final RetentionPolicy retentionPolicy = RetentionPolicy.builder().retentionType(RetentionPolicy.RetentionType.SIZE)
                .retentionParam(100L).build();
        final StreamConfiguration configuration = StreamConfiguration.builder().scope(scope).streamName(stream)
                .scalingPolicy(policy).retentionPolicy(retentionPolicy).build();

        long start = System.currentTimeMillis();
        store.createScope(scope).get();

        store.createStream(scope, stream, configuration, start, null, executor).get();
        store.setState(scope, stream, State.ACTIVE, null, executor).get();

        store.addUpdateStreamForAutoStreamCut(scope, stream, retentionPolicy, null, executor).get();
        List<String> streams = store.getStreamsForBucket(0, executor).get();
        assertTrue(streams.contains(String.format("%s/%s", scope, stream)));

        // region Size Computation on stream cuts on epoch 0
        Map<Integer, Long> map1 = new HashMap<>();
        map1.put(0, 10L);
        map1.put(1, 10L);

        Long size = store.getSizeTillStreamCut(scope, stream, map1, null, executor).join();
        assertTrue(size == 20L);

        long recordingTime = System.currentTimeMillis();
        StreamCutRecord streamCut1 = new StreamCutRecord(recordingTime, size, map1);
        store.addStreamCutToRetentionSet(scope, stream, streamCut1, null, executor).get();

        Map<Integer, Long> map2 = new HashMap<>();
        map2.put(0, 20L);
        map2.put(1, 20L);
        size = store.getSizeTillStreamCut(scope, stream, map2, null, executor).join();
        assertTrue(size == 40L);

        StreamCutRecord streamCut2 = new StreamCutRecord(recordingTime + 10, size, map2);
        store.addStreamCutToRetentionSet(scope, stream, streamCut2, null, executor).get();

        Map<Integer, Long> map3 = new HashMap<>();
        map3.put(0, 30L);
        map3.put(1, 30L);

        size = store.getSizeTillStreamCut(scope, stream, map3, null, executor).join();
        assertTrue(size == 60L);
        StreamCutRecord streamCut3 = new StreamCutRecord(recordingTime + 20, 60L, map3);
        store.addStreamCutToRetentionSet(scope, stream, streamCut3, null, executor).get();

        // endregion

        // region Size Computation on multiple epochs

        long scaleTs = System.currentTimeMillis();
        SimpleEntry<Double, Double> segment2 = new SimpleEntry<>(0.0, 0.5);
        SimpleEntry<Double, Double> segment3 = new SimpleEntry<>(0.5, 1.0);
        List<Integer> scale1SealedSegments = Lists.newArrayList(0, 1);

        StartScaleResponse response = store.startScale(scope, stream, scale1SealedSegments,
                Arrays.asList(segment2, segment3), scaleTs, false, null, executor).join();
        final List<Segment> scale1SegmentsCreated = response.getSegmentsCreated();
        store.setState(scope, stream, State.SCALING, null, executor).get();
        store.scaleCreateNewSegments(scope, stream, null, executor).join();
        store.scaleNewSegmentsCreated(scope, stream, null, executor).join();
        store.scaleSegmentsSealed(scope, stream, scale1SealedSegments.stream().collect(Collectors.toMap(x -> x, x -> 40L)),
                null, executor).join();

        // complex stream cut - across two epochs
        Map<Integer, Long> map4 = new HashMap<>();
        map4.put(0, 40L);
        map4.put(3, 10L);
        size = store.getSizeTillStreamCut(scope, stream, map4, null, executor).join();
        assertTrue(size == 90L);
        StreamCutRecord streamCut4 = new StreamCutRecord(recordingTime + 30, size, map4);
        store.addStreamCutToRetentionSet(scope, stream, streamCut4, null, executor).get();

        // simple stream cut on epoch 2
        Map<Integer, Long> map5 = new HashMap<>();
        map5.put(2, 10L);
        map5.put(3, 10L);

        size = store.getSizeTillStreamCut(scope, stream, map5, null, executor).join();
        assertTrue(size == 100L);
        StreamCutRecord streamCut5 = new StreamCutRecord(recordingTime + 30, size, map5);
        store.addStreamCutToRetentionSet(scope, stream, streamCut5, null, executor).get();
        // endregion
    }
}

