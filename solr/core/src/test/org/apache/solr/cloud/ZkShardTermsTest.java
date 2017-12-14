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

package org.apache.solr.cloud;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.apache.calcite.rel.core.Collect;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.embedded.JettySolrRunner;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.core.SolrCore;
import org.apache.solr.util.TimeOut;
import org.apache.zookeeper.KeeperException;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ZkShardTermsTest extends SolrCloudTestCase {

  @BeforeClass
  public static void setupCluster() throws Exception {
    configureCluster(1)
        .addConfig("conf1", TEST_PATH().resolve("configsets").resolve("cloud-minimal").resolve("conf"))
        .configure();
  }

  public void testParticipationOfReplicas() throws IOException, SolrServerException {
    String collection = "collection1";
    try (ZkShardTerms zkShardTerms = new ZkShardTerms(collection, "shard2", cluster.getZkClient())) {
      zkShardTerms.registerTerm("replica1");
      zkShardTerms.registerTerm("replica2");
      zkShardTerms.ensureTermsIsHigher("replica1", Collections.singleton("replica2"));
    }

    // When new collection is created, the old term nodes will be removed
    CollectionAdminRequest.createCollection(collection, 2, 2)
        .setCreateNodeSet(cluster.getJettySolrRunner(0).getNodeName())
        .setMaxShardsPerNode(1000)
        .process(cluster.getSolrClient());
    ZkController zkController = cluster.getJettySolrRunners().get(0).getCoreContainer().getZkController();
    Map<String,Long> terms = zkController.getShardTerms(collection, "shard1").getTerms();
    assertEquals(2, terms.size());
    assertArrayEquals(new Long[]{0L, 0L}, terms.values().toArray(new Long[2]));
    terms = zkController.getShardTerms(collection, "shard2").getTerms();
    assertEquals(2, terms.size());
    assertArrayEquals(new Long[]{0L, 0L}, terms.values().toArray(new Long[2]));
  }

  public void testRegisterTerm() {
    String collection = "registerTerm";
    ZkShardTerms rep1Terms = new ZkShardTerms(collection, "shard1", cluster.getZkClient());
    ZkShardTerms rep2Terms = new ZkShardTerms(collection, "shard1", cluster.getZkClient());

    rep1Terms.registerTerm("rep1");
    rep2Terms.registerTerm("rep2");
    try (ZkShardTerms zkShardTerms = new ZkShardTerms(collection, "shard1", cluster.getZkClient())) {
      assertEquals(0L, zkShardTerms.getTerms().get("rep1").longValue());
      assertEquals(0L, zkShardTerms.getTerms().get("rep2").longValue());
    }
    rep1Terms.ensureTermsIsHigher("rep1", Collections.singleton("rep2"));
    assertEquals(1L, rep1Terms.getTerms().get("rep1").longValue());
    assertEquals(0L, rep1Terms.getTerms().get("rep2").longValue());

    // assert registerTerm does not override current value
    rep1Terms.registerTerm("rep1");
    assertEquals(1L, rep1Terms.getTerms().get("rep1").longValue());

    rep2Terms.setEqualsToMax("rep2");
    assertEquals(1L, rep2Terms.getTerms().get("rep2").longValue());
    rep2Terms.registerTerm("rep2");
    assertEquals(1L, rep2Terms.getTerms().get("rep2").longValue());

    // zkShardTerms must stay updated by watcher
    Map<String, Long> expectedTerms = new HashMap<>();
    expectedTerms.put("rep1", 1L);
    expectedTerms.put("rep2", 1L);

    TimeOut timeOut = new TimeOut(10, TimeUnit.SECONDS);
    while (!timeOut.hasTimedOut()) {
      if (Objects.equals(expectedTerms, rep1Terms.getTerms()) && Objects.equals(expectedTerms, rep2Terms.getTerms())) break;
    }
    if (timeOut.hasTimedOut()) fail("Expected zkShardTerms must stay updated");

    rep1Terms.close();
    rep2Terms.close();
  }

  @Test
  public void testRaceConditionOnUpdates() throws InterruptedException {
    String collection = "raceConditionOnUpdates";
    List<String> replicas = Arrays.asList("rep1", "rep2", "rep3", "rep4");
    for (String replica : replicas) {
      try (ZkShardTerms zkShardTerms = new ZkShardTerms(collection, "shard1", cluster.getZkClient())) {
        zkShardTerms.registerTerm(replica);
      }
    }

    List<String> failedReplicas = new ArrayList<>(replicas);
    Collections.shuffle(failedReplicas);
    while (failedReplicas.size() > 2) {
      failedReplicas.remove(0);
    }
    AtomicBoolean stop = new AtomicBoolean(false);
    Thread[] threads = new Thread[failedReplicas.size()];
    for (int i = 0; i < failedReplicas.size(); i++) {
      String replica = failedReplicas.get(i);
      threads[i] = new Thread(() -> {
        try (ZkShardTerms zkShardTerms = new ZkShardTerms(collection, "shard1", cluster.getZkClient())) {
          while (!stop.get()) {
            try {
              Thread.sleep(random().nextInt(200));
              zkShardTerms.setEqualsToMax(replica);
            } catch (InterruptedException e) {
              e.printStackTrace();
            }
          }
        }
      });
      threads[i].start();
    }

    long maxTerm = 0;
    try (ZkShardTerms shardTerms = new ZkShardTerms(collection, "shard1", cluster.getZkClient())) {
      shardTerms.registerTerm("leader");
      TimeOut timeOut = new TimeOut(10, TimeUnit.SECONDS);
      while (!timeOut.hasTimedOut()) {
        maxTerm++;
        assertEquals(shardTerms.getTerms().get("leader"), Collections.max(shardTerms.getTerms().values()));
        Thread.sleep(100);
      }
      assertTrue(maxTerm >= Collections.max(shardTerms.getTerms().values()).longValue());
    }
    stop.set(true);
    for (Thread thread : threads) {
      thread.join();
    }
  }

  public void testCoreTermWatcher() throws InterruptedException {
    String collection = "coreTermWatcher";
    ZkShardTerms leaderTerms = new ZkShardTerms(collection, "shard1", cluster.getZkClient());
    leaderTerms.registerTerm("leader");
    ZkShardTerms replicaTerms = new ZkShardTerms(collection, "shard1", cluster.getZkClient());
    replicaTerms.registerTerm("replica");
    waitFor(2, () -> replicaTerms.getTerms().size());

    AtomicInteger count = new AtomicInteger(0);
    // this will get called for almost 2 times
    ZkShardTerms.CoreTermWatcher watcher = terms -> count.incrementAndGet() < 2;
    replicaTerms.addListener(watcher);
    leaderTerms.ensureTermsIsHigher("leader", Collections.singleton("replica"));
    waitFor(1, count::get);
    replicaTerms.setEqualsToMax("replica");
    waitFor(2, count::get);
    assertEquals(0, replicaTerms.getNumListeners());

    leaderTerms.close();
    replicaTerms.close();
  }

  private <T> void waitFor(T expected, Supplier<T> supplier) throws InterruptedException {
    TimeOut timeOut = new TimeOut(2, TimeUnit.SECONDS);
    while (!timeOut.hasTimedOut()) {
      if (expected == supplier.get()) return;
      Thread.sleep(100);
    }
    assertEquals(expected, supplier.get());
  }

}
