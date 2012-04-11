/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.kernel;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.jackrabbit.mk.api.MicroKernel;
import org.apache.jackrabbit.mk.simple.SimpleKernelImpl;
import org.apache.jackrabbit.oak.api.ChildNodeEntry;
import org.apache.jackrabbit.oak.api.NodeState;
import org.apache.jackrabbit.oak.api.PropertyState;
import org.junit.Before;
import org.junit.Test;

public class KernelNodeStateTest {

    private NodeState state;

    @Before
    public void setUp() {
        MicroKernel kernel = new SimpleKernelImpl("mem:KernelNodeStateTest");
        String jsop =
                "+\"test\":{\"a\":1,\"b\":2,\"c\":3,"
                + "\"x\":{},\"y\":{},\"z\":{}}";
        String revision = kernel.commit(
                "/", jsop, kernel.getHeadRevision(), "test data");
        state = new KernelNodeState(kernel, "/test", revision);
    }

    @Test
    public void testGetPropertyCount() {
        assertEquals(3, state.getPropertyCount());
    }

    @Test
    public void testGetProperty() {
        assertEquals("a", state.getProperty("a").getName());
        assertEquals(1, state.getProperty("a").getScalar().getLong());
        assertEquals("b", state.getProperty("b").getName());
        assertEquals(2, state.getProperty("b").getScalar().getLong());
        assertEquals("c", state.getProperty("c").getName());
        assertEquals(3, state.getProperty("c").getScalar().getLong());
        assertNull(state.getProperty("x"));
    }

    @Test
    public void testGetProperties() {
        List<String> names = new ArrayList<String>();
        List<Long> values = new ArrayList<Long>();
        for (PropertyState property : state.getProperties()) {
            names.add(property.getName());
            values.add(property.getScalar().getLong());
        }
        Collections.sort(names);
        Collections.sort(values);
        assertEquals(Arrays.asList("a", "b", "c"), names);
        assertEquals(Arrays.asList(
                Long.valueOf(1), Long.valueOf(2), Long.valueOf(3)), values);
    }

    @Test
    public void testGetChildNodeCount() {
        assertEquals(3, state.getChildNodeCount());
    }

    @Test
    public void testGetChildNode() {
        assertNotNull(state.getChildNode("x"));
        assertNotNull(state.getChildNode("y"));
        assertNotNull(state.getChildNode("z"));
        assertNull(state.getChildNode("a"));
    }

    @Test
    public void testGetChildNodeEntries() {
        List<String> names = new ArrayList<String>();
        for (ChildNodeEntry entry : state.getChildNodeEntries(0, -1)) {
            names.add(entry.getName());
        }
        Collections.sort(names);
        assertEquals(Arrays.asList("x", "y", "z"), names);
    }

    @Test
    public void testGetChildNodeEntriesWithOffset() {
        List<String> names = new ArrayList<String>();
        for (ChildNodeEntry entry : state.getChildNodeEntries(1, -1)) {
            names.add(entry.getName());
        }
        Collections.sort(names);
        assertEquals(Arrays.asList("y", "z"), names);

        // Offset beyond the range
        assertFalse(state.getChildNodeEntries(3, -1).iterator().hasNext());
    }

    @Test
    public void testGetChildNodeEntriesWithCount() {
        List<String> names = new ArrayList<String>();
        for (ChildNodeEntry entry : state.getChildNodeEntries(0, 2)) {
            names.add(entry.getName());
        }
        Collections.sort(names);
        assertEquals(Arrays.asList("x", "y"), names);

        // Zero count
        assertFalse(state.getChildNodeEntries(0, 0).iterator().hasNext());
    }

}
