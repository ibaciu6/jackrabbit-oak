/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.mk.core;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.jackrabbit.mk.api.MicroKernel;
import org.apache.jackrabbit.mk.api.MicroKernelException;
import org.apache.jackrabbit.mk.json.JsopBuilder;
import org.apache.jackrabbit.mk.json.JsopTokenizer;
import org.apache.jackrabbit.mk.model.ChildNodeEntry;
import org.apache.jackrabbit.mk.model.Commit;
import org.apache.jackrabbit.mk.model.CommitBuilder;
import org.apache.jackrabbit.mk.model.CommitBuilder.NodeTree;
import org.apache.jackrabbit.mk.model.DiffBuilder;
import org.apache.jackrabbit.mk.model.Id;
import org.apache.jackrabbit.mk.model.NodeState;
import org.apache.jackrabbit.mk.model.PropertyState;
import org.apache.jackrabbit.mk.model.StoredCommit;
import org.apache.jackrabbit.mk.util.CommitGate;
import org.apache.jackrabbit.mk.util.PathUtils;

/**
 *
 */
public class MicroKernelImpl implements MicroKernel {

    protected Repository rep;
    private final CommitGate gate = new CommitGate();

    public MicroKernelImpl(String homeDir) throws MicroKernelException {
        init(homeDir);
    }

    /**
     * Creates a new in-memory kernel instance that doesn't need to be
     * explicitly closed, i.e. standard Java garbage collection will take
     * care of releasing any acquired resources when no longer needed.
     * Useful especially for test cases and other similar scenarios.
     */
    public MicroKernelImpl() {
        this(new Repository());
    }

    /**
     * Alternate constructor, used for testing.
     * 
     * @param rep repository, already initialized
     */
    public MicroKernelImpl(Repository rep) {
        this.rep = rep;
    }

    protected void init(String homeDir) throws MicroKernelException {
        try {
            rep = new Repository(homeDir);
            rep.init();
        } catch (Exception e) {
            throw new MicroKernelException(e);
        }
    }

    public void dispose() {
        gate.commit("end");
        if (rep != null) {
            try {
                rep.shutDown();
            } catch (Exception ignore) {
                // fail silently
            }
            rep = null;
        }
    }

    public String getHeadRevision() throws MicroKernelException {
        if (rep == null) {
            throw new IllegalStateException("this instance has already been disposed");
        }
        return getHeadRevisionId().toString();
    }
    
    /**
     * Same as {@code getHeadRevisionId}, with typed {@code Id} return value instead of string.
     * 
     * @see #getHeadRevision()
     */
    private Id getHeadRevisionId() throws MicroKernelException {
        try {
            return rep.getHeadRevision();
        } catch (Exception e) {
            throw new MicroKernelException(e);
        }
    }

    public String getRevisionHistory(long since, int maxEntries) throws MicroKernelException {
        if (rep == null) {
            throw new IllegalStateException("this instance has already been disposed");
        }
        maxEntries = maxEntries < 0 ? Integer.MAX_VALUE : maxEntries;
        List<StoredCommit> history = new ArrayList<StoredCommit>();
        try {
            StoredCommit commit = rep.getHeadCommit();
            while (commit != null
                    && history.size() < maxEntries
                    && commit.getCommitTS() >= since) {
                history.add(commit);

                Id commitId = commit.getParentId();
                if (commitId == null) {
                    break;
                }
                commit = rep.getCommit(commitId);
            }
        } catch (Exception e) {
            throw new MicroKernelException(e);
        }

        JsopBuilder buff = new JsopBuilder().array();
        for (int i = history.size() - 1; i >= 0; i--) {
            StoredCommit commit = history.get(i);
            buff.object().
                    key("id").value(commit.getId().toString()).
                    key("ts").value(commit.getCommitTS()).
                    key("msg").value(commit.getMsg()).
                    endObject();
        }
        return buff.endArray().toString();
    }

    public String waitForCommit(String oldHeadRevisionId, long maxWaitMillis) throws MicroKernelException, InterruptedException {
        return gate.waitForCommit(oldHeadRevisionId, maxWaitMillis);
    }

    public String getJournal(String fromRevision, String toRevision, String filter) throws MicroKernelException {
        if (rep == null) {
            throw new IllegalStateException("this instance has already been disposed");
        }

        Id fromRevisionId = Id.fromString(fromRevision);
        Id toRevisionId = toRevision == null ? getHeadRevisionId() : Id.fromString(toRevision);

        List<StoredCommit> commits = new ArrayList<StoredCommit>();
        try {
            StoredCommit toCommit = rep.getCommit(toRevisionId);
            if (toCommit.getBranchRootId() != null) {
                throw new MicroKernelException("branch revisions are not supported: " + toRevisionId);
            }

            Commit fromCommit;
            if (toRevisionId.equals(fromRevisionId)) {
                fromCommit = toCommit;
            } else {
                fromCommit = rep.getCommit(fromRevisionId);
                if (fromCommit.getCommitTS() > toCommit.getCommitTS()) {
                    // negative range, return empty journal
                    return "[]";
                }
            }
            if (fromCommit.getBranchRootId() != null) {
                throw new MicroKernelException("branch revisions are not supported: " + fromRevisionId);
            }

            // collect commits, starting with toRevisionId
            // and traversing parent commit links until we've reached
            // fromRevisionId
            StoredCommit commit = toCommit;
            while (commit != null) {
                commits.add(commit);
                if (commit.getId().equals(fromRevisionId)) {
                    break;
                }
                Id commitId = commit.getParentId();
                if (commitId == null) {
                    // shouldn't get here, ignore
                    break;
                }
                commit = rep.getCommit(commitId);
                if (commit.getCommitTS() < fromCommit.getCommitTS()) {
                    // shouldn't get here, ignore
                    break;
                }
            }
        } catch (Exception e) {
            throw new MicroKernelException(e);
        }

        JsopBuilder commitBuff = new JsopBuilder().array();
        // iterate over commits in chronological order,
        // starting with oldest commit
        for (int i = commits.size() - 1; i >= 0; i--) {
            StoredCommit commit = commits.get(i);
            if (commit.getParentId() == null) {
                continue;
            }
            commitBuff.object().
                    key("id").value(commit.getId().toString()).
                    key("ts").value(commit.getCommitTS()).
                    key("msg").value(commit.getMsg()).
                    key("changes").value(commit.getChanges()).endObject();
        }
        return commitBuff.endArray().toString();
    }

    public String diff(String fromRevision, String toRevision, String filter) throws MicroKernelException {
        // TODO extract and evaluate filter criteria (such as e.g. 'path') specified in 'filter' parameter
        String path = "/";

        Id fromRevisionId, toRevisionId;
        if (fromRevision == null || toRevision == null) {
            Id head = getHeadRevisionId();
            fromRevisionId = fromRevision == null ? head : Id.fromString(fromRevision);
            toRevisionId = toRevision == null ? head : Id.fromString(toRevision);
        } else {
            fromRevisionId = Id.fromString(fromRevision);
            toRevisionId = Id.fromString(toRevision);
        }

        if (fromRevisionId.equals(toRevisionId)) {
            return "";
        }

        try {
            NodeState before = rep.getNodeState(fromRevisionId, path);
            NodeState after = rep.getNodeState(toRevisionId, path);

            return new DiffBuilder(before, after, path, rep.getRevisionStore(), filter).build();
        } catch (Exception e) {
            throw new MicroKernelException(e);
        }
    }
    
    public boolean nodeExists(String path, String revisionId) throws MicroKernelException {
        if (rep == null) {
            throw new IllegalStateException("this instance has already been disposed");
        }

        Id revId = revisionId == null ? getHeadRevisionId() : Id.fromString(revisionId);
        try {
            return rep.nodeExists(revId, path);
        } catch (Exception e) {
            throw new MicroKernelException(e);
        }
    }

    public long getChildNodeCount(String path, String revisionId) throws MicroKernelException {
        if (rep == null) {
            throw new IllegalStateException("this instance has already been disposed");
        }

        Id revId = revisionId == null ? getHeadRevisionId() : Id.fromString(revisionId);

        NodeState nodeState;
        try {
            nodeState = rep.getNodeState(revId, path);
        } catch (Exception e) {
            throw new MicroKernelException(e);
        }
        if (nodeState != null) {
            return nodeState.getChildNodeCount();
        } else {
            throw new MicroKernelException("Path " + path + " not found in revision " + revisionId);
        }
    }

    public String getNodes(String path, String revisionId) throws MicroKernelException {
        return getNodes(path, revisionId, 1, 0, -1, null);
    }

    public String getNodes(String path, String revisionId, int depth, long offset, int count, String filter) throws MicroKernelException {
        if (rep == null) {
            throw new IllegalStateException("this instance has already been disposed");
        }

        Id revId = revisionId == null ? getHeadRevisionId() : Id.fromString(revisionId);

        // TODO extract and evaluate filter criteria (such as e.g. ':hash') specified in 'filter' parameter

        try {
            NodeState nodeState = rep.getNodeState(revId, path);
            if (nodeState == null) {
                return null;
            }
            JsopBuilder buf = new JsopBuilder().object();
            toJson(buf, nodeState, depth, (int) offset, count, true);
            return buf.endObject().toString();
        } catch (Exception e) {
            throw new MicroKernelException(e);
        }
    }

    public String commit(String path, String jsonDiff, String revisionId, String message) throws MicroKernelException {
        if (rep == null) {
            throw new IllegalStateException("this instance has already been disposed");
        }
        if (path.length() > 0 && !PathUtils.isAbsolute(path)) {
            throw new IllegalArgumentException("absolute path expected: " + path);
        }

        Id revId = revisionId == null ? getHeadRevisionId() : Id.fromString(revisionId);

        try {
            JsopTokenizer t = new JsopTokenizer(jsonDiff);
            CommitBuilder cb = rep.getCommitBuilder(revId, message);
            while (true) {
                int r = t.read();
                if (r == JsopTokenizer.END) {
                    break;
                }
                int pos; // used for error reporting
                switch (r) {
                    case '+': {
                        pos = t.getLastPos();
                        String subPath = t.readString();
                        t.read(':');
                        if (t.matches('{')) {
                            String nodePath = PathUtils.concat(path, subPath);
                            if (!PathUtils.isAbsolute(nodePath)) {
                                throw new Exception("absolute path expected: " + nodePath + ", pos: " + pos);
                            }
                            String parentPath = PathUtils.getParentPath(nodePath);
                            String nodeName = PathUtils.getName(nodePath);
                            cb.addNode(parentPath, nodeName, parseNode(t));
                        } else {
                            String value;
                            if (t.matches(JsopTokenizer.NULL)) {
                                value = null;
                            } else {
                                value = t.readRawValue().trim();
                            }
                            String targetPath = PathUtils.concat(path, subPath);
                            if (!PathUtils.isAbsolute(targetPath)) {
                                throw new Exception("absolute path expected: " + targetPath + ", pos: " + pos);
                            }
                            String parentPath = PathUtils.getParentPath(targetPath);
                            String propName = PathUtils.getName(targetPath);
                            cb.setProperty(parentPath, propName, value);
                        }
                        break;
                    }
                    case '-': {
                        pos = t.getLastPos();
                        String subPath = t.readString();
                        String targetPath = PathUtils.concat(path, subPath);
                        if (!PathUtils.isAbsolute(targetPath)) {
                            throw new Exception("absolute path expected: " + targetPath + ", pos: " + pos);
                        }
                        cb.removeNode(targetPath);
                        break;
                    }
                    case '^': {
                        pos = t.getLastPos();
                        String subPath = t.readString();
                        t.read(':');
                        String value;
                        if (t.matches(JsopTokenizer.NULL)) {
                            value = null;
                        } else {
                            value = t.readRawValue().trim();
                        }
                        String targetPath = PathUtils.concat(path, subPath);
                        if (!PathUtils.isAbsolute(targetPath)) {
                            throw new Exception("absolute path expected: " + targetPath + ", pos: " + pos);
                        }
                        String parentPath = PathUtils.getParentPath(targetPath);
                        String propName = PathUtils.getName(targetPath);
                        cb.setProperty(parentPath, propName, value);
                        break;
                    }
                    case '>': {
                        pos = t.getLastPos();
                        String subPath = t.readString();
                        String srcPath = PathUtils.concat(path, subPath);
                        if (!PathUtils.isAbsolute(srcPath)) {
                            throw new Exception("absolute path expected: " + srcPath + ", pos: " + pos);
                        }
                        t.read(':');
                        pos = t.getLastPos();
                        String targetPath = t.readString();
                        if (!PathUtils.isAbsolute(targetPath)) {
                            targetPath = PathUtils.concat(path, targetPath);
                            if (!PathUtils.isAbsolute(targetPath)) {
                                throw new Exception("absolute path expected: " + targetPath + ", pos: " + pos);
                            }
                        }
                        cb.moveNode(srcPath, targetPath);
                        break;
                    }
                    case '*': {
                        pos = t.getLastPos();
                        String subPath = t.readString();
                        String srcPath = PathUtils.concat(path, subPath);
                        if (!PathUtils.isAbsolute(srcPath)) {
                            throw new Exception("absolute path expected: " + srcPath + ", pos: " + pos);
                        }
                        t.read(':');
                        pos = t.getLastPos();
                        String targetPath = t.readString();
                        if (!PathUtils.isAbsolute(targetPath)) {
                            targetPath = PathUtils.concat(path, targetPath);
                            if (!PathUtils.isAbsolute(targetPath)) {
                                throw new Exception("absolute path expected: " + targetPath + ", pos: " + pos);
                            }
                        }
                        cb.copyNode(srcPath, targetPath);
                        break;
                    }
                    default:
                        throw new AssertionError("token type: " + t.getTokenType());
                }
            }
            Id newHead = cb.doCommit();
            if (!newHead.equals(revId)) {
                // non-empty commit
                gate.commit(newHead.toString());
            }
            return newHead.toString();
        } catch (Exception e) {
            throw new MicroKernelException(e);
        }
    }

    public String branch(String trunkRevisionId) throws MicroKernelException {
        // create a private branch

        if (rep == null) {
            throw new IllegalStateException("this instance has already been disposed");
        }

        Id revId = trunkRevisionId == null ? getHeadRevisionId() : Id.fromString(trunkRevisionId);

        try {
            CommitBuilder cb = rep.getCommitBuilder(revId, "");
            return cb.doCommit(true).toString();
        } catch (Exception e) {
            throw new MicroKernelException(e);
        }
    }

    public String merge(String branchRevisionId, String message) throws MicroKernelException {
        // merge a private branch with current head revision

        if (rep == null) {
            throw new IllegalStateException("this instance has already been disposed");
        }

        Id revId = Id.fromString(branchRevisionId);

        try {
            return rep.getCommitBuilder(revId, message).doMerge().toString();
        } catch (Exception e) {
            throw new MicroKernelException(e);
        }
    }

    public long getLength(String blobId) throws MicroKernelException {
        if (rep == null) {
            throw new IllegalStateException("this instance has already been disposed");
        }
        try {
            return rep.getBlobStore().getBlobLength(blobId);
        } catch (Exception e) {
            throw new MicroKernelException(e);
        }
    }

    public int read(String blobId, long pos, byte[] buff, int off, int length) throws MicroKernelException {
        if (rep == null) {
            throw new IllegalStateException("this instance has already been disposed");
        }
        try {
            return rep.getBlobStore().readBlob(blobId, pos, buff, off, length);
        } catch (Exception e) {
            throw new MicroKernelException(e);
        }
    }

    public String write(InputStream in) throws MicroKernelException {
        if (rep == null) {
            throw new IllegalStateException("this instance has already been disposed");
        }
        try {
            return rep.getBlobStore().writeBlob(in);
        } catch (Exception e) {
            throw new MicroKernelException(e);
        }
    }

    //-------------------------------------------------------< implementation >

    void toJson(JsopBuilder builder, NodeState node, int depth, int offset, int count, boolean inclVirtualProps) {
        for (PropertyState property : node.getProperties()) {
            builder.key(property.getName()).encodedValue(property.getEncodedValue());
        }
        long childCount = node.getChildNodeCount();
        if (inclVirtualProps) {
            builder.key(":childNodeCount").value(childCount);
        }
        if (childCount > 0 && depth >= 0) {
            for (ChildNodeEntry entry : node.getChildNodeEntries(offset, count)) {
                builder.key(entry.getName()).object();
                if (depth > 0) {
                    toJson(builder, entry.getNode(), depth - 1, 0, -1, inclVirtualProps);
                }
                builder.endObject();
            }
        }
    }
    
    NodeTree parseNode(JsopTokenizer t) throws Exception {
        NodeTree node = new NodeTree();
        if (!t.matches('}')) {
            do {
                String key = t.readString();
                t.read(':');
                if (t.matches('{')) {
                    node.nodes.put(key, parseNode(t));
                } else {
                    node.props.put(key, t.readRawValue().trim());
                }
            } while (t.matches(','));
            t.read('}');
        }
        return node;
    }
}
