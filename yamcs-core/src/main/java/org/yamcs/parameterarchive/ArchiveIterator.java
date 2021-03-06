package org.yamcs.parameterarchive;

import static org.yamcs.parameterarchive.ParameterArchive.getIntervalEnd;
import static org.yamcs.parameterarchive.ParameterArchive.getIntervalStart;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.yamcs.parameterarchive.ParameterArchive.Partition;
import org.yamcs.utils.DatabaseCorruptionException;
import org.yamcs.utils.DecodingException;
import org.yamcs.yarch.rocksdb.AscendingRangeIterator;
import org.yamcs.yarch.rocksdb.DbIterator;
import org.yamcs.yarch.rocksdb.DescendingRangeIterator;

/**
 * For a given parameter id and group id, iterates over all segments in the parameter archive (across all partitions)
 * <p>
 * This works like a Rocks iterator (with isValid(), next(), and value()) not like a java one. The advantage is that one
 * can look at the current value multiple times. This property is used when merging the iterators using a priority
 * queue.
 * 
 * <p>
 * The iterator has to be closed if it is not used until the end, otherwise a rocks iterator may be left hanging
 * 
 * <p>
 * Note about the raw values retrieval: the retrieval assumes that if raw values are requested, the parameter has
 * raw values (this can be known from the type associated to the parameter id).
 * <p>
 * Thus, if the raw values are requested and not found in the archive, the engineering values are returned as raw
 * values. This is an optimisation done in case the two are equal.
 * 
 * <p>
 * The iterator also sends data from RealtimeFiller if that is enabled.
 * 
 *
 */
public class ArchiveIterator implements AutoCloseable {
    private final int parameterId, parameterGroupId;
    final byte[] rangeStart;
    final byte[] rangeStop;

    ParameterArchive parchive;

    SegmentEncoderDecoder segmentEncoder = new SegmentEncoderDecoder();
    List<Partition> partitions;

    // iterates over partitions
    Iterator<Partition> topIt;

    // iterates over segments in one partition
    PartitionIterator pit;

    final boolean ascending, retrieveEngValues, retrieveRawValues, retrieveParameterStatus;

    ParameterValueSegment curValue;
    Iterator<ParameterValueSegment> rtIterator;
    final RealtimeArchiveFiller rtfiller;

    public ArchiveIterator(ParameterArchive parchive, int parameterId, int parameterGroupId, ParameterRequest req) {

        this.parameterId = parameterId;
        this.parameterGroupId = parameterGroupId;
        this.parchive = parchive;
        this.ascending = req.isAscending();
        this.retrieveEngValues = req.isRetrieveEngineeringValues();
        this.retrieveRawValues = req.isRetrieveRawValues();
        this.retrieveParameterStatus = req.isRetrieveParameterStatus();

        partitions = parchive.getPartitions(getIntervalStart(req.start), getIntervalEnd(req.stop), req.ascending);
        topIt = partitions.iterator();
        rangeStart = new SegmentKey(parameterId, parameterGroupId, ParameterArchive.getIntervalStart(req.start),
                (byte) 0).encode();
        rangeStop = new SegmentKey(parameterId, parameterGroupId, req.stop, Byte.MAX_VALUE).encode();

        rtfiller = parchive.getRealtimeFiller();

        if (rtfiller != null && req.isAscending()) {
            rtIterator = rtfiller.getSegments(parameterId, parameterGroupId, ascending).iterator();
        }

        next();
    }

    public boolean isValid() {
        return curValue != null;
    }

    public ParameterValueSegment value() {
        return curValue;
    }

    public void next() {
        if (ascending && rtIterator != null) {
            if (rtIterator.hasNext()) {
                curValue = rtIterator.next();
                return;
            } else {
                rtIterator = null;
            }
        }

        pit = getPartitionIterator();
        if (pit != null) {
            curValue = pit.value();
            pit.next();
            return;
        } else {
            curValue = null;
        }

        if (!ascending && rtfiller != null) {
            if (rtIterator == null) {
                rtIterator = rtfiller.getSegments(parameterId, parameterGroupId, ascending).iterator();
            }
            if (rtIterator.hasNext()) {
                curValue = rtIterator.next();
            }
        }
    }

    private PartitionIterator getPartitionIterator() {
        while (pit == null || !pit.isValid()) {
            if (topIt.hasNext()) {
                Partition p = topIt.next();
                close(pit);
                pit = new PartitionIterator(p);
            } else {
                close(pit);
                return null;
            }
        }
        return pit;
    }

    /**
     * Close the underlying rocks iterator if not already closed
     */
    public void close() {
        close(pit);
    }

    private void close(PartitionIterator pit) {
        if (pit != null) {
            pit.close();
        }
    }

    public int getParameterGroupId() {
        return parameterGroupId;
    }

    public int getParameterId() {
        return parameterId;
    }

    // This iterator works like a rocks iterator (unlike the ArchiveIterator which is a java Iterator)
    class PartitionIterator {
        final Partition partition;
        private SegmentKey currentKey;
        SegmentEncoderDecoder segmentEncoder = new SegmentEncoderDecoder();
        private byte[] currentEngValueSegment;
        private byte[] currentRawValueSegment;
        private byte[] currentStatusSegment;
        DbIterator dbIterator;
        boolean valid;

        public PartitionIterator(Partition partition) {
            this.partition = partition;
            RocksIterator iterator;
            try {
                iterator = parchive.getIterator(partition);
            } catch (RocksDBException | IOException e) {
                throw new ParameterArchiveException("Failed to create iterator", e);
            }
            if (ascending) {
                dbIterator = new AscendingRangeIterator(iterator, rangeStart, rangeStop);
            } else {
                dbIterator = new DescendingRangeIterator(iterator, rangeStart, rangeStop);
            }
            next();
        }

        public void next() {
            if (!dbIterator.isValid()) {
                valid = false;
                return;
            }
            if (ascending) {
                nextAscending();
            } else {
                nextDescending();
            }
        }

        void nextAscending() {
            currentKey = SegmentKey.decode(dbIterator.key());
            valid = true;

            SegmentKey key = currentKey;
            while (key.segmentStart == currentKey.segmentStart) {
                loadSegment(key.type);
                dbIterator.next();
                if (dbIterator.isValid()) {
                    key = SegmentKey.decode(dbIterator.key());
                } else {
                    break;
                }
            }
        }

        void nextDescending() {
            currentKey = SegmentKey.decode(dbIterator.key());
            valid = true;
            SegmentKey key = currentKey;

            while (key.segmentStart == currentKey.segmentStart) {
                loadSegment(key.type);
                dbIterator.prev();
                if (dbIterator.isValid()) {
                    key = SegmentKey.decode(dbIterator.key());
                } else {
                    break;
                }
            }
        }

        private void loadSegment(byte type) {
            if ((type == SegmentKey.TYPE_ENG_VALUE) && (retrieveEngValues || retrieveRawValues)) {
                currentEngValueSegment = dbIterator.value();
            }
            if ((type == SegmentKey.TYPE_RAW_VALUE) && retrieveRawValues) {
                currentRawValueSegment = dbIterator.value();
            }
            if ((type == SegmentKey.TYPE_PARAMETER_STATUS) && retrieveParameterStatus) {
                currentStatusSegment = dbIterator.value();
            }
        }

        SegmentKey key() {
            return currentKey;
        }

        ParameterValueSegment value() {
            if (!valid) {
                throw new NoSuchElementException();
            }

            ParameterValueSegment pvs = new ParameterValueSegment(null);
            long segStart = currentKey.segmentStart;
            try {
                pvs.timeSegment = parchive.getTimeSegment(partition, segStart, parameterGroupId);
                if (pvs.timeSegment == null) {
                    String msg = "Cannot find a time segment for parameterGroupId=" + parameterGroupId
                            + " segmentStart = " + segStart + " despite having a value segment for parameterId: "
                            + parameterId;
                    throw new DatabaseCorruptionException(msg);
                }

                ValueSegment engValueSegment = null;
                if (currentEngValueSegment != null) {
                    engValueSegment = (ValueSegment) segmentEncoder.decode(currentEngValueSegment, segStart);
                }
                if (retrieveEngValues) {
                    pvs.engValueSegment = engValueSegment;
                }

                if (currentRawValueSegment != null) {
                    pvs.rawValueSegment = (ValueSegment) segmentEncoder.decode(currentRawValueSegment, segStart);
                } else if (retrieveRawValues) {
                    pvs.rawValueSegment = engValueSegment;
                }

                if (currentStatusSegment != null) {
                    pvs.parameterStatusSegment = (ParameterStatusSegment) segmentEncoder.decode(currentStatusSegment,
                            segStart);
                }

            } catch (DecodingException e) {
                throw new DatabaseCorruptionException(e);
            } catch (RocksDBException | IOException e) {
                throw new ParameterArchiveException("Failded extracting data from the parameter archive", e);
            }

            return pvs;
        }

        boolean isValid() {
            return valid;
        }


        void close() {
            if (dbIterator != null) {
                dbIterator.close();
            }
        }
    }

}