/**
  * Licensed to the Apache Software Foundation (ASF) under one or more
  * contributor license agreements.  See the NOTICE file distributed with
  * this work for additional information regarding copyright ownership.
  * The ASF licenses this file to You under the Apache License, Version 2.0
  * (the "License"); you may not use this file except in compliance with
  * the License.  You may obtain a copy of the License at
  *
  *    http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package kafka.log

import java.io.File

import kafka.server.LogOffsetMetadata
import kafka.utils.TestUtils
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors._
import org.apache.kafka.common.internals.Topic
import org.apache.kafka.common.record.{ControlRecordType, EndTransactionMarker, RecordBatch}
import org.apache.kafka.common.utils.{MockTime, Utils}
import org.junit.Assert._
import org.junit.{After, Before, Test}
import org.scalatest.junit.JUnitSuite

class ProducerStateManagerTest extends JUnitSuite {
  var logDir: File = null
  var stateManager: ProducerStateManager = null
  val partition = new TopicPartition("test", 0)
  val producerId = 1L
  val maxPidExpirationMs = 60 * 1000
  val time = new MockTime

  @Before
  def setUp(): Unit = {
    logDir = TestUtils.tempDir()
    stateManager = new ProducerStateManager(partition, logDir, maxPidExpirationMs)
  }

  @After
  def tearDown(): Unit = {
    Utils.delete(logDir)
  }

  @Test
  def testBasicIdMapping(): Unit = {
    val epoch = 0.toShort

    // First entry for id 0 added
    append(stateManager, producerId, epoch, 0, 0L, 0L)

    // Second entry for id 0 added
    append(stateManager, producerId, epoch, 1, 0L, 1L)

    // Duplicate sequence number (matches previous sequence number)
    assertThrows[DuplicateSequenceNumberException] {
      append(stateManager, producerId, epoch, 1, 0L, 1L)
    }

    // Invalid sequence number (greater than next expected sequence number)
    assertThrows[OutOfOrderSequenceException] {
      append(stateManager, producerId, epoch, 5, 0L, 2L)
    }

    // Change epoch
    append(stateManager, producerId, (epoch + 1).toShort, 0, 0L, 3L)

    // Incorrect epoch
    assertThrows[ProducerFencedException] {
      append(stateManager, producerId, epoch, 0, 0L, 4L)
    }
  }

  @Test
  def testNoValidationOnFirstEntryWhenLoadingLog(): Unit = {
    val epoch = 5.toShort
    val sequence = 16
    val offset = 735L
    append(stateManager, producerId, epoch, sequence, offset, isLoadingFromLog = true)

    val maybeLastEntry = stateManager.lastEntry(producerId)
    assertTrue(maybeLastEntry.isDefined)

    val lastEntry = maybeLastEntry.get
    assertEquals(epoch, lastEntry.producerEpoch)
    assertEquals(sequence, lastEntry.firstSeq)
    assertEquals(sequence, lastEntry.lastSeq)
    assertEquals(offset, lastEntry.lastOffset)
    assertEquals(offset, lastEntry.firstOffset)
  }

  @Test
  def testControlRecordBumpsEpoch(): Unit = {
    val epoch = 0.toShort
    append(stateManager, producerId, epoch, 0, 0L)

    val bumpedEpoch = 1.toShort
    val (completedTxn, lastStableOffset) = appendEndTxnMarker(stateManager, producerId, bumpedEpoch, ControlRecordType.ABORT, 1L)
    assertEquals(1L, completedTxn.firstOffset)
    assertEquals(1L, completedTxn.lastOffset)
    assertEquals(2L, lastStableOffset)
    assertTrue(completedTxn.isAborted)
    assertEquals(producerId, completedTxn.producerId)

    val maybeLastEntry = stateManager.lastEntry(producerId)
    assertTrue(maybeLastEntry.isDefined)

    val lastEntry = maybeLastEntry.get
    assertEquals(bumpedEpoch, lastEntry.producerEpoch)
    assertEquals(None, lastEntry.currentTxnFirstOffset)
    assertEquals(RecordBatch.NO_SEQUENCE, lastEntry.firstSeq)
    assertEquals(RecordBatch.NO_SEQUENCE, lastEntry.lastSeq)

    // should be able to append with the new epoch if we start at sequence 0
    append(stateManager, producerId, bumpedEpoch, 0, 2L)
    assertEquals(Some(0), stateManager.lastEntry(producerId).map(_.firstSeq))
  }

  @Test
  def testTxnFirstOffsetMetadataCached(): Unit = {
    val producerEpoch = 0.toShort
    val offset = 992342L
    val seq = 0
    val producerAppendInfo = new ProducerAppendInfo(producerId, ProducerIdEntry.Empty, validateSequenceNumbers = true,
      loadingFromLog = false)
    producerAppendInfo.append(producerEpoch, seq, seq, time.milliseconds(), offset, isTransactional = true)

    val logOffsetMetadata = new LogOffsetMetadata(messageOffset = offset, segmentBaseOffset = 990000L,
      relativePositionInSegment = 234224)
    producerAppendInfo.maybeCacheTxnFirstOffsetMetadata(logOffsetMetadata)
    stateManager.update(producerAppendInfo)

    assertEquals(Some(logOffsetMetadata), stateManager.firstUnstableOffset)
  }

  @Test
  def testNonMatchingTxnFirstOffsetMetadataNotCached(): Unit = {
    val producerEpoch = 0.toShort
    val offset = 992342L
    val seq = 0
    val producerAppendInfo = new ProducerAppendInfo(producerId, ProducerIdEntry.Empty, validateSequenceNumbers = true,
      loadingFromLog = false)
    producerAppendInfo.append(producerEpoch, seq, seq, time.milliseconds(), offset, isTransactional = true)

    // use some other offset to simulate a follower append where the log offset metadata won't typically
    // match any of the transaction first offsets
    val logOffsetMetadata = new LogOffsetMetadata(messageOffset = offset - 23429, segmentBaseOffset = 990000L,
      relativePositionInSegment = 234224)
    producerAppendInfo.maybeCacheTxnFirstOffsetMetadata(logOffsetMetadata)
    stateManager.update(producerAppendInfo)

    assertEquals(Some(LogOffsetMetadata(offset)), stateManager.firstUnstableOffset)
  }

  @Test
  def updateProducerTransactionState(): Unit = {
    val producerEpoch = 0.toShort
    val coordinatorEpoch = 15
    val offset = 9L
    append(stateManager, producerId, producerEpoch, 0, offset)

    val appendInfo = stateManager.prepareUpdate(producerId, loadingFromLog = false)
    appendInfo.append(producerEpoch, 1, 5, time.milliseconds(), 20L, isTransactional = true)
    var lastEntry = appendInfo.lastEntry
    assertEquals(producerEpoch, lastEntry.producerEpoch)
    assertEquals(1, lastEntry.firstSeq)
    assertEquals(5, lastEntry.lastSeq)
    assertEquals(16L, lastEntry.firstOffset)
    assertEquals(20L, lastEntry.lastOffset)
    assertEquals(Some(16L), lastEntry.currentTxnFirstOffset)
    assertEquals(List(new TxnMetadata(producerId, 16L)), appendInfo.startedTransactions)

    appendInfo.append(producerEpoch, 6, 10, time.milliseconds(), 30L, isTransactional = true)
    lastEntry = appendInfo.lastEntry
    assertEquals(producerEpoch, lastEntry.producerEpoch)
    assertEquals(6, lastEntry.firstSeq)
    assertEquals(10, lastEntry.lastSeq)
    assertEquals(26L, lastEntry.firstOffset)
    assertEquals(30L, lastEntry.lastOffset)
    assertEquals(Some(16L), lastEntry.currentTxnFirstOffset)
    assertEquals(List(new TxnMetadata(producerId, 16L)), appendInfo.startedTransactions)

    val endTxnMarker = new EndTransactionMarker(ControlRecordType.COMMIT, coordinatorEpoch)
    val completedTxn = appendInfo.appendEndTxnMarker(endTxnMarker, producerEpoch, 40L, time.milliseconds())
    assertEquals(producerId, completedTxn.producerId)
    assertEquals(16L, completedTxn.firstOffset)
    assertEquals(40L, completedTxn.lastOffset)
    assertFalse(completedTxn.isAborted)

    lastEntry = appendInfo.lastEntry
    assertEquals(producerEpoch, lastEntry.producerEpoch)
    assertEquals(10, lastEntry.firstSeq)
    assertEquals(10, lastEntry.lastSeq)
    assertEquals(40L, lastEntry.firstOffset)
    assertEquals(40L, lastEntry.lastOffset)
    assertEquals(coordinatorEpoch, lastEntry.coordinatorEpoch)
    assertEquals(None, lastEntry.currentTxnFirstOffset)
    assertEquals(List(new TxnMetadata(producerId, 16L)), appendInfo.startedTransactions)
  }

  @Test(expected = classOf[OutOfOrderSequenceException])
  def testOutOfSequenceAfterControlRecordEpochBump(): Unit = {
    val epoch = 0.toShort
    append(stateManager, producerId, epoch, 0, 0L)
    append(stateManager, producerId, epoch, 1, 1L)

    val bumpedEpoch = 1.toShort
    appendEndTxnMarker(stateManager, producerId, bumpedEpoch, ControlRecordType.ABORT, 1L)

    // next append is invalid since we expect the sequence to be reset
    append(stateManager, producerId, bumpedEpoch, 2, 2L)
  }

  @Test(expected = classOf[InvalidTxnStateException])
  def testNonTransactionalAppendWithOngoingTransaction(): Unit = {
    val epoch = 0.toShort
    append(stateManager, producerId, epoch, 0, 0L, isTransactional = true)
    append(stateManager, producerId, epoch, 1, 1L, isTransactional = false)
  }

  @Test
  def testTruncateAndReloadRemovesOutOfRangeSnapshots(): Unit = {
    val epoch = 0.toShort
    append(stateManager, producerId, epoch, 0, 0L)
    stateManager.takeSnapshot()
    append(stateManager, producerId, epoch, 1, 1L)
    stateManager.takeSnapshot()
    append(stateManager, producerId, epoch, 2, 2L)
    stateManager.takeSnapshot()
    append(stateManager, producerId, epoch, 3, 3L)
    stateManager.takeSnapshot()
    append(stateManager, producerId, epoch, 4, 4L)
    stateManager.takeSnapshot()

    stateManager.truncateAndReload(1L, 3L, time.milliseconds())

    assertEquals(Some(2L), stateManager.oldestSnapshotOffset)
    assertEquals(Some(3L), stateManager.latestSnapshotOffset)
  }

  @Test
  def testTakeSnapshot(): Unit = {
    val epoch = 0.toShort
    append(stateManager, producerId, epoch, 0, 0L, 0L)
    append(stateManager, producerId, epoch, 1, 1L, 1L)

    // Take snapshot
    stateManager.takeSnapshot()

    // Check that file exists and it is not empty
    assertEquals("Directory doesn't contain a single file as expected", 1, logDir.list().length)
    assertTrue("Snapshot file is empty", logDir.list().head.length > 0)
  }

  @Test
  def testRecoverFromSnapshot(): Unit = {
    val epoch = 0.toShort
    append(stateManager, producerId, epoch, 0, 0L)
    append(stateManager, producerId, epoch, 1, 1L)

    stateManager.takeSnapshot()
    val recoveredMapping = new ProducerStateManager(partition, logDir, maxPidExpirationMs)
    recoveredMapping.truncateAndReload(0L, 3L, time.milliseconds)

    // entry added after recovery
    append(recoveredMapping, producerId, epoch, 2, 2L)
  }

  @Test(expected = classOf[OutOfOrderSequenceException])
  def testRemoveExpiredPidsOnReload(): Unit = {
    val epoch = 0.toShort
    append(stateManager, producerId, epoch, 0, 0L, 0)
    append(stateManager, producerId, epoch, 1, 1L, 1)

    stateManager.takeSnapshot()
    val recoveredMapping = new ProducerStateManager(partition, logDir, maxPidExpirationMs)
    recoveredMapping.truncateAndReload(0L, 1L, 70000)

    // entry added after recovery. The pid should be expired now, and would not exist in the pid mapping. Hence
    // we should get an out of order sequence exception.
    append(recoveredMapping, producerId, epoch, 2, 2L, 70001)
  }

  @Test
  def testDeleteSnapshotsBefore(): Unit = {
    val epoch = 0.toShort
    append(stateManager, producerId, epoch, 0, 0L)
    append(stateManager, producerId, epoch, 1, 1L)
    stateManager.takeSnapshot()
    assertEquals(1, logDir.listFiles().length)
    assertEquals(Set(2), currentSnapshotOffsets)

    append(stateManager, producerId, epoch, 2, 2L)
    stateManager.takeSnapshot()
    assertEquals(2, logDir.listFiles().length)
    assertEquals(Set(2, 3), currentSnapshotOffsets)

    stateManager.deleteSnapshotsBefore(3L)
    assertEquals(1, logDir.listFiles().length)
    assertEquals(Set(3), currentSnapshotOffsets)

    stateManager.deleteSnapshotsBefore(4L)
    assertEquals(0, logDir.listFiles().length)
    assertEquals(Set(), currentSnapshotOffsets)
  }

  @Test
  def testTruncate(): Unit = {
    val epoch = 0.toShort

    append(stateManager, producerId, epoch, 0, 0L)
    append(stateManager, producerId, epoch, 1, 1L)
    stateManager.takeSnapshot()
    assertEquals(1, logDir.listFiles().length)
    assertEquals(Set(2), currentSnapshotOffsets)

    append(stateManager, producerId, epoch, 2, 2L)
    stateManager.takeSnapshot()
    assertEquals(2, logDir.listFiles().length)
    assertEquals(Set(2, 3), currentSnapshotOffsets)

    stateManager.truncate()

    assertEquals(0, logDir.listFiles().length)
    assertEquals(Set(), currentSnapshotOffsets)

    append(stateManager, producerId, epoch, 0, 0L)
    stateManager.takeSnapshot()
    assertEquals(1, logDir.listFiles().length)
    assertEquals(Set(1), currentSnapshotOffsets)
  }

  @Test
  def testFirstUnstableOffsetAfterTruncation(): Unit = {
    val epoch = 0.toShort
    val sequence = 0

    append(stateManager, producerId, epoch, sequence, offset = 99, isTransactional = true)
    assertEquals(Some(99), stateManager.firstUnstableOffset.map(_.messageOffset))
    stateManager.takeSnapshot()

    appendEndTxnMarker(stateManager, producerId, epoch, ControlRecordType.COMMIT, offset = 105)
    stateManager.onHighWatermarkUpdated(106)
    assertEquals(None, stateManager.firstUnstableOffset.map(_.messageOffset))
    stateManager.takeSnapshot()

    append(stateManager, producerId, epoch, sequence + 1, offset = 106)
    stateManager.truncateAndReload(0L, 106, time.milliseconds())
    assertEquals(None, stateManager.firstUnstableOffset.map(_.messageOffset))

    stateManager.truncateAndReload(0L, 100L, time.milliseconds())
    assertEquals(Some(99), stateManager.firstUnstableOffset.map(_.messageOffset))
  }

  @Test
  def testFirstUnstableOffsetAfterEviction(): Unit = {
    val epoch = 0.toShort
    val sequence = 0
    append(stateManager, producerId, epoch, sequence, offset = 99, isTransactional = true)
    assertEquals(Some(99), stateManager.firstUnstableOffset.map(_.messageOffset))
    append(stateManager, 2L, epoch, 0, offset = 106, isTransactional = true)
    stateManager.evictUnretainedProducers(100)
    assertEquals(Some(106), stateManager.firstUnstableOffset.map(_.messageOffset))
  }

  @Test
  def testEvictUnretainedPids(): Unit = {
    val epoch = 0.toShort

    append(stateManager, producerId, epoch, 0, 0L)
    append(stateManager, producerId, epoch, 1, 1L)
    stateManager.takeSnapshot()

    val anotherPid = 2L
    append(stateManager, anotherPid, epoch, 0, 2L)
    append(stateManager, anotherPid, epoch, 1, 3L)
    stateManager.takeSnapshot()
    assertEquals(Set(2, 4), currentSnapshotOffsets)

    stateManager.evictUnretainedProducers(2)
    assertEquals(Set(4), currentSnapshotOffsets)
    assertEquals(Set(anotherPid), stateManager.activeProducers.keySet)
    assertEquals(None, stateManager.lastEntry(producerId))

    val maybeEntry = stateManager.lastEntry(anotherPid)
    assertTrue(maybeEntry.isDefined)
    assertEquals(3L, maybeEntry.get.lastOffset)

    stateManager.evictUnretainedProducers(3)
    assertEquals(Set(anotherPid), stateManager.activeProducers.keySet)
    assertEquals(Set(4), currentSnapshotOffsets)
    assertEquals(4, stateManager.mapEndOffset)

    stateManager.evictUnretainedProducers(5)
    assertEquals(Set(), stateManager.activeProducers.keySet)
    assertEquals(Set(), currentSnapshotOffsets)
    assertEquals(5, stateManager.mapEndOffset)
  }

  @Test
  def testSkipSnapshotIfOffsetUnchanged(): Unit = {
    val epoch = 0.toShort
    append(stateManager, producerId, epoch, 0, 0L, 0L)

    stateManager.takeSnapshot()
    assertEquals(1, logDir.listFiles().length)
    assertEquals(Set(1), currentSnapshotOffsets)

    // nothing changed so there should be no new snapshot
    stateManager.takeSnapshot()
    assertEquals(1, logDir.listFiles().length)
    assertEquals(Set(1), currentSnapshotOffsets)
  }

  @Test
  def testStartOffset(): Unit = {
    val epoch = 0.toShort
    val pid2 = 2L
    append(stateManager, pid2, epoch, 0, 0L, 1L)
    append(stateManager, producerId, epoch, 0, 1L, 2L)
    append(stateManager, producerId, epoch, 1, 2L, 3L)
    append(stateManager, producerId, epoch, 2, 3L, 4L)
    stateManager.takeSnapshot()

    intercept[OutOfOrderSequenceException] {
      val recoveredMapping = new ProducerStateManager(partition, logDir, maxPidExpirationMs)
      recoveredMapping.truncateAndReload(0L, 1L, time.milliseconds)
      append(recoveredMapping, pid2, epoch, 1, 4L, 5L)
    }
  }

  @Test(expected = classOf[OutOfOrderSequenceException])
  def testPidExpirationTimeout() {
    val epoch = 5.toShort
    val sequence = 37
    append(stateManager, producerId, epoch, sequence, 1L)
    time.sleep(maxPidExpirationMs + 1)
    stateManager.removeExpiredProducers(time.milliseconds)
    append(stateManager, producerId, epoch, sequence + 1, 1L)
  }

  @Test
  def testFirstUnstableOffset() {
    val epoch = 5.toShort
    val sequence = 0

    assertEquals(None, stateManager.firstUndecidedOffset)

    append(stateManager, producerId, epoch, sequence, offset = 99, isTransactional = true)
    assertEquals(Some(99L), stateManager.firstUndecidedOffset)
    assertEquals(Some(99L), stateManager.firstUnstableOffset.map(_.messageOffset))

    val anotherPid = 2L
    append(stateManager, anotherPid, epoch, sequence, offset = 105, isTransactional = true)
    assertEquals(Some(99L), stateManager.firstUndecidedOffset)
    assertEquals(Some(99L), stateManager.firstUnstableOffset.map(_.messageOffset))

    appendEndTxnMarker(stateManager, producerId, epoch, ControlRecordType.COMMIT, offset = 109)
    assertEquals(Some(105L), stateManager.firstUndecidedOffset)
    assertEquals(Some(99L), stateManager.firstUnstableOffset.map(_.messageOffset))

    stateManager.onHighWatermarkUpdated(100L)
    assertEquals(Some(99L), stateManager.firstUnstableOffset.map(_.messageOffset))

    stateManager.onHighWatermarkUpdated(110L)
    assertEquals(Some(105L), stateManager.firstUnstableOffset.map(_.messageOffset))

    appendEndTxnMarker(stateManager, anotherPid, epoch, ControlRecordType.ABORT, offset = 112)
    assertEquals(None, stateManager.firstUndecidedOffset)
    assertEquals(Some(105L), stateManager.firstUnstableOffset.map(_.messageOffset))

    stateManager.onHighWatermarkUpdated(113L)
    assertEquals(None, stateManager.firstUnstableOffset.map(_.messageOffset))
  }

  @Test
  def testProducersWithOngoingTransactionsDontExpire() {
    val epoch = 5.toShort
    val sequence = 0

    append(stateManager, producerId, epoch, sequence, offset = 99, isTransactional = true)
    assertEquals(Some(99L), stateManager.firstUndecidedOffset)

    time.sleep(maxPidExpirationMs + 1)
    stateManager.removeExpiredProducers(time.milliseconds)

    assertTrue(stateManager.lastEntry(producerId).isDefined)
    assertEquals(Some(99L), stateManager.firstUndecidedOffset)

    stateManager.removeExpiredProducers(time.milliseconds)
    assertTrue(stateManager.lastEntry(producerId).isDefined)
  }

  @Test
  def testSequenceNotValidatedForGroupMetadataTopic(): Unit = {
    val partition = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, 0)
    val stateManager = new ProducerStateManager(partition, logDir, maxPidExpirationMs)

    val epoch = 0.toShort
    append(stateManager, producerId, epoch, RecordBatch.NO_SEQUENCE, offset = 99, isTransactional = true)
    append(stateManager, producerId, epoch, RecordBatch.NO_SEQUENCE, offset = 100, isTransactional = true)

  }

  @Test(expected = classOf[ProducerFencedException])
  def testOldEpochForControlRecord(): Unit = {
    val epoch = 5.toShort
    val sequence = 0

    assertEquals(None, stateManager.firstUndecidedOffset)

    append(stateManager, producerId, epoch, sequence, offset = 99, isTransactional = true)
    appendEndTxnMarker(stateManager, producerId, 3.toShort, ControlRecordType.COMMIT, offset=100)
  }

  @Test
  def testCoordinatorFencing(): Unit = {
    val epoch = 5.toShort
    val sequence = 0

    append(stateManager, producerId, epoch, sequence, offset = 99, isTransactional = true)
    appendEndTxnMarker(stateManager, producerId, epoch, ControlRecordType.COMMIT, offset = 100, coordinatorEpoch = 1)

    val lastEntry = stateManager.lastEntry(producerId)
    assertEquals(Some(1), lastEntry.map(_.coordinatorEpoch))

    // writing with the current epoch is allowed
    appendEndTxnMarker(stateManager, producerId, epoch, ControlRecordType.COMMIT, offset = 101, coordinatorEpoch = 1)

    // bumping the epoch is allowed
    appendEndTxnMarker(stateManager, producerId, epoch, ControlRecordType.COMMIT, offset = 102, coordinatorEpoch = 2)

    // old epochs are not allowed
    try {
      appendEndTxnMarker(stateManager, producerId, epoch, ControlRecordType.COMMIT, offset = 103, coordinatorEpoch = 1)
      fail("Expected coordinator to be fenced")
    } catch {
      case e: TransactionCoordinatorFencedException =>
    }
  }

  @Test(expected = classOf[TransactionCoordinatorFencedException])
  def testCoordinatorFencedAfterReload(): Unit = {
    val producerEpoch = 0.toShort
    append(stateManager, producerId, producerEpoch, 0, offset = 99, isTransactional = true)
    appendEndTxnMarker(stateManager, producerId, producerEpoch, ControlRecordType.COMMIT, offset = 100, coordinatorEpoch = 1)
    stateManager.takeSnapshot()

    val recoveredMapping = new ProducerStateManager(partition, logDir, maxPidExpirationMs)
    recoveredMapping.truncateAndReload(0L, 2L, 70000)

    // append from old coordinator should be rejected
    appendEndTxnMarker(stateManager, producerId, producerEpoch, ControlRecordType.COMMIT, offset = 100, coordinatorEpoch = 0)
  }

  private def appendEndTxnMarker(mapping: ProducerStateManager,
                                 producerId: Long,
                                 producerEpoch: Short,
                                 controlType: ControlRecordType,
                                 offset: Long,
                                 coordinatorEpoch: Int = 0,
                                 timestamp: Long = time.milliseconds()): (CompletedTxn, Long) = {
    val producerAppendInfo = stateManager.prepareUpdate(producerId, loadingFromLog = false)
    val endTxnMarker = new EndTransactionMarker(controlType, coordinatorEpoch)
    val completedTxn = producerAppendInfo.appendEndTxnMarker(endTxnMarker, producerEpoch, offset, timestamp)
    mapping.update(producerAppendInfo)
    val lastStableOffset = mapping.completeTxn(completedTxn)
    mapping.updateMapEndOffset(offset + 1)
    (completedTxn, lastStableOffset)
  }

  private def append(stateManager: ProducerStateManager,
                     producerId: Long,
                     producerEpoch: Short,
                     seq: Int,
                     offset: Long,
                     timestamp: Long = time.milliseconds(),
                     isTransactional: Boolean = false,
                     isLoadingFromLog: Boolean = false): Unit = {
    val producerAppendInfo = stateManager.prepareUpdate(producerId, isLoadingFromLog)
    producerAppendInfo.append(producerEpoch, seq, seq, timestamp, offset, isTransactional)
    stateManager.update(producerAppendInfo)
    stateManager.updateMapEndOffset(offset + 1)
  }

  private def currentSnapshotOffsets =
    logDir.listFiles().map(file => Log.offsetFromFilename(file.getName)).toSet

}
