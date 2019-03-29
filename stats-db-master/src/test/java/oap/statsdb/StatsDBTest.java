/*
 * The MIT License (MIT)
 *
 * Copyright (c) Open Application Platform Authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package oap.statsdb;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import oap.application.remote.RemoteSerialization;
import oap.storage.mongo.AbstractMongoTest;
import oap.testng.Env;
import oap.util.Cuid;
import org.testng.annotations.Test;

import java.util.List;

import static oap.statsdb.NodeSchema.nc;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Created by igor.petrenko on 08.09.2017.
 */
@Test
public class StatsDBTest extends AbstractMongoTest {
    private static final NodeSchema schema2 = new NodeSchema(
            nc("n1", MockValue::new),
            nc("n2", MockChild::new));
    private static final NodeSchema schema3 = new NodeSchema(
            nc("n1", MockValue::new),
            nc("n2", MockChild::new),
            nc("n3", MockChild::new));

    @Test
    public void testEmptySync() {
        try (var master = new StatsDBMaster(schema3, StatsDBStorage.NULL);
             var node = new StatsDBNode(schema3, getProxy(master), null)) {

            assertThat(node.lastSyncSuccess).isFalse();
            node.sync();
            assertThat(node.lastSyncSuccess).isTrue();
        }
    }

    @Test
    public void children() {
        try (var master = new StatsDBMaster(schema2, StatsDBStorage.NULL)) {
            master.<MockChild>update("k1", "k2", c -> c.ci = 10);
            master.<MockChild>update("k1", "k3", c -> c.ci = 3);
            master.<MockChild>update("k2", "k4", c -> c.ci = 4);
            master.<MockValue>update("k1", c -> c.i2 = 10);


            assertThat(master.children("k1"))
                    .hasSize(2)
                    .contains(new MockChild(10))
                    .contains(new MockChild(3));

            assertThat(master.children("k2"))
                    .hasSize(1)
                    .contains(new MockChild(4));

            assertThat(master.children("unknown")).isEmpty();
            assertThat(master.children("k1", "k2")).isEmpty();
        }
    }

    @Test
    public void mergeChild() {
        try (var master = new StatsDBMaster(schema3, StatsDBStorage.NULL);
             var node = new StatsDBNode(schema3, getProxy(master), null)) {

            node.<MockValue>update("p", (p) -> p.i2 = 1);
            node.<MockChild>update("p", "c1", c -> c.ci = 1);
            node.<MockChild>update("p", "c1", "c2", c -> c.ci = 2);
            node.sync();

            assertThat(master.<MockValue>get("p").sum).isEqualTo(3);

            node.<MockValue>update("p", p -> p.i2 = 1);
            node.<MockChild>update("p", "c1", c -> c.ci = 2);
            node.sync();

            node.<MockChild>update("p", "c1", "c2", c -> c.ci = 2);
            node.sync();

            assertThat(master.<MockValue>get("p").i2).isEqualTo(2);
            assertThat(master.<MockValue>get("p").sum).isEqualTo(7);
            assertThat(master.<MockChild>get("p", "c1").ci).isEqualTo(3);
            assertThat(master.<MockChild>get("p", "c1").sum).isEqualTo(4);
            assertThat(master.<MockChild>get("p", "c1", "c2").ci).isEqualTo(4);
        }
    }

    private RemoteStatsDB getProxy(RemoteStatsDB master) {
        return RemoteSerialization.Proxy(RemoteStatsDB.class, master);
    }


    @Test
    public void persistMaster() {
        try (var masterStorage = new StatsDBStorageMongo(mongoClient, "test");
             StatsDBMaster master = new StatsDBMaster(schema3, masterStorage)) {
            master.<MockChild>update("k1", "k2", "k3", c -> c.ci = 10);
            master.<MockChild>update("k1", "k2", "k33", c -> c.ci = 1);
            master.<MockValue>update("k1", c -> c.i2 = 111);
        }

        try (var masterStorage = new StatsDBStorageMongo(mongoClient, "test");
             StatsDBMaster master = new StatsDBMaster(schema3, masterStorage)) {
            assertThat(master.<MockChild>get("k1", "k2", "k3").ci).isEqualTo(10);

            assertThat(master.<MockValue>get("k1").sum).isEqualTo(11L);
        }
    }

    @Test
    public void persistNode() {
        var master = new MockRemoteStatsDB(schema2);
        master.syncWithException((sync) -> new RuntimeException("sync"));

        try (var node = new StatsDBNode(schema2, getProxy(master), Env.tmpPath("node"))) {
            node.<MockChild>update("k1", "k2", c -> c.ci = 10);
        }

        master.syncWithoutException();
        try (var node = new StatsDBNode(schema2, getProxy(master), Env.tmpPath("node"))) {
            node.sync();

            assertThat(master.syncs).hasSize(1);
            assertThat(master.syncs.get(0).data.containsKey("k1"));
        }
    }

    @Test
    public void sync() {
        try (var master = new StatsDBMaster(schema2, StatsDBStorage.NULL);
             var node = new StatsDBNode(schema2, getProxy(master), null)) {
            node.sync();

            node.<MockChild>update("k1", "k2", c -> c.ci = 10);
            node.<MockChild>update("k1", "k3", c -> c.ci = 1);
            node.<MockValue>update("k1", c -> c.i2 = 20);

            node.sync();
            assertThat(node.<MockValue>get("k1", "k2")).isNull();
            assertThat(master.<MockChild>get("k1", "k2").ci).isEqualTo(10);
            assertThat(master.<MockValue>get("k1").i2).isEqualTo(20);

            node.<MockChild>update("k1", "k2", c -> c.ci = 10);
            node.<MockValue>update("k1", c -> c.i2 = 21);

            node.sync();
            assertThat(node.<MockValue>get("k1", "k2")).isNull();
            assertThat(master.<MockChild>get("k1", "k2").ci).isEqualTo(20);
            assertThat(master.<MockValue>get("k1").i2).isEqualTo(41);
            assertThat(master.<MockValue>get("k1").sum).isEqualTo(21L);
        }
    }

    public void calculatedValuesAfterRestart() {
        sync();

        try (var master = new StatsDBMaster(schema2, StatsDBStorage.NULL);
             var node = new StatsDBNode(schema2, getProxy(master), null)) {
            node.<MockChild>update("k1", "k2", c -> c.ci = 10);
            node.<MockChild>update("k1", "k3", c -> c.ci = 1);
            node.<MockValue>update("k1", c -> c.i2 = 20);
            node.sync();

            assertThat(master.<MockValue>get("k1").sum).isEqualTo(11L);
        }
    }

    @Test
    public void syncFailed() {
        var master = new MockRemoteStatsDB(schema2);

        try (var node = new StatsDBNode(schema2, getProxy(master), Env.tmpPath("node"))) {
            master.syncWithException((sync) -> new RuntimeException("sync"));
            node.<MockChild>update("k1", "k2", c -> c.ci = 10);
            node.sync();
            assertThat(node.<MockValue>get("k1", "k2")).isNull();
        }

        assertThat(master.syncs).isEmpty();

        try (var node = new StatsDBNode(schema2, getProxy(master), Env.tmpPath("node"))) {
            master.syncWithoutException();
            node.sync();

            assertThat(node.<MockValue>get("k1", "k2")).isNull();
        }

        assertThat(master.syncs).hasSize(1);
    }

    @Test
    public void version() {
        Cuid.IncrementalCuid uid = Cuid.incremental(0);
        try (StatsDBMaster master = new StatsDBMaster(schema2, StatsDBStorage.NULL);
             StatsDBNode node = new StatsDBNode(schema2, getProxy(master), null, uid)) {

            uid.reset(0);

            node.<MockValue>update("k1", c -> c.i2 = 20);
            node.sync();
            assertThat(master.<MockValue>get("k1").i2).isEqualTo(20);

            uid.reset(0);
            node.<MockValue>update("k1", c -> c.i2 = 21);
            node.sync();
            assertThat(master.<MockValue>get("k1").i2).isEqualTo(20);
        }
    }

    @ToString
    @EqualsAndHashCode
    public static class MockValue implements Node.Container<MockValue, MockChild> {
        public long l1;
        public int i2;

        @JsonIgnore
        public long sum;

        public MockValue() {
            this(0);
        }

        public MockValue(int i2) {
            this.i2 = i2;
        }

        @Override
        public MockValue aggregate(List<MockChild> children) {
            sum = children.stream().mapToLong(c -> c.sum + c.ci).sum();

            return this;
        }

        @Override
        public MockValue merge(MockValue other) {
            l1 += other.l1;
            i2 += other.i2;

            return this;
        }
    }

    @ToString
    @EqualsAndHashCode
    public static class MockChild implements Node.Container<MockChild, MockChild> {
        public long ci;
        public long sum;

        public MockChild() {
        }

        public MockChild(long ci) {
            this.ci = ci;
        }

        @Override
        public MockChild merge(MockChild other) {
            ci += other.ci;

            return this;
        }

        @Override
        public MockChild aggregate(List<MockChild> children) {
            sum = children.stream().mapToLong(c -> c.ci + c.sum).sum();
            return this;
        }
    }
}
