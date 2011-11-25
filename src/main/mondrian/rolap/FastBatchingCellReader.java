/*
// $Id$
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// Copyright (C) 2004-2011 Julian Hyde and others
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/
package mondrian.rolap;

import mondrian.olap.*;
import mondrian.rolap.agg.*;
import mondrian.rolap.aggmatcher.AggGen;
import mondrian.rolap.aggmatcher.AggStar;
import mondrian.server.Execution;
import mondrian.server.Locus;
import mondrian.spi.Dialect;
import mondrian.spi.SegmentHeader;
import mondrian.util.*;

import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.Future;

/**
 * A <code>FastBatchingCellReader</code> doesn't really Read cells: when asked
 * to look up the values of stored measures, it lies, and records the fact
 * that the value was asked for.  Later, we can look over the values which
 * are required, fetch them in an efficient way, and re-run the evaluation
 * with a real evaluator.
 *
 * <p>NOTE: When it doesn't know the answer, it lies by returning an error
 * object.  The calling code must be able to deal with that.</p>
 *
 * <p>This class tries to minimize the amount of storage needed to record the
 * fact that a cell was requested.</p>
 */
public class FastBatchingCellReader implements CellReader {

    private static final Logger LOGGER =
        Logger.getLogger(FastBatchingCellReader.class);

    private final RolapCube cube;
    private final Execution execution;
    private final Map<AggregationKey, Batch> batches =
        new HashMap<AggregationKey, Batch>();

    /**
     * Records the number of requests. The field is used for correctness: if
     * the request count stays the same during an operation, you know that the
     * FastBatchingCellReader has not told any lies during that operation, and
     * therefore the result is true. The field is also useful for debugging.
     */
    private int missCount;

    /**
     * Number of occasions that a requested cell was already in cache.
     */
    private int hitCount;

    /**
     * Number of occasions that requested cell was in the process of being
     * loaded into cache but not ready.
     */
    private int pendingCount;

    final AggregationManager aggMgr;

    private final RolapAggregationManager.PinSet pinnedSegments;

    /**
     * Indicates that the reader has given incorrect results.
     *
     * @see Util#deprecated(Object) I don't think this is ever set, other than
     *   if there are cache misses; can remove
     */
    private boolean dirty;

    private final List<Future<SegmentWithData>> futureSegments =
        new ArrayList<Future<SegmentWithData>>();
    private final List<CellRequest> cellRequests = new ArrayList<CellRequest>();

    private final Set<SegmentHeader> segmentHeaderResults =
        Util.newIdentityHashSet();

    public FastBatchingCellReader(Execution execution, RolapCube cube) {
        assert cube != null;
        assert execution != null;
        this.execution = execution;
        this.cube = cube;
        aggMgr = execution.getMondrianStatement().getMondrianConnection()
            .getServer().getAggregationManager();
        pinnedSegments = aggMgr.createPinSet();
    }

    public Object get(RolapEvaluator evaluator) {
        final CellRequest request =
            RolapAggregationManager.makeRequest(evaluator);

        if (request == null || request.isUnsatisfiable()) {
            return Util.nullValue; // request not satisfiable.
        }

        // Try to retrieve a cell and simultaneously pin the segment which
        // contains it.
        final Object o = aggMgr.getCellFromCache(request, pinnedSegments);

        if (o == Boolean.TRUE) {
            // Aggregation is being loaded. (todo: Use better value, or
            // throw special exception)
            ++pendingCount;
            return RolapUtil.valueNotReadyException;
        }
        if (o != null) {
            ++hitCount;
            return o;
        }
        // if there is no such cell, record that we need to fetch it, and
        // return 'error'
        recordCellRequest(request);
        return RolapUtil.valueNotReadyException;
    }

    public int getMissCount() {
        return missCount;
    }

    public int getHitCount() {
        return hitCount;
    }

    public int getPendingCount() {
        return pendingCount;
    }

    public final void recordCellRequest(CellRequest request) {
        if (request.isUnsatisfiable()) {
            return;
        }
        ++missCount;
        cellRequests.add(request);
        if (cellRequests.size() % 5000 == 0) {
            // Signal that it's time to ask the cache manager if it has cells
            // we need in the cache. Not really an exception.
            throw CellRequestQuantumExceededException.INSTANCE;
        }
    }

    private void recordCellRequest2(
        CellRequest request)
    {
        // If there is a segment matching these criteria, write it to the list
        // of found segments, and remove the cell request from the list.
        final AggregationKey key = new AggregationKey(request);
        Pair<SegmentHeader, SegmentBody> headerBody =
            locateHeaderBody(
                request,
                request.getMappedCellValues(),
                key);
        if (headerBody != null) {
            // A previous cell request in this request might have hit the same
            // segment. Only create a segment the first time we see this segment
            // header.
            final SegmentHeader header = headerBody.left;
            if (segmentHeaderResults.add(header)) {
                Segment segment =
                    SegmentBuilder.toSegment(
                        header,
                        request.getMeasure().getStar(),
                        request.getConstrainedColumnsBitKey(),
                        request.getConstrainedColumns(),
                        request.getMeasure(),
                        key.getCompoundPredicateList());
                final SegmentBody body = headerBody.right;
                final SegmentWithData segmentWithData =
                    SegmentBuilder.addData(segment, body);
                futureSegments.add(
                    new CompletedFuture<SegmentWithData>(
                        segmentWithData, null));
            }
            return;
        }

        // TODO: try to roll up

        // Finally, add to a batch. It will turn in to a SQL request.
        Batch batch = batches.get(key);
        if (batch == null) {
            batch = new Batch(request);
            batches.put(key, batch);

            if (LOGGER.isDebugEnabled()) {
                StringBuilder buf = new StringBuilder(100);
                buf.append("FastBatchingCellReader: bitkey=");
                buf.append(request.getConstrainedColumnsBitKey());
                buf.append(Util.nl);

                for (RolapStar.Column column
                    : request.getConstrainedColumns())
                {
                    buf.append("  ");
                    buf.append(column);
                    buf.append(Util.nl);
                }
                LOGGER.debug(buf.toString());
            }
        }
        batch.add(request);
    }

    /**
     * Locates a segment that actually exists.
     *
     * @param request Cell request
     * @param map Column values
     * @param key Aggregate key.
     * @return Segment header and body
     */
    private Pair<SegmentHeader, SegmentBody> locateHeaderBody(
        CellRequest request,
        Map<String, Comparable<?>> map,
        AggregationKey key)
    {
        final List<SegmentHeader> locate =
            aggMgr.cacheMgr.segmentIndex.locate(
                request.getMeasure().getStar().getSchema().getName(),
                request.getMeasure().getStar().getSchema().getChecksum(),
                request.getMeasure().getCubeName(),
                request.getMeasure().getName(),
                request.getMeasure().getStar().getFactTable().getAlias(),
                request.getConstrainedColumnsBitKey(),
                map,
                AggregationKey.getCompoundPredicateArray(
                    key.getStar(),
                    key.getCompoundPredicateList()));
        for (SegmentHeader header : locate) {
            for (SegmentCacheWorker worker : aggMgr.segmentCacheWorkers) {
                final SegmentBody body = worker.get(header);
                if (body != null) {
                    return Pair.of(header, body);
                }
            }
        }
        return null;
    }

    /**
     * Returns whether this reader has told a lie. This is the case if there
     * are pending batches to load or if {@link #setDirty(boolean)} has been
     * called.
     */
    public boolean isDirty() {
        return dirty || !cellRequests.isEmpty();
    }

    /**
     * Resolves any pending cell reads using the cache.
     *
     * <p>Returns a list of futures for segments that will be loaded to satisfy
     * such cell requests. If there are no pending cell reads, returns the empty
     * list.</p>
     *
     * <p>If the list is non-empty, it means that the reader has told lies.
     * To be safe, the caller must re-evaluate expressions after loading
     * segments.</p>
     *
     * <p>For each segment future, caller must retrieve the segment (say by
     * calling {@link java.util.concurrent.Future#get()}) and place the segment
     * into the statement-local cache.
     *
     * <p>The segments can come from various places:</p>
     * <ul>
     *     <li>Global cache (shared between all statements in this Mondrian
     *     server that use the same star)</li>
     *     <li>External cache</li>
     *     <li>By rolling up a segment or segments in global cache or external
     *     cache</li>
     *     <li>By executing a SQL {@code GROUP BY} statement</li>
     * </ul>
     *
     *
     *
     *
     * Asks the cache to ensure that all cells requested in a given batch are
     * loaded into a segment. The result may contain multiple segments, and
     * each of the segments may or may not have completed loading.
     *
     * <p>The client should put the resulting segments into its "query local"
     * cache, to ensure that future cells in that segment can be answered
     * without a call to the cache manager. (That is probably 1000x faster.)</p>
     *
     * <p>The cache manager does not inform where client where each segment
     * came from. There are several possibilities:</p>
     *
     * <ul>
     *     <li>Segment was already in cache (header and body)</li>
     *     <li>Segment is in the process of being loaded by executing a SQL
     *     statement (probably due to a request from another client)</li>
     *     <li>Segment is in an external cache (that is, header is in the cache,
     *        body is not yet)</li>
     *     <li>Segment can be created by rolling up one or more cache segments.
     *        (And of course each of these segments might be "paged out".)</li>
     * </ul>
     *
     *
     *
     *
     *
     *
     *
     * @return Whether any aggregations were loaded.
     */
    boolean loadAggregations() {
        if (!isDirty()) {
            return false;
        }
        final Locus locus = Locus.peek();
        List<Future<SegmentWithData>> segmentFutures =
            aggMgr.cacheMgr.execute(
                new SegmentCacheManager.Command<List<Future<SegmentWithData>>>()
                {
                    public List<Future<SegmentWithData>> call()
                        throws Exception
                    {
                        Locus.push(locus);
                        try {
                            return runAsync();
                        } finally {
                            Locus.pop(locus);
                        }
                    }
                }
            );

        // Wait for segments to finish loading, and place them in thread-local
        // cache. Note that this step can't be done by the cacheMgr -- it's our
        // cache.
        for (Future<SegmentWithData> segmentFuture : segmentFutures) {
            final SegmentWithData segmentWithData =
                Util.safeGet(segmentFuture, "While loading cache segments");

            segmentWithData.getStar().register(segmentWithData);
        }
        return true;
    }

    /**
     *
     * @return List of segment futures. Each segment future may or may not be
     *    already present (it depends on the current location of the segment
     *    body). Each future will return a not-null segment (or throw).
     */
    private List<Future<SegmentWithData>> runAsync() {
        final long t1 = System.currentTimeMillis();

        // Now we're inside the cache manager, we can see which of our cell
        // requests can be answered from cache. Those that can will be added
        // to the segments list; those that can not will be converted into
        // batches and rolled up or loaded using SQL.
        for (CellRequest cellRequest : cellRequests) {
            recordCellRequest2(cellRequest);
        }
        cellRequests.clear();

        // Sort the batches into deterministic order.
        List<Batch> batchList =
            new ArrayList<Batch>(batches.values());
        Collections.sort(batchList, BatchComparator.instance);
        if (shouldUseGroupingFunction()) {
            LOGGER.debug("Using grouping sets");
            List<CompositeBatch> groupedBatches = groupBatches(batchList);
            for (CompositeBatch batch : groupedBatches) {
                loadAggregation(batch);
            }
        } else {
            // Load batches in turn.
            for (Batch batch : batchList) {
                loadAggregation(batch);
            }
        }

        final List<Future<SegmentWithData>> list =
            new ArrayList<Future<SegmentWithData>>(futureSegments);
        futureSegments.clear();
        segmentHeaderResults.clear();
        batches.clear();
        dirty = false;

        if (LOGGER.isDebugEnabled()) {
            final long t2 = System.currentTimeMillis();
            LOGGER.debug("loadAggregation (millis): " + (t2 - t1));
        }

        return list;
    }

    private void loadAggregation(Loadable batch) {
        if (execution != null) {
            execution.checkCancelOrTimeout();
        }
        batch.loadAggregation();
    }

    List<CompositeBatch> groupBatches(List<Batch> batchList) {
        Map<AggregationKey, CompositeBatch> batchGroups =
            new HashMap<AggregationKey, CompositeBatch>();
        for (int i = 0; i < batchList.size(); i++) {
            for (int j = i + 1; j < batchList.size() && i < batchList.size();) {
                FastBatchingCellReader.Batch iBatch = batchList.get(i);
                FastBatchingCellReader.Batch jBatch = batchList.get(j);
                if (iBatch.canBatch(jBatch)) {
                    batchList.remove(j);
                    addToCompositeBatch(batchGroups, iBatch, jBatch);
                } else if (jBatch.canBatch(iBatch)) {
                    batchList.set(i, jBatch);
                    batchList.remove(j);
                    addToCompositeBatch(batchGroups, jBatch, iBatch);
                    j = i + 1;
                } else {
                    j++;
                }
            }
        }

        wrapNonBatchedBatchesWithCompositeBatches(batchList, batchGroups);
        final CompositeBatch[] compositeBatches =
            batchGroups.values().toArray(
                new CompositeBatch[batchGroups.size()]);
        Arrays.sort(compositeBatches, CompositeBatchComparator.instance);
        return Arrays.asList(compositeBatches);
    }

    private void wrapNonBatchedBatchesWithCompositeBatches(
        List<Batch> batchList,
        Map<AggregationKey, CompositeBatch> batchGroups)
    {
        for (Batch batch : batchList) {
            if (batchGroups.get(batch.batchKey) == null) {
                batchGroups.put(batch.batchKey, new CompositeBatch(batch));
            }
        }
    }


    void addToCompositeBatch(
        Map<AggregationKey, CompositeBatch> batchGroups,
        Batch detailedBatch,
        Batch summaryBatch)
    {
        CompositeBatch compositeBatch = batchGroups.get(detailedBatch.batchKey);

        if (compositeBatch == null) {
            compositeBatch = new CompositeBatch(detailedBatch);
            batchGroups.put(detailedBatch.batchKey, compositeBatch);
        }

        FastBatchingCellReader.CompositeBatch compositeBatchOfSummaryBatch =
            batchGroups.remove(summaryBatch.batchKey);

        if (compositeBatchOfSummaryBatch != null) {
            compositeBatch.merge(compositeBatchOfSummaryBatch);
        } else {
            compositeBatch.add(summaryBatch);
        }
    }

    boolean shouldUseGroupingFunction() {
        return MondrianProperties.instance().EnableGroupingSets.get()
            && doesDBSupportGroupingSets();
    }

    /**
     * Uses Dialect to identify if grouping sets is supported by the
     * database.
     */
    boolean doesDBSupportGroupingSets() {
        return getDialect().supportsGroupingSets();
    }

    /**
     * Returns the SQL dialect. Overridden in some unit tests.
     *
     * @return Dialect
     */
    Dialect getDialect() {
        final RolapStar star = cube.getStar();
        if (star != null) {
            return star.getSqlQueryDialect();
        } else {
            return cube.getSchema().getDialect();
        }
    }

    /**
     * Sets the flag indicating that the reader has told a lie.
     */
    void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    /**
     * Set of Batches which can grouped together.
     */
    class CompositeBatch implements Loadable {
        /** Batch with most number of constraint columns */
        final Batch detailedBatch;

        /** Batches whose data can be fetched using rollup on detailed batch */
        final List<Batch> summaryBatches = new ArrayList<Batch>();

        CompositeBatch(Batch detailedBatch) {
            this.detailedBatch = detailedBatch;
        }

        void add(Batch summaryBatch) {
            summaryBatches.add(summaryBatch);
        }

        void merge(CompositeBatch summaryBatch) {
            summaryBatches.add(summaryBatch.detailedBatch);
            summaryBatches.addAll(summaryBatch.summaryBatches);
        }

        public void loadAggregation() {
            GroupingSetsCollector batchCollector =
                new GroupingSetsCollector(true);
            this.detailedBatch.loadAggregation(batchCollector);

            int cellRequestCount = 0;
            for (Batch batch : summaryBatches) {
                batch.loadAggregation(batchCollector);
                cellRequestCount += batch.cellRequestCount;
            }

            getSegmentLoader().load(
                cellRequestCount,
                batchCollector.getGroupingSets(),
                detailedBatch.batchKey.getCompoundPredicateList());
        }

        SegmentLoader getSegmentLoader() {
            return new SegmentLoader(aggMgr);
        }
    }

    private static final Logger BATCH_LOGGER = Logger.getLogger(Batch.class);

    /**
     * Encapsulates a common property of {@link Batch} and
     * {@link CompositeBatch}, namely, that they can be asked to load their
     * aggregations into the cache.
     */
    interface Loadable {
        void loadAggregation();
    }

    public class Batch implements Loadable {
        // the CellRequest's constrained columns
        final RolapStar.Column[] columns;
        final List<RolapStar.Measure> measuresList =
            new ArrayList<RolapStar.Measure>();
        final Set<StarColumnPredicate>[] valueSets;
        final AggregationKey batchKey;
        // string representation; for debug; set lazily in toString
        private String string;
        private int cellRequestCount;
        private List<StarColumnPredicate[]> tuples =
            new ArrayList<StarColumnPredicate[]>();

        public Batch(CellRequest request) {
            columns = request.getConstrainedColumns();
            valueSets = new HashSet[columns.length];
            for (int i = 0; i < valueSets.length; i++) {
                valueSets[i] = new HashSet<StarColumnPredicate>();
            }
            batchKey = new AggregationKey(request);
        }

        public String toString() {
            if (string == null) {
                final StringBuilder buf = new StringBuilder();
                buf.append("Batch {\n")
                    .append("  columns={").append(Arrays.toString(columns))
                    .append("}\n")
                    .append("  measures={").append(measuresList).append("}\n")
                    .append("  valueSets={").append(Arrays.toString(valueSets))
                    .append("}\n")
                    .append("  batchKey=").append(batchKey).append("}\n")
                    .append("}");
                string = buf.toString();
            }
            return string;
        }

        public final void add(CellRequest request) {
            ++cellRequestCount;
            final int valueCount = request.getNumValues();
            final StarColumnPredicate[] tuple =
                new StarColumnPredicate[valueCount];
            for (int j = 0; j < valueCount; j++) {
                final StarColumnPredicate value = request.getValueAt(j);
                valueSets[j].add(value);
                tuple[j] = value;
            }
            tuples.add(tuple);
            final RolapStar.Measure measure = request.getMeasure();
            if (!measuresList.contains(measure)) {
                assert (measuresList.size() == 0)
                       || (measure.getStar()
                           == (measuresList.get(0)).getStar())
                    : "Measure must belong to same star as other measures";
                measuresList.add(measure);
            }
        }

        /**
         * Returns the RolapStar associated with the Batch's first Measure.
         *
         * <p>This method can only be called after the {@link #add} method has
         * been called.
         *
         * @return the RolapStar associated with the Batch's first Measure
         */
        private RolapStar getStar() {
            RolapStar.Measure measure = measuresList.get(0);
            return measure.getStar();
        }

        public BitKey getConstrainedColumnsBitKey() {
            return batchKey.getConstrainedColumnsBitKey();
        }

        public final void loadAggregation() {
            GroupingSetsCollector collectorWithGroupingSetsTurnedOff =
                new GroupingSetsCollector(false);
            loadAggregation(collectorWithGroupingSetsTurnedOff);
        }

        final void loadAggregation(
            GroupingSetsCollector groupingSetsCollector)
        {
            if (MondrianProperties.instance().GenerateAggregateSql.get()) {
                generateAggregateSql();
            }
            final StarColumnPredicate[] predicates = initPredicates();
            final long t1 = System.currentTimeMillis();

            // TODO: optimize key sets; drop a constraint if more than x% of
            // the members are requested; whether we should get just the cells
            // requested or expand to a n-cube

            // If the database cannot execute "count(distinct ...)", split the
            // distinct aggregations out.
            final Dialect dialect = getDialect();

            int distinctMeasureCount = getDistinctMeasureCount(measuresList);
            boolean tooManyDistinctMeasures =
                distinctMeasureCount > 0
                && !dialect.allowsCountDistinct()
                || distinctMeasureCount > 1
                   && !dialect.allowsMultipleCountDistinct();

            if (tooManyDistinctMeasures) {
                doSpecialHandlingOfDistinctCountMeasures(
                    aggMgr, predicates, groupingSetsCollector);
            }

            // Load agg(distinct <SQL expression>) measures individually
            // for DBs that does allow multiple distinct SQL measures.
            if (!dialect.allowsMultipleDistinctSqlMeasures()) {
                // Note that the intention was orignially to capture the
                // subquery SQL measures and separate them out; However,
                // without parsing the SQL string, Mondrian cannot distinguish
                // between "col1" + "col2" and subquery. Here the measure list
                // contains both types.

                // See the test case testLoadDistinctSqlMeasure() in
                //  mondrian.rolap.FastBatchingCellReaderTest

                List<RolapStar.Measure> distinctSqlMeasureList =
                    getDistinctSqlMeasures(measuresList);
                for (RolapStar.Measure measure : distinctSqlMeasureList) {
                    RolapStar.Measure[] measures = {measure};
                    futureSegments.addAll(
                        aggMgr.loadAggregation(
                            cellRequestCount,
                            measures,
                            columns,
                            batchKey,
                            predicates,
                            pinnedSegments,
                            groupingSetsCollector));
                    measuresList.remove(measure);
                }
            }

            final int measureCount = measuresList.size();
            if (measureCount > 0) {
                final RolapStar.Measure[] measures =
                    measuresList.toArray(new RolapStar.Measure[measureCount]);
                futureSegments.addAll(
                    aggMgr.loadAggregation(
                        cellRequestCount,
                        measures,
                        columns,
                        batchKey,
                        predicates,
                        pinnedSegments,
                        groupingSetsCollector));
            }

            if (BATCH_LOGGER.isDebugEnabled()) {
                final long t2 = System.currentTimeMillis();
                BATCH_LOGGER.debug(
                    "Batch.loadAggregation (millis) " + (t2 - t1));
            }
        }

        private void doSpecialHandlingOfDistinctCountMeasures(
            AggregationManager aggmgr,
            StarColumnPredicate[] predicates,
            GroupingSetsCollector groupingSetsCollector)
        {
            while (true) {
                // Scan for a measure based upon a distinct aggregation.
                final RolapStar.Measure distinctMeasure =
                    getFirstDistinctMeasure(measuresList);
                if (distinctMeasure == null) {
                    break;
                }
                final String expr =
                    distinctMeasure.getExpression().getGenericExpression();
                final List<RolapStar.Measure> distinctMeasuresList =
                    new ArrayList<RolapStar.Measure>();
                for (int i = 0; i < measuresList.size();) {
                    final RolapStar.Measure measure = measuresList.get(i);
                    if (measure.getAggregator().isDistinct()
                        && measure.getExpression().getGenericExpression()
                        .equals(expr))
                    {
                        measuresList.remove(i);
                        distinctMeasuresList.add(distinctMeasure);
                    } else {
                        i++;
                    }
                }

                // Load all the distinct measures based on the same expression
                // together
                final RolapStar.Measure[] measures =
                    distinctMeasuresList.toArray(
                        new RolapStar.Measure[distinctMeasuresList.size()]);
                futureSegments.addAll(
                    aggmgr.loadAggregation(
                        cellRequestCount,
                        measures,
                        columns,
                        batchKey,
                        predicates,
                        pinnedSegments,
                        groupingSetsCollector));
            }
        }

        private StarColumnPredicate[] initPredicates() {
            StarColumnPredicate[] predicates =
                new StarColumnPredicate[columns.length];
            for (int j = 0; j < columns.length; j++) {
                Set<StarColumnPredicate> valueSet = valueSets[j];

                StarColumnPredicate predicate;
                if (valueSet == null) {
                    predicate = LiteralStarPredicate.FALSE;
                } else {
                    ValueColumnPredicate[] values =
                        valueSet.toArray(
                            new ValueColumnPredicate[valueSet.size()]);
                    // Sort array to achieve determinism in generated SQL.
                    Arrays.sort(
                        values,
                        ValueColumnConstraintComparator.instance);

                    predicate =
                        new ListColumnPredicate(
                            columns[j],
                            Arrays.asList((StarColumnPredicate[]) values));
                }

                predicates[j] = predicate;
            }
            return predicates;
        }

        private void generateAggregateSql() {
            final RolapCube cube = FastBatchingCellReader.this.cube;
            if (cube == null || cube.isVirtual()) {
                final StringBuilder buf = new StringBuilder(64);
                buf.append(
                    "AggGen: Sorry, can not create SQL for virtual Cube \"")
                    .append(cube == null ? null : cube.getName())
                    .append("\", operation not currently supported");
                BATCH_LOGGER.error(buf.toString());

            } else {
                final AggGen aggGen =
                    new AggGen(cube.getName(), cube.getStar(), columns);
                if (aggGen.isReady()) {
                    // PRINT TO STDOUT - DO NOT USE BATCH_LOGGER
                    System.out.println(
                        "createLost:" + Util.nl + aggGen.createLost());
                    System.out.println(
                        "insertIntoLost:" + Util.nl + aggGen.insertIntoLost());
                    System.out.println(
                        "createCollapsed:" + Util.nl
                        + aggGen.createCollapsed());
                    System.out.println(
                        "insertIntoCollapsed:" + Util.nl
                        + aggGen.insertIntoCollapsed());
                } else {
                    BATCH_LOGGER.error("AggGen failed");
                }
            }
        }

        /**
         * Returns the first measure based upon a distinct aggregation, or null
         * if there is none.
         */
        final RolapStar.Measure getFirstDistinctMeasure(
            List<RolapStar.Measure> measuresList)
        {
            for (RolapStar.Measure measure : measuresList) {
                if (measure.getAggregator().isDistinct()) {
                    return measure;
                }
            }
            return null;
        }

        /**
         * Returns the number of the measures based upon a distinct
         * aggregation.
         */
        private int getDistinctMeasureCount(
            List<RolapStar.Measure> measuresList)
        {
            int count = 0;
            for (RolapStar.Measure measure : measuresList) {
                if (measure.getAggregator().isDistinct()) {
                    ++count;
                }
            }
            return count;
        }

        /**
         * Returns the list of measures based upon a distinct aggregation
         * containing SQL measure expressions(as opposed to column expressions).
         *
         * This method was initially intended for only those measures that are
         * defined using subqueries(for DBs that support them). However, since
         * Mondrian does not parse the SQL string, the method will count both
         * queries as well as some non query SQL expressions.
         */
        private List<RolapStar.Measure> getDistinctSqlMeasures(
            List<RolapStar.Measure> measuresList)
        {
            List<RolapStar.Measure> distinctSqlMeasureList =
                new ArrayList<RolapStar.Measure>();
            for (RolapStar.Measure measure : measuresList) {
                if (measure.getAggregator().isDistinct()
                    && measure.getExpression() instanceof
                        MondrianDef.MeasureExpression)
                {
                    MondrianDef.MeasureExpression measureExpr =
                        (MondrianDef.MeasureExpression) measure.getExpression();
                    MondrianDef.SQL measureSql = measureExpr.expressions[0];
                    // Checks if the SQL contains "SELECT" to detect the case a
                    // subquery is used to define the measure. This is not a
                    // perfect check, because a SQL expression on column names
                    // containing "SELECT" will also be detected. e,g,
                    // count("select beef" + "regular beef").
                    if (measureSql.cdata.toUpperCase().contains("SELECT")) {
                        distinctSqlMeasureList.add(measure);
                    }
                }
            }
            return distinctSqlMeasureList;
        }

        /**
         * Returns whether another Batch can be batched to this Batch.
         *
         * <p>This is possible if:
         * <li>columns list is super set of other batch's constraint columns;
         *     and
         * <li>both have same Fact Table; and
         * <li>matching columns of this and other batch has the same value; and
         * <li>non matching columns of this batch have ALL VALUES
         * </ul>
         */
        boolean canBatch(Batch other) {
            return hasOverlappingBitKeys(other)
                && constraintsMatch(other)
                && hasSameMeasureList(other)
                && !hasDistinctCountMeasure()
                && !other.hasDistinctCountMeasure()
                && haveSameStarAndAggregation(other)
                && haveSameClosureColumns(other);
        }

        /**
         * Returns whether the constraints on this Batch subsume the constraints
         * on another Batch and therefore the other Batch can be subsumed into
         * this one for GROUPING SETS purposes. Not symmetric.
         *
         * @param other Other batch
         * @return Whether other batch can be subsumed into this one
         */
        private boolean constraintsMatch(Batch other) {
            if (areBothDistinctCountBatches(other)) {
                if (getConstrainedColumnsBitKey().equals(
                        other.getConstrainedColumnsBitKey()))
                {
                    return hasSameCompoundPredicate(other)
                        && haveSameValues(other);
                } else {
                    return hasSameCompoundPredicate(other)
                        || (other.batchKey.getCompoundPredicateList().isEmpty()
                            || equalConstraint(
                                batchKey.getCompoundPredicateList(),
                                other.batchKey.getCompoundPredicateList()))
                        && haveSameValues(other);
                }
            } else {
                return haveSameValues(other);
            }
        }

        private boolean equalConstraint(
            List<StarPredicate> predList1,
            List<StarPredicate> predList2)
        {
            if (predList1.size() != predList2.size()) {
                return false;
            }
            for (int i = 0; i < predList1.size(); i++) {
                StarPredicate pred1 = predList1.get(i);
                StarPredicate pred2 = predList2.get(i);
                if (!pred1.equalConstraint(pred2)) {
                    return false;
                }
            }
            return true;
        }

        private boolean areBothDistinctCountBatches(Batch other) {
            return this.hasDistinctCountMeasure()
                && !this.hasNormalMeasures()
                && other.hasDistinctCountMeasure()
                && !other.hasNormalMeasures();
        }

        private boolean hasNormalMeasures() {
            return getDistinctMeasureCount(measuresList)
                !=  measuresList.size();
        }

        private boolean hasSameMeasureList(Batch other) {
            return this.measuresList.size() == other.measuresList.size()
                   && this.measuresList.containsAll(other.measuresList);
        }

        boolean hasOverlappingBitKeys(Batch other) {
            return getConstrainedColumnsBitKey()
                .isSuperSetOf(other.getConstrainedColumnsBitKey());
        }

        boolean hasDistinctCountMeasure() {
            return getDistinctMeasureCount(measuresList) > 0;
        }

        boolean hasSameCompoundPredicate(Batch other) {
            final StarPredicate starPredicate = compoundPredicate();
            final StarPredicate otherStarPredicate = other.compoundPredicate();
            if (starPredicate == null && otherStarPredicate == null) {
                return true;
            } else if (starPredicate != null && otherStarPredicate != null) {
                return starPredicate.equalConstraint(otherStarPredicate);
            }
            return false;
        }

        private StarPredicate compoundPredicate() {
            StarPredicate predicate = null;
            for (Set<StarColumnPredicate> valueSet : valueSets) {
                StarPredicate orPredicate = null;
                for (StarColumnPredicate starColumnPredicate : valueSet) {
                    if (orPredicate == null) {
                        orPredicate = starColumnPredicate;
                    } else {
                        orPredicate = orPredicate.or(starColumnPredicate);
                    }
                }
                if (predicate == null) {
                    predicate = orPredicate;
                } else {
                    predicate = predicate.and(orPredicate);
                }
            }
            for (StarPredicate starPredicate
                : batchKey.getCompoundPredicateList())
            {
                if (predicate == null) {
                    predicate = starPredicate;
                } else {
                    predicate = predicate.and(starPredicate);
                }
            }
            return predicate;
        }

        boolean haveSameStarAndAggregation(Batch other) {
            boolean rollup[] = {false};
            boolean otherRollup[] = {false};

            boolean hasSameAggregation =
                getAgg(rollup) == other.getAgg(otherRollup);
            boolean hasSameRollupOption = rollup[0] == otherRollup[0];

            boolean hasSameStar = getStar().equals(other.getStar());
            return hasSameStar && hasSameAggregation && hasSameRollupOption;
        }

        /**
         * Returns whether this batch has the same closure columns as another.
         *
         * <p>Ensures that we do not group together a batch that includes a
         * level of a parent-child closure dimension with a batch that does not.
         * It is not safe to roll up from a parent-child closure level; due to
         * multiple accounting, the 'all' level is less than the sum of the
         * members of the closure level.
         *
         * @param other Other batch
         * @return Whether batches have the same closure columns
         */
        boolean haveSameClosureColumns(Batch other) {
            final BitKey cubeClosureColumnBitKey = cube.closureColumnBitKey;
            if (cubeClosureColumnBitKey == null) {
                // Virtual cubes have a null bitkey. For now, punt; should do
                // better.
                return true;
            }
            final BitKey closureColumns =
                this.batchKey.getConstrainedColumnsBitKey()
                    .and(cubeClosureColumnBitKey);
            final BitKey otherClosureColumns =
                other.batchKey.getConstrainedColumnsBitKey()
                    .and(cubeClosureColumnBitKey);
            return closureColumns.equals(otherClosureColumns);
        }

        /**
         * @param rollup Out parameter
         * @return AggStar
         */
        private AggStar getAgg(boolean[] rollup) {
            return aggMgr.findAgg(
                getStar(),
                getConstrainedColumnsBitKey(),
                makeMeasureBitKey(),
                rollup);
        }

        private BitKey makeMeasureBitKey() {
            BitKey bitKey = getConstrainedColumnsBitKey().emptyCopy();
            for (RolapStar.Measure measure : measuresList) {
                bitKey.set(measure.getBitPosition());
            }
            return bitKey;
        }

        /**
         * Return whether have same values for overlapping columns or
         * has all children for others.
         */
        boolean haveSameValues(
            Batch other)
        {
            for (int j = 0; j < columns.length; j++) {
                boolean isCommonColumn = false;
                for (int i = 0; i < other.columns.length; i++) {
                    if (areSameColumns(other.columns[i], columns[j])) {
                        if (hasSameValues(other.valueSets[i], valueSets[j])) {
                            isCommonColumn = true;
                            break;
                        } else {
                            return false;
                        }
                    }
                }
                if (!isCommonColumn
                    && !hasAllValues(columns[j], valueSets[j]))
                {
                    return false;
                }
            }
            return true;
        }

        private boolean hasAllValues(
            RolapStar.Column column,
            Set<StarColumnPredicate> valueSet)
        {
            return column.getCardinality() == valueSet.size();
        }

        private boolean areSameColumns(
            RolapStar.Column otherColumn,
            RolapStar.Column thisColumn)
        {
            return otherColumn.equals(thisColumn);
        }

        private boolean hasSameValues(
            Set<StarColumnPredicate> otherValueSet,
            Set<StarColumnPredicate> thisValueSet)
        {
            return otherValueSet.equals(thisValueSet);
        }
    }

    private static class CompositeBatchComparator
        implements Comparator<CompositeBatch>
    {
        static final CompositeBatchComparator instance =
            new CompositeBatchComparator();

        public int compare(CompositeBatch o1, CompositeBatch o2) {
            return BatchComparator.instance.compare(
                o1.detailedBatch,
                o2.detailedBatch);
        }
    }

    private static class BatchComparator implements Comparator<Batch> {
        static final BatchComparator instance = new BatchComparator();

        private BatchComparator() {
        }

        public int compare(
            Batch o1, Batch o2)
        {
            if (o1.columns.length != o2.columns.length) {
                return o1.columns.length - o2.columns.length;
            }
            for (int i = 0; i < o1.columns.length; i++) {
                int c = o1.columns[i].getName().compareTo(
                    o2.columns[i].getName());
                if (c != 0) {
                    return c;
                }
            }
            for (int i = 0; i < o1.columns.length; i++) {
                int c = compare(o1.valueSets[i], o2.valueSets[i]);
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        }

        <T> int compare(Set<T> set1, Set<T> set2) {
            if (set1.size() != set2.size()) {
                return set1.size() - set2.size();
            }
            Iterator<T> iter1 = set1.iterator();
            Iterator<T> iter2 = set2.iterator();
            while (iter1.hasNext()) {
                T v1 = iter1.next();
                T v2 = iter2.next();
                int c = Util.compareKey(v1, v2);
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        }
    }

    private static class ValueColumnConstraintComparator
        implements Comparator<ValueColumnPredicate>
    {
        static final ValueColumnConstraintComparator instance =
            new ValueColumnConstraintComparator();

        private ValueColumnConstraintComparator() {
        }

        public int compare(
            ValueColumnPredicate o1,
            ValueColumnPredicate o2)
        {
            Object v1 = o1.getValue();
            Object v2 = o2.getValue();
            if (v1.getClass() == v2.getClass()
                && v1 instanceof Comparable)
            {
                return ((Comparable) v1).compareTo(v2);
            } else {
                return v1.toString().compareTo(v2.toString());
            }
        }
    }
}

// End FastBatchingCellReader.java
