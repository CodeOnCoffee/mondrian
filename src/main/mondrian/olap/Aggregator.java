/*
// $Id$
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// Copyright (C) 2003-2011 Julian Hyde
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/
package mondrian.olap;

import mondrian.calc.Calc;
import mondrian.calc.TupleList;
import mondrian.spi.Dialect.Datatype;
import mondrian.spi.SegmentBody;

import java.util.List;

/**
 * Describes an aggregation operator, such as "sum" or "count".
 *
 * @see FunDef
 * @see Evaluator
 *
 * @author jhyde$
 * @since Jul 9, 2003$
 * @version $Id$
 */
public interface Aggregator {
    /**
     * Returns the aggregator used to combine sub-totals into a grand-total.
     */
    Aggregator getRollup();

    /**
     * Applies this aggregator to an expression over a set of members and
     * returns the result.
     *
     * @param evaluator Evaluation context
     * @param members List of members, not null
     * @param calc Expression to evaluate
     */
    Object aggregate(Evaluator evaluator, TupleList members, Calc calc);

    /**
     * Tells Mondrian if this aggregator can perform fast aggregation
     * using only the raw data of a given object type. This will
     * determine if Mondrian will attempt to perform in-memory rollups
     * on raw segment data by invoking {@link Aggregator#aggregate(Object[])}.
     *
     * <p>This is only invoked for rollup operations.
     *
     * @param datatype The datatype of the object we would like to rollup.
     * @return True or false, depending on the support status.
     */
    boolean supportsFastAggregates(Datatype datatype);

    /**
     * Applies this aggregator over a raw list of objects for a rollup
     * operation. This is useful when the values are already resolved
     * and we are dealing with a raw {@link SegmentBody} object.
     *
     * <p>Only gets called if {@link Aggregator#supportsFastAggregates()}
     * is true.
     *
     * <p>This is only invoked for rollup operations.
     *
     * @param rawData An array of values in its raw form, to be aggregated.
     * @return A rolled up value of the raw data.
     * if the object type is not supported.
     */
    Object aggregate(List<Object> rawData);
}

// End Aggregator.java
