/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.operator.aggregation;

import com.google.common.collect.ImmutableList;
import io.airlift.bytecode.DynamicClassLoader;
import io.trino.annotation.UsedByGeneratedCode;
import io.trino.metadata.FunctionArgumentDefinition;
import io.trino.metadata.FunctionBinding;
import io.trino.metadata.FunctionDependencies;
import io.trino.metadata.FunctionDependencyDeclaration;
import io.trino.metadata.FunctionMetadata;
import io.trino.metadata.Signature;
import io.trino.metadata.SqlAggregationFunction;
import io.trino.operator.aggregation.AggregationMetadata.AccumulatorStateDescriptor;
import io.trino.operator.aggregation.state.BlockPositionState;
import io.trino.operator.aggregation.state.BlockPositionStateSerializer;
import io.trino.operator.aggregation.state.NullableBooleanState;
import io.trino.operator.aggregation.state.NullableDoubleState;
import io.trino.operator.aggregation.state.NullableLongState;
import io.trino.operator.aggregation.state.StateCompiler;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.function.AccumulatorState;
import io.trino.spi.function.AccumulatorStateFactory;
import io.trino.spi.function.AccumulatorStateSerializer;
import io.trino.spi.function.InvocationConvention;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeSignature;

import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.metadata.FunctionKind.AGGREGATE;
import static io.trino.metadata.Signature.orderableTypeParameter;
import static io.trino.operator.aggregation.AggregationMetadata.ParameterMetadata;
import static io.trino.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.BLOCK_INDEX;
import static io.trino.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.BLOCK_INPUT_CHANNEL;
import static io.trino.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.INPUT_CHANNEL;
import static io.trino.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.STATE;
import static io.trino.operator.aggregation.AggregationUtils.generateAggregationName;
import static io.trino.spi.function.InvocationConvention.InvocationArgumentConvention.BLOCK_POSITION;
import static io.trino.spi.function.InvocationConvention.InvocationArgumentConvention.NEVER_NULL;
import static io.trino.spi.function.InvocationConvention.InvocationReturnConvention.FAIL_ON_NULL;
import static io.trino.spi.function.InvocationConvention.simpleConvention;
import static io.trino.spi.function.OperatorType.COMPARISON;
import static io.trino.util.Failures.internalError;
import static io.trino.util.MinMaxCompare.getMinMaxCompare;
import static io.trino.util.Reflection.methodHandle;

public abstract class AbstractMinMaxAggregationFunction
        extends SqlAggregationFunction
{
    private static final MethodHandle LONG_INPUT_FUNCTION = methodHandle(AbstractMinMaxAggregationFunction.class, "input", MethodHandle.class, NullableLongState.class, long.class);
    private static final MethodHandle DOUBLE_INPUT_FUNCTION = methodHandle(AbstractMinMaxAggregationFunction.class, "input", MethodHandle.class, NullableDoubleState.class, double.class);
    private static final MethodHandle BOOLEAN_INPUT_FUNCTION = methodHandle(AbstractMinMaxAggregationFunction.class, "input", MethodHandle.class, NullableBooleanState.class, boolean.class);
    private static final MethodHandle BLOCK_POSITION_INPUT_FUNCTION = methodHandle(AbstractMinMaxAggregationFunction.class, "input", MethodHandle.class, BlockPositionState.class, Block.class, int.class);

    private static final MethodHandle LONG_OUTPUT_FUNCTION = methodHandle(NullableLongState.class, "write", Type.class, NullableLongState.class, BlockBuilder.class);
    private static final MethodHandle DOUBLE_OUTPUT_FUNCTION = methodHandle(NullableDoubleState.class, "write", Type.class, NullableDoubleState.class, BlockBuilder.class);
    private static final MethodHandle BOOLEAN_OUTPUT_FUNCTION = methodHandle(NullableBooleanState.class, "write", Type.class, NullableBooleanState.class, BlockBuilder.class);
    private static final MethodHandle BLOCK_POSITION_OUTPUT_FUNCTION = methodHandle(BlockPositionState.class, "write", Type.class, BlockPositionState.class, BlockBuilder.class);

    private static final MethodHandle LONG_COMBINE_FUNCTION = methodHandle(AbstractMinMaxAggregationFunction.class, "combine", MethodHandle.class, NullableLongState.class, NullableLongState.class);
    private static final MethodHandle DOUBLE_COMBINE_FUNCTION = methodHandle(AbstractMinMaxAggregationFunction.class, "combine", MethodHandle.class, NullableDoubleState.class, NullableDoubleState.class);
    private static final MethodHandle BOOLEAN_COMBINE_FUNCTION = methodHandle(AbstractMinMaxAggregationFunction.class, "combine", MethodHandle.class, NullableBooleanState.class, NullableBooleanState.class);
    private static final MethodHandle BLOCK_POSITION_COMBINE_FUNCTION = methodHandle(AbstractMinMaxAggregationFunction.class, "combine", MethodHandle.class, BlockPositionState.class, BlockPositionState.class);

    private final boolean min;

    protected AbstractMinMaxAggregationFunction(String name, boolean min, String description)
    {
        super(
                new FunctionMetadata(
                        new Signature(
                                name,
                                ImmutableList.of(orderableTypeParameter("E")),
                                ImmutableList.of(),
                                new TypeSignature("E"),
                                ImmutableList.of(new TypeSignature("E")),
                                false),
                        true,
                        ImmutableList.of(new FunctionArgumentDefinition(false)),
                        false,
                        true,
                        description,
                        AGGREGATE),
                true,
                false);
        this.min = min;
    }

    @Override
    public FunctionDependencyDeclaration getFunctionDependencies()
    {
        return FunctionDependencyDeclaration.builder()
                .addOperatorSignature(COMPARISON, ImmutableList.of(new TypeSignature("E"), new TypeSignature("E")))
                .build();
    }

    @Override
    public List<TypeSignature> getIntermediateTypes(FunctionBinding functionBinding)
    {
        Type type = functionBinding.getTypeVariable("E");
        if (type.getJavaType() == long.class) {
            return ImmutableList.of(StateCompiler.getSerializedType(NullableLongState.class).getTypeSignature());
        }
        if (type.getJavaType() == double.class) {
            return ImmutableList.of(StateCompiler.getSerializedType(NullableDoubleState.class).getTypeSignature());
        }
        if (type.getJavaType() == boolean.class) {
            return ImmutableList.of(StateCompiler.getSerializedType(NullableBooleanState.class).getTypeSignature());
        }
        // native container type is Slice or Block
        return ImmutableList.of(new BlockPositionStateSerializer(type).getSerializedType().getTypeSignature());
    }

    @Override
    public InternalAggregationFunction specialize(FunctionBinding functionBinding, FunctionDependencies functionDependencies)
    {
        Type type = functionBinding.getTypeVariable("E");
        InvocationConvention invocationConvention;
        if (type.getJavaType().isPrimitive()) {
            invocationConvention = simpleConvention(FAIL_ON_NULL, NEVER_NULL, NEVER_NULL);
        }
        else {
            invocationConvention = simpleConvention(FAIL_ON_NULL, BLOCK_POSITION, BLOCK_POSITION);
        }

        MethodHandle compareMethodHandle = getMinMaxCompare(functionDependencies, type, invocationConvention, min);

        return generateAggregation(type, compareMethodHandle);
    }

    protected InternalAggregationFunction generateAggregation(Type type, MethodHandle compareMethodHandle)
    {
        DynamicClassLoader classLoader = new DynamicClassLoader(AbstractMinMaxAggregationFunction.class.getClassLoader());

        List<Type> inputTypes = ImmutableList.of(type);

        MethodHandle inputFunction;
        MethodHandle combineFunction;
        MethodHandle outputFunction;
        Class<? extends AccumulatorState> stateInterface;
        AccumulatorStateSerializer<?> stateSerializer;

        if (type.getJavaType() == long.class) {
            stateInterface = NullableLongState.class;
            stateSerializer = StateCompiler.generateStateSerializer(stateInterface, classLoader);
            inputFunction = LONG_INPUT_FUNCTION.bindTo(compareMethodHandle);
            combineFunction = LONG_COMBINE_FUNCTION.bindTo(compareMethodHandle);
            outputFunction = LONG_OUTPUT_FUNCTION.bindTo(type);
        }
        else if (type.getJavaType() == double.class) {
            stateInterface = NullableDoubleState.class;
            stateSerializer = StateCompiler.generateStateSerializer(stateInterface, classLoader);
            inputFunction = DOUBLE_INPUT_FUNCTION.bindTo(compareMethodHandle);
            combineFunction = DOUBLE_COMBINE_FUNCTION.bindTo(compareMethodHandle);
            outputFunction = DOUBLE_OUTPUT_FUNCTION.bindTo(type);
        }
        else if (type.getJavaType() == boolean.class) {
            stateInterface = NullableBooleanState.class;
            stateSerializer = StateCompiler.generateStateSerializer(stateInterface, classLoader);
            inputFunction = BOOLEAN_INPUT_FUNCTION.bindTo(compareMethodHandle);
            combineFunction = BOOLEAN_COMBINE_FUNCTION.bindTo(compareMethodHandle);
            outputFunction = BOOLEAN_OUTPUT_FUNCTION.bindTo(type);
        }
        else {
            // native container type is Slice or Block
            stateInterface = BlockPositionState.class;
            stateSerializer = new BlockPositionStateSerializer(type);
            inputFunction = BLOCK_POSITION_INPUT_FUNCTION.bindTo(compareMethodHandle);
            combineFunction = BLOCK_POSITION_COMBINE_FUNCTION.bindTo(compareMethodHandle);
            outputFunction = BLOCK_POSITION_OUTPUT_FUNCTION.bindTo(type);
        }

        AccumulatorStateFactory<?> stateFactory = StateCompiler.generateStateFactory(stateInterface, classLoader);

        Type intermediateType = stateSerializer.getSerializedType();
        String name = getFunctionMetadata().getSignature().getName();
        AggregationMetadata metadata = new AggregationMetadata(
                generateAggregationName(name, type.getTypeSignature(), inputTypes.stream().map(Type::getTypeSignature).collect(toImmutableList())),
                createParameterMetadata(type),
                inputFunction,
                Optional.empty(),
                combineFunction,
                outputFunction,
                ImmutableList.of(new AccumulatorStateDescriptor(
                        stateInterface,
                        stateSerializer,
                        stateFactory)),
                type);

        GenericAccumulatorFactoryBinder factory = AccumulatorCompiler.generateAccumulatorFactoryBinder(metadata, classLoader);
        return new InternalAggregationFunction(name, inputTypes, ImmutableList.of(intermediateType), type, factory);
    }

    private static List<ParameterMetadata> createParameterMetadata(Type type)
    {
        if (type.getJavaType().isPrimitive()) {
            return ImmutableList.of(
                    new ParameterMetadata(STATE),
                    new ParameterMetadata(INPUT_CHANNEL, type));
        }
        else {
            return ImmutableList.of(
                    new ParameterMetadata(STATE),
                    new ParameterMetadata(BLOCK_INPUT_CHANNEL, type),
                    new ParameterMetadata(BLOCK_INDEX));
        }
    }

    @UsedByGeneratedCode
    public static void input(MethodHandle methodHandle, NullableDoubleState state, double value)
    {
        compareAndUpdateState(methodHandle, state, value);
    }

    @UsedByGeneratedCode
    public static void input(MethodHandle methodHandle, NullableLongState state, long value)
    {
        compareAndUpdateState(methodHandle, state, value);
    }

    @UsedByGeneratedCode
    public static void input(MethodHandle methodHandle, NullableBooleanState state, boolean value)
    {
        compareAndUpdateState(methodHandle, state, value);
    }

    @UsedByGeneratedCode
    public static void input(MethodHandle methodHandle, BlockPositionState state, Block block, int position)
    {
        compareAndUpdateState(methodHandle, state, block, position);
    }

    @UsedByGeneratedCode
    public static void combine(MethodHandle methodHandle, NullableLongState state, NullableLongState otherState)
    {
        compareAndUpdateState(methodHandle, state, otherState.getLong());
    }

    @UsedByGeneratedCode
    public static void combine(MethodHandle methodHandle, NullableDoubleState state, NullableDoubleState otherState)
    {
        compareAndUpdateState(methodHandle, state, otherState.getDouble());
    }

    @UsedByGeneratedCode
    public static void combine(MethodHandle methodHandle, NullableBooleanState state, NullableBooleanState otherState)
    {
        compareAndUpdateState(methodHandle, state, otherState.getBoolean());
    }

    @UsedByGeneratedCode
    public static void combine(MethodHandle methodHandle, BlockPositionState state, BlockPositionState otherState)
    {
        compareAndUpdateState(methodHandle, state, otherState.getBlock(), otherState.getPosition());
    }

    private static void compareAndUpdateState(MethodHandle methodHandle, NullableLongState state, long value)
    {
        if (state.isNull()) {
            state.setNull(false);
            state.setLong(value);
            return;
        }
        try {
            if ((boolean) methodHandle.invokeExact(value, state.getLong())) {
                state.setLong(value);
            }
        }
        catch (Throwable t) {
            throw internalError(t);
        }
    }

    private static void compareAndUpdateState(MethodHandle methodHandle, NullableDoubleState state, double value)
    {
        if (state.isNull()) {
            state.setNull(false);
            state.setDouble(value);
            return;
        }
        try {
            if ((boolean) methodHandle.invokeExact(value, state.getDouble())) {
                state.setDouble(value);
            }
        }
        catch (Throwable t) {
            throw internalError(t);
        }
    }

    private static void compareAndUpdateState(MethodHandle methodHandle, NullableBooleanState state, boolean value)
    {
        if (state.isNull()) {
            state.setNull(false);
            state.setBoolean(value);
            return;
        }
        try {
            if ((boolean) methodHandle.invokeExact(value, state.getBoolean())) {
                state.setBoolean(value);
            }
        }
        catch (Throwable t) {
            throw internalError(t);
        }
    }

    private static void compareAndUpdateState(MethodHandle methodHandle, BlockPositionState state, Block block, int position)
    {
        if (state.getBlock() == null) {
            state.setBlock(block);
            state.setPosition(position);
            return;
        }
        try {
            if ((boolean) methodHandle.invokeExact(block, position, state.getBlock(), state.getPosition())) {
                state.setBlock(block);
                state.setPosition(position);
            }
        }
        catch (Throwable t) {
            throw internalError(t);
        }
    }
}
