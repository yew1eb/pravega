/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.segmentstore.server.containers;

import io.pravega.common.util.ImmutableDate;
import io.pravega.segmentstore.contracts.Attributes;
import io.pravega.segmentstore.server.SegmentMetadataComparer;
import io.pravega.test.common.AssertExtensions;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import lombok.val;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * Unit tests for StreamSegmentMetadata class.
 */
public class StreamSegmentMetadataTests {
    private static final String SEGMENT_NAME = "Segment";
    private static final long SEGMENT_ID = 1;
    private static final long PARENT_SEGMENT_ID = 2;
    private static final int CONTAINER_ID = 1234567;
    private static final int ATTRIBUTE_COUNT = 100;
    @Rule
    public Timeout globalTimeout = Timeout.seconds(10);

    /**
     * Tests that Attributes are properly recorded and updated
     */
    @Test
    public void testAttributes() {
        StreamSegmentMetadata metadata = new StreamSegmentMetadata(SEGMENT_NAME, SEGMENT_ID, PARENT_SEGMENT_ID, CONTAINER_ID);

        // Step 1: initial set of attributes.
        Random rnd = new Random(0);
        val expectedAttributes = generateAttributes(rnd);

        metadata.updateAttributes(expectedAttributes);
        SegmentMetadataComparer.assertSameAttributes("Unexpected attributes after initial set.", expectedAttributes, metadata);

        // Step 2: Update half of attributes and add 50% more.
        int count = 0;
        val keyIterator = expectedAttributes.keySet().iterator();
        val attributeUpdates = new HashMap<UUID, Long>();

        // Update
        while (count < ATTRIBUTE_COUNT / 2 && keyIterator.hasNext()) {
            attributeUpdates.put(keyIterator.next(), rnd.nextLong());
            count++;
        }

        // Now add a few more.
        while (attributeUpdates.size() < ATTRIBUTE_COUNT) {
            attributeUpdates.put(UUID.randomUUID(), rnd.nextLong());
        }

        attributeUpdates.forEach(expectedAttributes::put);
        metadata.updateAttributes(attributeUpdates);
        SegmentMetadataComparer.assertSameAttributes("Unexpected attributes after update.", expectedAttributes, metadata);

        // Step 3: Remove all attributes (Note that attributes are not actually removed; they're set to the NULL_ATTRIBUTE_VALUE).fa
        expectedAttributes.entrySet().forEach(e -> e.setValue(Attributes.NULL_ATTRIBUTE_VALUE));
        metadata.updateAttributes(expectedAttributes);
        SegmentMetadataComparer.assertSameAttributes("Unexpected attributes after removal.", expectedAttributes, metadata);
    }

    /**
     * Tests the copyFrom() method.
     */
    @Test
    public void testCopyFrom() {
        // Transaction (has ParentId, and IsMerged==true).
        val txnMetadata = new StreamSegmentMetadata(SEGMENT_NAME, SEGMENT_ID, PARENT_SEGMENT_ID, CONTAINER_ID);
        txnMetadata.markSealed();
        txnMetadata.setLength(3235342);
        txnMetadata.markMerged();
        testCopyFrom(txnMetadata);

        // Non-Transaction (no ParentId, but has StartOffset).
        val normalMetadata = new StreamSegmentMetadata(SEGMENT_NAME, SEGMENT_ID, CONTAINER_ID);
        normalMetadata.markSealed();
        normalMetadata.setLength(3235342);
        normalMetadata.setStartOffset(1200);
        testCopyFrom(normalMetadata);
    }

    private void testCopyFrom(StreamSegmentMetadata baseMetadata) {
        baseMetadata.setStorageLength(1233);
        baseMetadata.updateAttributes(generateAttributes(new Random(0)));
        baseMetadata.setLastModified(new ImmutableDate());
        baseMetadata.markDeleted();
        baseMetadata.markInactive();
        baseMetadata.setLastUsed(1545895);

        // Normal metadata copy.
        StreamSegmentMetadata newMetadata = new StreamSegmentMetadata(baseMetadata.getName(), baseMetadata.getId(),
                baseMetadata.getParentId(), baseMetadata.getContainerId());
        newMetadata.copyFrom(baseMetadata);
        Assert.assertTrue("copyFrom copied the Active flag too.", newMetadata.isActive());
        SegmentMetadataComparer.assertEquals("Metadata copy:", baseMetadata, newMetadata);
        Assert.assertEquals("Metadata copy: getLastUsed differs.",
                baseMetadata.getLastUsed(), newMetadata.getLastUsed());

        // Verify we cannot copy from different StreamSegments.
        AssertExtensions.assertThrows(
                "copyFrom allowed copying from a metadata with a different Segment Name",
                () -> new StreamSegmentMetadata("foo", SEGMENT_ID, PARENT_SEGMENT_ID, CONTAINER_ID).copyFrom(baseMetadata),
                ex -> ex instanceof IllegalArgumentException);

        AssertExtensions.assertThrows(
                "copyFrom allowed copying from a metadata with a different Segment Id",
                () -> new StreamSegmentMetadata(SEGMENT_NAME, -SEGMENT_ID, PARENT_SEGMENT_ID, CONTAINER_ID).copyFrom(baseMetadata),
                ex -> ex instanceof IllegalArgumentException);

        AssertExtensions.assertThrows(
                "copyFrom allowed copying from a metadata with a different Parent Id",
                () -> new StreamSegmentMetadata(SEGMENT_NAME, SEGMENT_ID, -PARENT_SEGMENT_ID, CONTAINER_ID).copyFrom(baseMetadata),
                ex -> ex instanceof IllegalArgumentException);
    }

    private Map<UUID, Long> generateAttributes(Random rnd) {
        val result = new HashMap<UUID, Long>();
        for (int i = 0; i < ATTRIBUTE_COUNT; i++) {
            result.put(UUID.randomUUID(), rnd.nextLong());
        }

        return result;
    }
}
