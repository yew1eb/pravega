/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.segmentstore.storage;

import io.pravega.common.ConfigSetup;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Defines a Factory for Storage Adapters.
 */
public interface StorageFactory {
    /**
     * Creates a new instance of a Storage adapter.
     */
    Storage createStorageAdapter();

    String getName();

    void initialize(ConfigSetup setup, ScheduledExecutorService executor);
}
