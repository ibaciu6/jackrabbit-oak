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

package org.apache.jackrabbit.oak.segment.tool;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.apache.jackrabbit.oak.segment.tool.Utils.openReadOnlyFileStore;
import static org.apache.jackrabbit.oak.segment.tool.Utils.parseSegmentInfoTimestamp;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.jackrabbit.oak.segment.RecordId;
import org.apache.jackrabbit.oak.segment.RecordType;
import org.apache.jackrabbit.oak.segment.SegmentId;
import org.apache.jackrabbit.oak.segment.SegmentNodeState;
import org.apache.jackrabbit.oak.segment.file.ReadOnlyFileStore;
import org.apache.jackrabbit.oak.spi.state.NodeState;

public class RecoverJournal {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private File path;

        private PrintStream out = System.out;

        private PrintStream err = System.err;

        private Builder() {
            // Prevent external instantiation.
        }

        public Builder withPath(File path) {
            this.path = checkNotNull(path, "path");
            return this;
        }

        public Builder withOut(PrintStream out) {
            this.out = checkNotNull(out, "out");
            return this;
        }

        public Builder withErr(PrintStream err) {
            this.err = checkNotNull(err, "err");
            return this;
        }

        public RecoverJournal build() {
            checkState(path != null, "path not specified");
            return new RecoverJournal(this);
        }
    }

    private final File path;

    private final PrintStream out;

    private final PrintStream err;

    private RecoverJournal(Builder builder) {
        this.path = builder.path;
        this.out = builder.out;
        this.err = builder.err;
    }

    public int run() {
        List<Entry> entries;

        try (ReadOnlyFileStore store = openReadOnlyFileStore(path)) {
            entries = extractRootEntries(store);
        } catch (Exception e) {
            out.println("Unable to extract root entries, aborting");
            e.printStackTrace(err);
            return 1;
        }

        if (entries.isEmpty()) {
            out.println("No root entries found, aborting");
            return 1;
        }

        entries.sort((left, right) -> Long.compare(right.timestamp, left.timestamp));

        File rootEntriesFile = new File(path, "root-entries.log");

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(rootEntriesFile))) {
            for (Entry entry : entries) {
                writer.write(String.format("%s root %d\n", entry.recordId.toString10(), entry.timestamp));
            }
        } catch (IOException e) {
            err.println("Unable to write the root entries to file");
            e.printStackTrace(err);
            return 1;
        }

        out.println("Root entries extracted successfully");

        return 0;
    }

    private static class Entry {

        long timestamp;

        RecordId recordId;

        Entry(long timestamp, RecordId recordId) {
            this.timestamp = timestamp;
            this.recordId = recordId;
        }
    }

    private List<Entry> extractRootEntries(ReadOnlyFileStore fileStore) {
        List<Entry> entries = new ArrayList<>();

        for (SegmentId segmentId : fileStore.getSegmentIds()) {
            if (segmentId.isBulkSegmentId()) {
                continue;
            }

            Long timestamp = parseSegmentInfoTimestamp(segmentId);
            if (timestamp == null) {
                err.printf("No timestamp found in segment %s\n", segmentId);
                continue;
            }

            segmentId.getSegment().forEachRecord((number, type, offset) -> {
                if (type != RecordType.NODE) {
                    return;
                }
                RecordId recordId = new RecordId(segmentId, number);
                SegmentNodeState nodeState = fileStore.getReader().readNode(recordId);

                if (nodeState.hasChildNode("checkpoints") && nodeState.hasChildNode("root")) {
                    entries.add(new Entry(timestamp, recordId));
                }
            });
        }

        return entries;
    }
}
