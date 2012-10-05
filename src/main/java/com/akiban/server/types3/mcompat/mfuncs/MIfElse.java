/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.server.types3.mcompat.mfuncs;

import com.akiban.server.types3.LazyList;
import com.akiban.server.types3.TClass;
import com.akiban.server.types3.TExecutionContext;
import com.akiban.server.types3.TOverloadResult;
import com.akiban.server.types3.TPreptimeValue;
import com.akiban.server.types3.TScalar;
import com.akiban.server.types3.aksql.aktypes.AkBool;
import com.akiban.server.types3.mcompat.mtypes.MApproximateNumber;
import com.akiban.server.types3.mcompat.mtypes.MString;
import com.akiban.server.types3.pvalue.PValueSource;
import com.akiban.server.types3.pvalue.PValueTarget;
import com.akiban.server.types3.pvalue.PValueTargets;
import com.akiban.server.types3.texpressions.Constantness;
import com.akiban.server.types3.texpressions.TInputSetBuilder;
import com.akiban.server.types3.texpressions.TScalarBase;

public final class MIfElse extends TScalarBase {

    public static final TScalar[] overloads = new TScalar[] {
            new MIfElse(MString.LONGTEXT, ExactInput.LEFT, -10),
            new MIfElse(MString.LONGTEXT, ExactInput.RIGHT, -9),
            new MIfElse(MString.VARCHAR, ExactInput.LEFT, -8),
            new MIfElse(MString.VARCHAR, ExactInput.RIGHT, -7),
            new MIfElse(MApproximateNumber.DOUBLE, ExactInput.LEFT, -6),
            new MIfElse(MApproximateNumber.DOUBLE, ExactInput.RIGHT, -5),
            new MIfElse(null, ExactInput.BOTH, -4),
            new MIfElse(MString.VARCHAR, ExactInput.NEITHER, -3),
    };

    @Override
    protected void buildInputSets(TInputSetBuilder builder) {
        builder.covers(AkBool.INSTANCE, 0).pickingCovers(targetClass, 1, 2);
        if(exactInput == ExactInput.BOTH || exactInput == ExactInput.LEFT)
            builder.setExact(1, true);
        if(exactInput == ExactInput.BOTH || exactInput == ExactInput.RIGHT)
            builder.setExact(2, true);
    }

    @Override
    protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output) {
        int whichSource = inputs.get(0).getBoolean() ? 1 : 2;
        PValueSource source = inputs.get(whichSource);
        PValueTargets.copyFrom(source, output);
    }

    @Override
    public String displayName() {
        return "IF";
    }

    @Override
    public TOverloadResult resultType() {
        return TOverloadResult.picking();
    }

    @Override
    protected boolean nullContaminates(int inputIndex) {
        return (inputIndex == 1);
    }

    @Override
    public int[] getPriorities() {
        return new int[] { priority };
    }

    @Override
    protected Constantness constness(int inputIndex, LazyList<? extends TPreptimeValue> values) {
        assert inputIndex == 0 : inputIndex; // should be fully resolved after the first call
        PValueSource condition = values.get(0).value();
        if (condition == null)
            return Constantness.NOT_CONST;
        int result = condition.getBoolean() ? 1 : 2;
        return values.get(result).value() == null ? Constantness.NOT_CONST : Constantness.CONST;
    }

    private MIfElse(TClass targetClass, ExactInput exactInput, int priority) {
        this.targetClass = targetClass;
        this.exactInput = exactInput;
        this.priority = priority;
    }

    private final TClass targetClass;
    private final ExactInput exactInput;
    private final int priority;

    private enum ExactInput {
        LEFT, RIGHT, NEITHER, BOTH
    }
}
