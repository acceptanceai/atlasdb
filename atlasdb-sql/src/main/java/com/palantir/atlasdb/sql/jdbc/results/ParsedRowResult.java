package com.palantir.atlasdb.sql.jdbc.results;

import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.palantir.atlasdb.keyvalue.api.RowResult;
import com.palantir.atlasdb.table.description.ValueType;
import com.palantir.common.annotation.Output;

public class ParsedRowResult {

    private final List<JdbcColumnMetadataAndValue> result;
    private final Map<String, JdbcColumnMetadataAndValue> labelOrNameToResult;

    public static ParsedRowResult create(RowResult<byte[]> rawResult, List<JdbcColumnMetadata> columns) {
        if (columns.stream().anyMatch(JdbcColumnMetadata::isDynCol)) {
            throw new UnsupportedOperationException("dynamic columns are not currently supported");
        }

        ImmutableList.Builder<JdbcColumnMetadataAndValue> resultBuilder = ImmutableList.builder();
        parseRowComponents(rawResult.getRowName(),
                columns.stream().filter(JdbcColumnMetadata::isRowComp).collect(Collectors.toList()),
                resultBuilder);
        parseColumns(rawResult,
                columns.stream().filter(JdbcColumnMetadata::isCol).collect(Collectors.toList()),
                resultBuilder);
        List<JdbcColumnMetadataAndValue> colsMeta = resultBuilder.build();
        ImmutableMap.Builder<String, JdbcColumnMetadataAndValue> indexBuilder = ImmutableMap.builder();
        indexBuilder.putAll(colsMeta.stream().collect(Collectors.toMap(JdbcColumnMetadataAndValue::getName, Function.identity())));
        indexBuilder.putAll(colsMeta.stream()
                .filter(m -> !m.getLabel().equals(m.getName()))
                .collect(Collectors.toMap(JdbcColumnMetadataAndValue::getLabel, Function.identity())));
        return new ParsedRowResult(colsMeta, indexBuilder.build());
    }

    private static void parseColumns(RowResult<byte[]> rawResult,
                                     List<JdbcColumnMetadata> colsMeta,
                                     @Output ImmutableList.Builder<JdbcColumnMetadataAndValue> resultBuilder) {
        Map<ByteBuffer, byte[]> wrappedCols = Maps.newHashMap();
        for(Map.Entry<byte[], byte[]> entry : rawResult.getColumns().entrySet()) {
            wrappedCols.put(ByteBuffer.wrap(entry.getKey()), entry.getValue());
        }
        for (JdbcColumnMetadata meta : colsMeta) {
            Preconditions.checkState(meta.isCol(), "all metadata here is expected to be for columns");
            ByteBuffer shortName = ByteBuffer.wrap(meta.getName().getBytes());
            if (wrappedCols.containsKey(shortName)) {
                resultBuilder.add(JdbcColumnMetadataAndValue.create(meta, wrappedCols.get(shortName)));
            } else {
                resultBuilder.add(JdbcColumnMetadataAndValue.create(meta, null));  // put null for missing columns
            }
        }
    }

    private static void parseRowComponents(byte[] row,
                                           List<JdbcColumnMetadata> colsMeta,
                                           @Output ImmutableList.Builder<JdbcColumnMetadataAndValue> resultBuilder) {
        int index = 0;
        for (int i = 0; i < colsMeta.size(); i++) {
            JdbcColumnMetadata meta = colsMeta.get(i);
            Preconditions.checkState(meta.isRowComp(), "all metadata here is expected to be for rows components");

            ValueType type = meta.getValueType();
            Object val = type.convertToJava(row, index);
            int len = type.sizeOf(val);
            if (len == 0) {
                Preconditions.checkArgument(type == ValueType.STRING || type == ValueType.BLOB,
                        "only BLOB and STRING can have unknown length");
                Preconditions.checkArgument(i == colsMeta.size() - 1, "only terminal types can have unknown length");
                len = row.length - index;
            }
            byte[] rowBytes = Arrays.copyOfRange(row, index, index + len);
            index += len;
            resultBuilder.add(new JdbcColumnMetadataAndValue(meta, rowBytes));
        }
    }

    private ParsedRowResult(List<JdbcColumnMetadataAndValue> result, Map<String, JdbcColumnMetadataAndValue> labelToResult) {
        this.result = result;
        this.labelOrNameToResult = labelToResult;
    }

    private Object get(JdbcColumnMetadataAndValue res, JdbcReturnType returnType) {
        return ResultDeserializers.convert(res, returnType);
    }

    public Object get(int index, JdbcReturnType returnType) throws SQLException {
        if (index > result.size()) {
            throw new SQLException(String.format("given column index %s, but there are only %s columns", index, result.size()));
        }
        return get(result.get(index - 1), returnType);
    }

    public Object get(String col, JdbcReturnType returnType) throws SQLException {
        if (!labelOrNameToResult.containsKey(col)) {
            throw new SQLException(String.format("column '%s' is not found in results", col));
        }
        return get(labelOrNameToResult.get(col), returnType);
    }

    public int getIndexFromColumnLabel(String col) throws SQLException {
        if (!labelOrNameToResult.containsKey(col)) {
            throw new SQLException(String.format("column '%s' is not found in results", col));
        }
        return result.indexOf(labelOrNameToResult.get(col)) + 1;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("result", result)
                .add("labelOrNameToResult", labelOrNameToResult)
                .toString();
    }
}
