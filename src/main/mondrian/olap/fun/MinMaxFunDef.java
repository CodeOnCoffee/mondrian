/*
// $Id$
// This software is subject to the terms of the Common Public License
// Agreement, available at the following URL:
// http://www.opensource.org/licenses/cpl.html.
// Copyright (C) 2006-2006 Julian Hyde
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/
package mondrian.olap.fun;

import mondrian.olap.FunDef;
import mondrian.olap.Evaluator;
import mondrian.olap.Dimension;
import mondrian.calc.Calc;
import mondrian.calc.ExpCompiler;
import mondrian.calc.ListCalc;
import mondrian.calc.impl.ValueCalc;
import mondrian.calc.impl.AbstractCalc;
import mondrian.mdx.ResolvedFunCall;

import java.util.List;

/**
 * Definition of the <code>Min</code> and <code>Max</code> MDX functions.
 *
 * @author jhyde
 * @version $Id$
 * @since Mar 23, 2006
 */
class MinMaxFunDef extends AbstractAggregateFunDef {
    static final ReflectiveMultiResolver MinResolver = new ReflectiveMultiResolver(
            "Min",
            "Min(<Set>[, <Numeric Expression>])",
            "Returns the minimum value of a numeric expression evaluated over a set.",
            new String[]{"fnx", "fnxn"},
            MinMaxFunDef.class);

    static final MultiResolver MaxResolver = new ReflectiveMultiResolver(
            "Max",
            "Max(<Set>[, <Numeric Expression>])",
            "Returns the maximum value of a numeric expression evaluated over a set.",
            new String[]{"fnx", "fnxn"},
            MinMaxFunDef.class);

    private final boolean max;

    public MinMaxFunDef(FunDef dummyFunDef) {
        super(dummyFunDef);
        this.max = dummyFunDef.getName().equals("Max");
    }

    public Calc compileCall(ResolvedFunCall call, ExpCompiler compiler) {
        final ListCalc listCalc =
                compiler.compileList(call.getArg(0));
        final Calc calc = call.getArgCount() > 1 ?
                compiler.compileScalar(call.getArg(1), true) :
                new ValueCalc(call);
        return new AbstractCalc(call) {
            public Object evaluate(Evaluator evaluator) {
                List memberList = listCalc.evaluateList(evaluator);
                return max ?
                        max(evaluator.push(), memberList, calc) :
                        min(evaluator.push(), memberList, calc);
            }

            public Calc[] getCalcs() {
                return new Calc[] {listCalc, calc};
            }

            public boolean dependsOn(Dimension dimension) {
                return anyDependsButFirst(getCalcs(), dimension);
            }
        };
    }
}

// End MinMaxFunDef.java