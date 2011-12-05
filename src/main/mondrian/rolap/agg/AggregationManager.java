/*
// $Id$
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// Copyright (C) 2001-2002 Kana Software, Inc.
// Copyright (C) 2001-2011 Julian Hyde and others
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
//
// jhyde, 30 August, 2001
*/
package mondrian.rolap.agg;

import mondrian.olap.CacheControl;
import mondrian.olap.MondrianProperties;
import mondrian.olap.Util;
import mondrian.rolap.*;
import mondrian.rolap.SqlStatement.Type;
import mondrian.rolap.aggmatcher.AggStar;
import mondrian.rolap.cache.*;
import mondrian.server.Locus;
import mondrian.spi.SegmentCache;
import mondrian.util.Pair;

import org.apache.log4j.Logger;

import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.*;

/**
 * <code>RolapAggregationManager</code> manages all {@link Aggregation}s
 * in the system. It is a singleton class.
 *
 * @author jhyde
 * @since 30 August, 2001
 * @version $Id$
 */
public class AggregationManager extends RolapAggregationManager {

    private static final MondrianProperties properties =
        MondrianProperties.instance();

    private static final Logger LOGGER =
        Logger.getLogger(AggregationManager.class);

    public final List<SegmentCacheWorker> segmentCacheWorkers =
        new CopyOnWriteArrayList<SegmentCacheWorker>();

    public final SegmentCacheManager cacheMgr = new SegmentCacheManager();

    private static AggregationManager instance;

    // TODO: create using factory and/or configuration parameters. Executor
    //   should be shared within MondrianServer or target JDBC database.
    public final ExecutorService sqlExecutor =
        Util.getExecutorService(
            10, 0, 1, 10, "mondrian.rolap.agg.AggregationManager$sqlExecutor");

    /**
     * Returns or creates the singleton.
     *
     * @deprecated No longer a singleton, and will be removed in mondrian-4.
     *   Use {@link mondrian.olap.MondrianServer#getAggregationManager()}.
     *   To get a server, call
     *   {@link mondrian.olap.MondrianServer#forConnection(mondrian.olap.Connection)},
     *   passing in a null connection if you absolutely must.
     */
    public static synchronized AggregationManager instance() {
        if (instance == null) {
            instance = new AggregationManager();
        }
        return instance;
    }

    /**
     * Creates the AggregationManager.
     */
    public AggregationManager() {
        if (properties.EnableCacheHitCounters.get()) {
            LOGGER.error(
                "Property " + properties.EnableCacheHitCounters.getPath()
                + " is obsolete; ignored.");
        }
        // Add a local cache, if needed.
        if (!MondrianProperties.instance().DisableCaching.get()) {
            final MemorySegmentCache cache = new MemorySegmentCache();
            segmentCacheWorkers.add(
                new SegmentCacheWorker(cache));
        }
        // Add an external cache, if configured.
        final SegmentCache externalCache = SegmentCacheWorker.initCache();
        if (externalCache != null) {
            // Create a worker for this external cache
            segmentCacheWorkers.add(new SegmentCacheWorker(externalCache));
            // Hook up a listener so it can update
            // the segment index.
            externalCache.addListener(new AsyncCacheListener(this));
        }
    }

    /**
     * Returns the log4j logger.
     *
     * @return Logger
     */
    public final Logger getLogger() {
        return LOGGER;
    }

    /**
     * Called by FastBatchingCellReader.loadAggregation where the
     * RolapStar creates an Aggregation if needed.
     *
     * @param cellRequestCount Number of missed cells that led to this request
     * @param measures Measures to load
     * @param columns this is the CellRequest's constrained columns
     * @param aggregationKey this is the CellRequest's constraint key
     * @param predicates Array of constraints on each column
     * @param pinnedSegments Set of pinned segments
     * @param groupingSetsCollector grouping sets collector
     */
    public List<Future<SegmentWithData>> loadAggregation(
        int cellRequestCount,
        RolapStar.Measure[] measures,
        RolapStar.Column[] columns,
        AggregationKey aggregationKey,
        StarColumnPredicate[] predicates,
        PinSet pinnedSegments,
        GroupingSetsCollector groupingSetsCollector)
    {
        RolapStar star = measures[0].getStar();
        Aggregation aggregation =
            star.lookupOrCreateAggregation(aggregationKey);

        // try to eliminate unneccessary constraints
        // for Oracle: prevent an IN-clause with more than 1000 elements
        predicates = aggregation.optimizePredicates(columns, predicates);
        return aggregation.load(
            cellRequestCount, columns, measures, predicates, pinnedSegments,
            groupingSetsCollector);
    }

    /**
     * Returns an API with which to explicitly manage the contents of the cache.
     *
     * @param connection Server whose cache to control
     * @param pw Print writer, for tracing
     * @return CacheControl API
     */
    public CacheControl getCacheControl(
        RolapConnection connection,
        final PrintWriter pw)
    {
        return new CacheControlImpl(connection) {
            protected void flushNonUnion(final CellRegion region) {
              cacheMgr.flush(AggregationManager.this, this, region);
            }

            public void flush(final CellRegion region) {
                if (pw != null) {
                    pw.println("Cache state before flush:");
                    printCacheState(pw, region);
                    pw.println();
                }
                super.flush(region);
                if (pw != null) {
                    pw.println("Cache state after flush:");
                    printCacheState(pw, region);
                    pw.println();
                }
            }

            public void trace(final String message) {
                if (pw != null) {
                    pw.println(message);
                }
            }
        };
    }

    public Object getCellFromCache(CellRequest request) {
        return getCellFromCache(request, null);
    }

    public Object getCellFromCache(CellRequest request, PinSet pinSet) {
        // NOTE: This method used to check both local (thread/statment) cache
        // and global cache (segments in JVM, shared between statements). Now it
        // only looks in local cache. This can be done without acquiring any
        // locks, because the local cache is thread-local. If a segment that
        // matches this cell-request in global cache, a call to
        // SegmentCacheManager will copy it into local cache.
        final RolapStar.Measure measure = request.getMeasure();
        return measure.getStar().getCellFromCache(request, pinSet);
    }

    public Object getCellFromAllCaches(CellRequest request) {
        final RolapStar.Measure measure = request.getMeasure();
        return measure.getStar().getCellFromAllCaches(request);
    }

    public String getDrillThroughSql(
        final CellRequest request,
        final StarPredicate starPrediateSlicer,
        final boolean countOnly)
    {
        DrillThroughQuerySpec spec =
            new DrillThroughQuerySpec(
                request,
                starPrediateSlicer,
                countOnly);
        Pair<String, List<SqlStatement.Type>> pair = spec.generateSqlQuery();

        if (getLogger().isDebugEnabled()) {
            getLogger().debug(
                "DrillThroughSQL: "
                + pair.left
                + Util.nl);
        }

        return pair.left;
    }

    /**
     * Generates the query to retrieve the cells for a list of segments.
     * Called by Segment.load.
     *
     * @return A pair consisting of a SQL statement and a list of suggested
     *     types of columns
     */
    public Pair<String, List<SqlStatement.Type>> generateSql(
        GroupingSetsList groupingSetsList,
        List<StarPredicate> compoundPredicateList)
    {
        final RolapStar star = groupingSetsList.getStar();
        BitKey levelBitKey = groupingSetsList.getDefaultLevelBitKey();
        BitKey measureBitKey = groupingSetsList.getDefaultMeasureBitKey();

        // Check if using aggregates is enabled.
        boolean hasCompoundPredicates = false;
        if (compoundPredicateList != null && compoundPredicateList.size() > 0) {
            // Do not use Aggregate tables if compound predicates are present.
            hasCompoundPredicates = true;
        }
        if (MondrianProperties.instance().UseAggregates.get()
             && !hasCompoundPredicates)
        {
            final boolean[] rollup = {false};
            AggStar aggStar = findAgg(star, levelBitKey, measureBitKey, rollup);

            if (aggStar != null) {
                // Got a match, hot damn

                if (getLogger().isDebugEnabled()) {
                    StringBuilder buf = new StringBuilder(256);
                    buf.append("MATCH: ");
                    buf.append(star.getFactTable().getAlias());
                    buf.append(Util.nl);
                    buf.append("   foreign=");
                    buf.append(levelBitKey);
                    buf.append(Util.nl);
                    buf.append("   measure=");
                    buf.append(measureBitKey);
                    buf.append(Util.nl);
                    buf.append("   aggstar=");
                    buf.append(aggStar.getBitKey());
                    buf.append(Util.nl);
                    buf.append("AggStar=");
                    buf.append(aggStar.getFactTable().getName());
                    buf.append(Util.nl);
                    for (AggStar.Table.Column column
                        : aggStar.getFactTable().getColumns())
                    {
                        buf.append("   ");
                        buf.append(column);
                        buf.append(Util.nl);
                    }
                    getLogger().debug(buf.toString());
                }

                AggQuerySpec aggQuerySpec =
                    new AggQuerySpec(
                        aggStar, rollup[0], groupingSetsList);
                Pair<String, List<Type>> sql = aggQuerySpec.generateSqlQuery();

                if (getLogger().isDebugEnabled()) {
                    getLogger().debug(
                        "generateSqlQuery: sql="
                        + sql.left);
                }

                return sql;
            }

            // No match, fall through and use fact table.
        }

        if (getLogger().isDebugEnabled()) {
            StringBuilder sb = new StringBuilder();
            sb.append("NO MATCH : ");
            sb.append(star.getFactTable().getAlias());
            sb.append(Util.nl);
            sb.append("Foreign columns bit key=");
            sb.append(levelBitKey);
            sb.append(Util.nl);
            sb.append("Measure bit key=        ");
            sb.append(measureBitKey);
            sb.append(Util.nl);
            sb.append("Agg Stars=[");
            sb.append(Util.nl);
            for (AggStar aggStar : star.getAggStars()) {
                sb.append(aggStar.toString());
            }
            sb.append(Util.nl);
            sb.append("]");
            getLogger().debug(sb.toString());
        }


        // Fact table query
        SegmentArrayQuerySpec spec =
            new SegmentArrayQuerySpec(groupingSetsList, compoundPredicateList);

        Pair<String, List<SqlStatement.Type>> pair = spec.generateSqlQuery();

        if (getLogger().isDebugEnabled()) {
            getLogger().debug(
                "generateSqlQuery: sql=" + pair.left);
        }

        return pair;
    }

    /**
     * Finds an aggregate table in the given star which has the desired levels
     * and measures. Returns null if no aggregate table is suitable.
     *
     * <p>If there no aggregate is an exact match, returns a more
     * granular aggregate which can be rolled up, and sets rollup to true.
     * If one or more of the measures are distinct-count measures
     * rollup is possible only in limited circumstances.
     *
     * @param star Star
     * @param levelBitKey Set of levels
     * @param measureBitKey Set of measures
     * @param rollup Out parameter, is set to true if the aggregate is not
     *   an exact match
     * @return An aggregate, or null if none is suitable.
     */
    public AggStar findAgg(
        RolapStar star,
        final BitKey levelBitKey,
        final BitKey measureBitKey,
        boolean[] rollup)
    {
        // If there is no distinct count measure, isDistinct == false,
        // then all we want is an AggStar whose BitKey is a superset
        // of the combined measure BitKey and foreign-key/level BitKey.
        //
        // On the other hand, if there is at least one distinct count
        // measure, isDistinct == true, then what is wanted is an AggStar
        // whose measure BitKey is a superset of the measure BitKey,
        // whose level BitKey is an exact match and the aggregate table
        // can NOT have any foreign keys.
        assert rollup != null;
        BitKey fullBitKey = levelBitKey.or(measureBitKey);

        // The AggStars are already ordered from smallest to largest so
        // we need only find the first one and return it.
        for (AggStar aggStar : star.getAggStars()) {
            // superset match
            if (!aggStar.superSetMatch(fullBitKey)) {
                continue;
            }

            boolean isDistinct = measureBitKey.intersects(
                aggStar.getDistinctMeasureBitKey());

            // The AggStar has no "distinct count" measures so
            // we can use it without looking any further.
            if (!isDistinct) {
                rollup[0] = !aggStar.getLevelBitKey().equals(levelBitKey);
                return aggStar;
            }

            // If there are distinct measures, we can only rollup in limited
            // circumstances.

            // No foreign keys (except when its used as a distinct count
            //   measure).
            // Level key exact match.
            // Measure superset match.

            // Compute the core levels -- those which can be safely
            // rolled up to. For example,
            // if the measure is 'distinct customer count',
            // and the agg table has levels customer_id,
            // then gender is a core level.
            final BitKey distinctMeasuresBitKey =
                measureBitKey.and(aggStar.getDistinctMeasureBitKey());
            final BitSet distinctMeasures = distinctMeasuresBitKey.toBitSet();
            BitKey combinedLevelBitKey = null;
            for (int k = distinctMeasures.nextSetBit(0); k >= 0;
                k = distinctMeasures.nextSetBit(k + 1))
            {
                final AggStar.FactTable.Measure distinctMeasure =
                    aggStar.lookupMeasure(k);
                BitKey rollableLevelBitKey =
                    distinctMeasure.getRollableLevelBitKey();
                if (combinedLevelBitKey == null) {
                    combinedLevelBitKey = rollableLevelBitKey;
                } else {
                    // TODO use '&=' to remove unnecessary copy
                    combinedLevelBitKey =
                        combinedLevelBitKey.and(rollableLevelBitKey);
                }
            }

            if (aggStar.hasForeignKeys()) {
/*
                    StringBuilder buf = new StringBuilder(256);
                    buf.append("");
                    buf.append(star.getFactTable().getAlias());
                    buf.append(Util.nl);
                    buf.append("foreign =");
                    buf.append(levelBitKey);
                    buf.append(Util.nl);
                    buf.append("measure =");
                    buf.append(measureBitKey);
                    buf.append(Util.nl);
                    buf.append("aggstar =");
                    buf.append(aggStar.getBitKey());
                    buf.append(Util.nl);
                    buf.append("distinct=");
                    buf.append(aggStar.getDistinctMeasureBitKey());
                    buf.append(Util.nl);
                    buf.append("AggStar=");
                    buf.append(aggStar.getFactTable().getName());
                    buf.append(Util.nl);
                    for (Iterator columnIter =
                            aggStar.getFactTable().getColumns().iterator();
                         columnIter.hasNext();) {
                        AggStar.Table.Column column =
                                (AggStar.Table.Column) columnIter.next();
                        buf.append("   ");
                        buf.append(column);
                        buf.append(Util.nl);
                    }
System.out.println(buf.toString());
*/
                // This is a little pessimistic. If the measure is
                // 'count(distinct customer_id)' and one of the foreign keys is
                // 'customer_id' then it is OK to roll up.

                // Some of the measures in this query are distinct count.
                // Get all of the foreign key columns.
                // For each such measure, is it based upon a foreign key.
                // Are there any foreign keys left over. No, can use AggStar.
                BitKey fkBitKey = aggStar.getForeignKeyBitKey().copy();
                for (AggStar.FactTable.Measure measure
                    : aggStar.getFactTable().getMeasures())
                {
                    if (measure.isDistinct()) {
                        if (measureBitKey.get(measure.getBitPosition())) {
                            fkBitKey.clear(measure.getBitPosition());
                        }
                    }
                }
                if (!fkBitKey.isEmpty()) {
                    // there are foreign keys left so we can not use this
                    // AggStar.
                    continue;
                }
            }

            if (!aggStar.select(
                    levelBitKey, combinedLevelBitKey, measureBitKey))
            {
                continue;
            }

            rollup[0] = !aggStar.getLevelBitKey().equals(levelBitKey);
            return aggStar;
        }
        return null;
    }

    public PinSet createPinSet() {
        return new PinSetImpl();
    }

    public void shutdown() {
        // Send a shutdown command and wait for it to return.
        cacheMgr.shutdown();
        // Now we can cleanup.
        for (SegmentCacheWorker worker : segmentCacheWorkers) {
            worker.shutdown();
        }
        sqlExecutor.shutdown();
    }

    /**
     * Implementation of {@link mondrian.rolap.RolapAggregationManager.PinSet}
     * using a {@link HashSet}.
     */
    public static class PinSetImpl
        extends HashSet<Segment>
        implements RolapAggregationManager.PinSet
    {
    }

    /**
     * This is an implementation of SegmentCacheListener which updates the
     * segment index of its aggregation manager instance when it receives
     * events from its assigned SegmentCache implementation.
     */
    private static class AsyncCacheListener
        implements SegmentCache.SegmentCacheListener
    {
        private final AggregationManager aggMan;
        public AsyncCacheListener(AggregationManager aggMan) {
            this.aggMan = aggMan;
        }
        public void handle(final SegmentCacheEvent e) {
            final SegmentCacheManager.Command<Void> command;
            final Locus locus = Locus.peek();
            switch (e.getEventType()) {
            case ENTRY_CREATED:
                command =
                    new SegmentCacheManager.Command<Void>() {
                        public Void call() throws Exception {
                            Locus.push(locus);
                            try {
                                aggMan.cacheMgr
                                    .externalSegmentCreated(
                                        aggMan,
                                        e.getSource());
                                return null;
                            } finally {
                                Locus.pop(locus);
                            }
                        }
                    };
                break;
            case ENTRY_DELETED:
                command =
                    new SegmentCacheManager.Command<Void>() {
                        public Void call() throws Exception {
                            Locus.push(locus);
                            try {
                                aggMan.cacheMgr
                                    .externalSegmentDeleted(
                                        aggMan,
                                        e.getSource());
                                return null;
                            } finally {
                                Locus.pop(locus);
                            }
                        }
                    };
                break;
            default:
                throw new UnsupportedOperationException();
            }
            aggMan.cacheMgr.execute(command);
        }
    }
}

// End AggregationManager.java
