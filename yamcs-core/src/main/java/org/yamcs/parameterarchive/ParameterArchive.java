package org.yamcs.parameterarchive;

import static org.yamcs.yarch.rocksdb.RdbStorageEngine.TBS_INDEX_SIZE;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Future;

import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.yamcs.AbstractYamcsService;
import org.yamcs.ConfigurationException;
import org.yamcs.InitException;
import org.yamcs.Spec;
import org.yamcs.Spec.OptionType;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.time.TimeService;
import org.yamcs.utils.ByteArrayUtils;
import org.yamcs.utils.DatabaseCorruptionException;
import org.yamcs.utils.DecodingException;
import org.yamcs.utils.PartitionedTimeInterval;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.utils.TimeInterval;
import org.yamcs.yarch.TimePartitionInfo;
import org.yamcs.yarch.TimePartitionSchema;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;
import org.yamcs.yarch.rocksdb.AscendingRangeIterator;
import org.yamcs.yarch.rocksdb.RdbStorageEngine;
import org.yamcs.yarch.rocksdb.Tablespace;
import org.yamcs.yarch.rocksdb.YRDB;
import org.yamcs.yarch.rocksdb.protobuf.Tablespace.TablespaceRecord;
import org.yamcs.yarch.rocksdb.protobuf.Tablespace.TablespaceRecord.Type;
import org.yamcs.yarch.rocksdb.protobuf.Tablespace.TimeBasedPartition;

/**
 * 
 * The parameter archive stores data in partitions -> intervals -> segments. A partition covers one year and each
 * partition has its own RocksDB database
 * 
 * An interval covers 2^23 millisec =~ 139 minutes
 * 
 * An segment covers at most maxSegmentSize values for one parameter
 * 
 * 
 * 
 * @author nm
 *
 */
public class ParameterArchive extends AbstractYamcsService {

    public static final boolean STORE_RAW_VALUES = true;

    public static final int NUMBITS_MASK = 23; // 2^23 millisecons =~ 139 minutes per interval
    public static final int TIMESTAMP_MASK = (0xFFFFFFFF >>> (32 - NUMBITS_MASK));
    public static final long INTERVAL_MASK = ~TIMESTAMP_MASK;

    private ParameterIdDb parameterIdMap;
    private ParameterGroupIdDb parameterGroupIdMap;
    private Tablespace tablespace;

    TimePartitionSchema partitioningSchema;

    // the tbsIndex used to encode partition information
    int partitionTbsIndex;

    private PartitionedTimeInterval<Partition> partitions = new PartitionedTimeInterval<>();
    SegmentEncoderDecoder vsEncoder = new SegmentEncoderDecoder();

    TimeService timeService;
    private BackFiller backFiller;
    private RealtimeArchiveFiller realtimeFiller;
    YConfiguration realtimeFillerConfig;
    YConfiguration backFillerConfig;
    boolean realtimeFillerEnabled;
    boolean backFillerEnabled;

    @Override
    public Spec getSpec() {
        Spec spec = new Spec();
        spec.addOption("backFiller", OptionType.ANY);
        spec.addOption("realtimeFiller", OptionType.ANY);
        spec.addOption("partitioningSchema", OptionType.STRING).withDefault("YYYY")
                .withChoices("YYYY/DOY", "YYYY/MM", "YYYY", "none");

        return spec;
    }

    @Override
    public void init(String yamcsInstance, YConfiguration config) throws InitException {
        super.init(yamcsInstance, config);

        timeService = YamcsServer.getTimeService(yamcsInstance);
        YarchDatabaseInstance ydb = YarchDatabase.getInstance(yamcsInstance);
        tablespace = RdbStorageEngine.getInstance().getTablespace(ydb);

        if (config.containsKey("backFiller")) {
            backFillerConfig = config.getConfig("backFiller");
            log.debug("backFillerConfig: {}", backFillerConfig);
            backFillerEnabled = backFillerConfig.getBoolean("enabled", true);
        }
        if (config.containsKey("realtimeFiller")) {
            realtimeFillerConfig = config.getConfig("realtimeFiller");
            realtimeFillerEnabled = realtimeFillerConfig.getBoolean("enabled", false);
            log.debug("realtimeFillerConfig: {}", realtimeFillerConfig);
        }

        String schema = config.getString("partitioningSchema");
        if (!"none".equalsIgnoreCase(schema)) {
            partitioningSchema = TimePartitionSchema.getInstance(schema);
        }

        if (!config.containsKey("backFiller") && !config.containsKey("realtimeFiller")) {
            backFiller = new BackFiller(this, null);
        }

        try {
            TablespaceRecord.Type trType = TablespaceRecord.Type.PARCHIVE_PINFO;
            List<TablespaceRecord> trl = tablespace.filter(trType, yamcsInstance, trb -> true);
            if (trl.size() > 1) {
                throw new DatabaseCorruptionException(
                        "More than one tablespace record of type " + trType.name() + " for instance " + yamcsInstance);
            }
            parameterIdMap = new ParameterIdDb(yamcsInstance, tablespace);
            parameterGroupIdMap = new ParameterGroupIdDb(yamcsInstance, tablespace);

            TablespaceRecord tr;
            if (trl.isEmpty()) { // new database
                initializeDb();
            } else {// existing database
                tr = trl.get(0);
                partitionTbsIndex = tr.getTbsIndex();
                if (tr.hasPartitioningSchema()) {
                    partitioningSchema = TimePartitionSchema.getInstance(tr.getPartitioningSchema());
                }
                readPartitions();
            }
            if (partitioningSchema == null) {
                partitions.insert(new Partition());
            }
        } catch (RocksDBException | IOException e) {
            throw new InitException(e);
        }
    }

    private void initializeDb() throws RocksDBException {
        log.debug("initializing db");

        TablespaceRecord.Builder trb = TablespaceRecord.newBuilder().setType(Type.PARCHIVE_PINFO);
        if (partitioningSchema != null) {
            trb.setPartitioningSchema(partitioningSchema.getName());
        }

        TablespaceRecord tr = tablespace.createMetadataRecord(yamcsInstance, trb);
        partitionTbsIndex = tr.getTbsIndex();
    }

    public TimePartitionSchema getPartitioningSchema() {
        return partitioningSchema;
    }

    private void readPartitions() throws IOException, RocksDBException {
        YRDB db = tablespace.getRdb();
        byte[] range = new byte[TBS_INDEX_SIZE];
        ByteArrayUtils.encodeInt(partitionTbsIndex, range, 0);

        try (AscendingRangeIterator it = new AscendingRangeIterator(db.newIterator(), range, false, range, false)) {
            while (it.isValid()) {
                TimeBasedPartition tbp = TimeBasedPartition.parseFrom(it.value());
                Partition p = new Partition(tbp.getPartitionStart(), tbp.getPartitionEnd(), tbp.getPartitionDir());
                Partition p1 = partitions.insert(p, 0);
                if (p1 == null) {
                    throw new DatabaseCorruptionException("Partition " + p + " overlaps with existing partitions");
                }
                it.next();
            }
        }
    }

    static long decodePartitionId(String prefix, String cfname) {
        try {
            return Long.parseLong(cfname.substring(prefix.length()), 16);
        } catch (NumberFormatException e) {
            throw new ParameterArchiveException("Cannot decode partition id from column family: " + cfname);
        }
    }

    public ParameterIdDb getParameterIdDb() {
        return parameterIdMap;
    }

    public ParameterGroupIdDb getParameterGroupIdDb() {
        return parameterGroupIdMap;
    }

    public void writeToArchive(PGSegment pgs) throws RocksDBException, IOException {
        pgs.consolidate();
        Partition p = createAndGetPartition(pgs.getSegmentStart());
        try (WriteBatch writeBatch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            writeToBatch(writeBatch, p, pgs);
            tablespace.getRdb(p.partitionDir, false).getDb().write(wo, writeBatch);
        }
    }

    public void writeToArchive(long segStart, Collection<PGSegment> pgList) throws RocksDBException, IOException {
        Partition p = createAndGetPartition(segStart);
        try (WriteBatch writeBatch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {

            for (PGSegment pgs : pgList) {
                pgs.consolidate();
                assert (segStart == pgs.getSegmentStart());
                writeToBatch(writeBatch, p, pgs);
            }
            tablespace.getRdb(p.partitionDir, false).getDb().write(wo, writeBatch);
        }
    }

    private void writeToBatch(WriteBatch writeBatch, Partition p, PGSegment pgs) throws RocksDBException {
        // write the time segment
        SortedTimeSegment timeSegment = pgs.getTimeSegment();
        byte[] timeKey = new SegmentKey(parameterIdMap.timeParameterId, pgs.getParameterGroupId(),
                pgs.getSegmentStart(), SegmentKey.TYPE_ENG_VALUE).encode();
        byte[] timeValue = vsEncoder.encode(timeSegment);
        writeBatch.put(timeKey, timeValue);

        // and then the consolidated value segments
        List<BaseSegment> consolidated = pgs.getConsolidatedValueSegments();
        List<BaseSegment> consolidatedRawValues = pgs.getConsolidatedRawValueSegments();
        List<ParameterStatusSegment> satusSegments = pgs.getConsolidatedParameterStatusSegments();

        for (int i = 0; i < consolidated.size(); i++) {
            BaseSegment vs = consolidated.get(i);
            int parameterId = pgs.getParameterId(i);
            String pname = parameterIdMap.getParameterFqnById(parameterId);
            if (vs.size() != timeSegment.size()) {
                throw new IllegalArgumentException(
                        "Trying to write to archive an engineering value segment whose size (" + vs.size()
                                + ") is different than the time segment (" + timeSegment.size() + ") "
                                + "for parameterId: " + parameterId + "(" + pname + ") and segment: ["
                                + TimeEncoding.toString(timeSegment.getSegmentStart()) + " - "
                                + TimeEncoding.toString(timeSegment.getSegmentEnd()) + "]");
            }
            byte[] engKey = new SegmentKey(parameterId, pgs.getParameterGroupId(), pgs.getSegmentStart(),
                    SegmentKey.TYPE_ENG_VALUE).encode();
            byte[] engValue = vsEncoder.encode(vs);
            writeBatch.put(engKey, engValue);

            if (STORE_RAW_VALUES && consolidatedRawValues != null) {
                BaseSegment rvs = consolidatedRawValues.get(i);
                if (rvs != null) {
                    if (rvs.size() != timeSegment.size()) {
                        throw new IllegalArgumentException(
                                "Trying to write to archive an raw value segment whose size (" + rvs.size()
                                        + ") is different than the time segment (" + timeSegment.size() + ") "
                                        + "for parameterId: " + parameterId + "(" + pname + ") and segment: ["
                                        + TimeEncoding.toString(timeSegment.getSegmentStart()) + " - "
                                        + TimeEncoding.toString(timeSegment.getSegmentEnd()) + "]");
                    }
                    byte[] rawKey = new SegmentKey(parameterId, pgs.getParameterGroupId(), pgs.getSegmentStart(),
                            SegmentKey.TYPE_RAW_VALUE).encode();
                    byte[] rawValue = vsEncoder.encode(rvs);
                    writeBatch.put(rawKey, rawValue);

                }
            }
            ParameterStatusSegment pss = satusSegments.get(i);
            if (pss.size() != timeSegment.size()) {
                throw new IllegalArgumentException("Trying to write to archive an parameter status segment whose size ("
                        + pss.size() + ") is different than the time segment (" + timeSegment.size() + ") "
                        + "for parameterId: " + parameterId + "(" + pname + ") and segment: ["
                        + TimeEncoding.toString(timeSegment.getSegmentStart()) + " - "
                        + TimeEncoding.toString(timeSegment.getSegmentEnd()) + "]");
            }
            byte[] pssKey = new SegmentKey(parameterId, pgs.getParameterGroupId(), pgs.getSegmentStart(),
                    SegmentKey.TYPE_PARAMETER_STATUS).encode();
            byte[] pssValue = vsEncoder.encode(pss);
            writeBatch.put(pssKey, pssValue);
        }
    }

    /**
     * get partition for segment, creating it if it doesn't exist
     * 
     * @param segStart
     * @throws RocksDBException
     */
    public Partition createAndGetPartition(long segStart) throws RocksDBException {
        synchronized (partitions) {
            Partition p = partitions.getFit(segStart);

            if (p == null) {
                TimePartitionInfo pinfo = partitioningSchema.getPartitionInfo(segStart);
                p = new Partition(pinfo.getStart(), pinfo.getEnd(), pinfo.getDir());
                p = partitions.insert(p, 60000L);
                assert p != null;
                TimeBasedPartition tbp = TimeBasedPartition.newBuilder().setPartitionDir(p.partitionDir)
                        .setPartitionStart(p.getStart()).setPartitionEnd(p.getEnd()).build();
                byte[] key = new byte[TBS_INDEX_SIZE + 8];
                ByteArrayUtils.encodeInt(partitionTbsIndex, key, 0);
                ByteArrayUtils.encodeLong(pinfo.getStart(), key, TBS_INDEX_SIZE);
                tablespace.putData(key, tbp.toByteArray());
            }
            return p;
        }
    }

    public Future<?> reprocess(long start, long stop) {
        log.debug("Scheduling a reprocess for interval [{} - {}]", TimeEncoding.toString(start),
                TimeEncoding.toString(stop));
        if (backFiller == null) {
            throw new ConfigurationException("backFilling is not enabled");
        }
        return backFiller.scheduleFillingTask(start, stop);
    }

    /**
     * a copy of the partitions from start to stop inclusive
     * 
     * @param start
     * @param stop
     * @return a sorted list of partitions
     */
    public List<Partition> getPartitions(long start, long stop, boolean ascending) {
        List<Partition> r = new ArrayList<>();
        Iterator<Partition> it;
        if (ascending) {
            it = partitions.overlappingIterator(new TimeInterval(start, stop));
        } else {
            it = partitions.overlappingReverseIterator(new TimeInterval(start, stop));
        }
        while (it.hasNext()) {
            r.add(it.next());
        }
        return r;
    }

    @Override
    protected void doStart() {
        if (backFillerEnabled) {
            backFiller = new BackFiller(this, backFillerConfig);
            backFiller.start();
        }
        if (realtimeFillerEnabled) {
            realtimeFiller = new RealtimeArchiveFiller(this, realtimeFillerConfig);
            realtimeFiller.startAsync();
        }
        notifyStarted();
    }

    @Override
    protected void doStop() {
        log.debug("Stopping ParameterArchive service for instance {}", yamcsInstance);

        if (backFiller != null) {
            backFiller.stop();
        }

        if (realtimeFiller != null) {
            realtimeFiller.stopAsync();
            realtimeFiller.awaitTerminated();
        }
        notifyStopped();
    }

    public void printKeys(PrintStream out) throws DecodingException, RocksDBException, IOException {
        out.println("pid\t pgid\t type\tSegmentStart\tcount\tsize\tstype");
        SegmentEncoderDecoder decoder = new SegmentEncoderDecoder();
        for (Partition p : partitions) {
            try (RocksIterator it = getIterator(p)) {
                it.seekToFirst();
                while (it.isValid()) {
                    SegmentKey key = SegmentKey.decode(it.key());
                    byte[] v = it.value();
                    BaseSegment s;
                    s = decoder.decode(it.value(), key.segmentStart);
                    out.println(key.parameterId + "\t " + key.parameterGroupId + "\t " + key.type + "\t"
                            + TimeEncoding.toString(key.segmentStart) + "\t" + s.size() + "\t" + v.length + "\t"
                            + s.getClass().getSimpleName());
                    it.next();
                }
            }
        }
    }

    /**
     * Delete all partitions that overlap with [start, stop) segment.
     * 
     * @param start
     * @param stop
     * @throws RocksDBException
     * @return all the partitions removed
     */
    public List<Partition> deletePartitions(long start, long stop) throws RocksDBException {
        // List<Partition> parts = getPartitions(start, stop, true);
        throw new UnsupportedOperationException("operation not supported");
    }

    public RocksIterator getIterator(Partition p) throws RocksDBException, IOException {
        return tablespace.getRdb(p.partitionDir, false).newIterator();
    }

    public SortedTimeSegment getTimeSegment(Partition p, long segmentStart, int parameterGroupId)
            throws RocksDBException, IOException {
        byte[] timeKey = new SegmentKey(parameterIdMap.timeParameterId, parameterGroupId, segmentStart,
                SegmentKey.TYPE_ENG_VALUE).encode();
        byte[] tv = tablespace.getRdb(p.partitionDir, false).get(timeKey);
        if (tv == null) {
            return null;
        }
        try {
            return (SortedTimeSegment) vsEncoder.decode(tv, segmentStart);
        } catch (DecodingException e) {
            throw new DatabaseCorruptionException(e);
        }
    }

    Partition getPartitions(long instant) {
        synchronized (partitions) {
            return partitions.getFit(instant);
        }
    }

    /**
     * returns the intervalStart where this instant could fit.
     * 
     * @param instant
     * @return
     */
    public static long getIntervalStart(long instant) {
        return instant & INTERVAL_MASK;
    }

    /**
     * returns the end of the interval where the instant fits
     * 
     * @param instant
     * @return
     */
    public static long getIntervalEnd(long instant) {
        return instant | TIMESTAMP_MASK;
    }

    /**
     * duration in milliseconds of one segment
     * 
     * @return
     */
    public static long getIntervalDuration() {
        return TIMESTAMP_MASK + 1;
    }

    public Tablespace getTablespace() {
        return tablespace;
    }

    public static class Partition extends TimeInterval {
        final String partitionDir;

        Partition() {
            super();
            this.partitionDir = null;
        }

        Partition(long start, long end, String dir) {
            super(start, end);
            this.partitionDir = dir;
        }

        @Override
        public String toString() {
            return "partition: " + partitionDir + "[" + TimeEncoding.toString(getStart()) + " - "
                    + TimeEncoding.toString(getEnd()) + "]";
        }

        public String getPartitionDir() {
            return partitionDir;
        }
    }

}
