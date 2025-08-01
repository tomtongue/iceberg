/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.spark.data;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.Schema;
import org.apache.iceberg.parquet.ParquetSchemaUtil;
import org.apache.iceberg.parquet.ParquetValueReader;
import org.apache.iceberg.parquet.ParquetValueReaders;
import org.apache.iceberg.parquet.ParquetValueReaders.FloatAsDoubleReader;
import org.apache.iceberg.parquet.ParquetValueReaders.IntAsLongReader;
import org.apache.iceberg.parquet.ParquetValueReaders.PrimitiveReader;
import org.apache.iceberg.parquet.ParquetValueReaders.RepeatedKeyValueReader;
import org.apache.iceberg.parquet.ParquetValueReaders.RepeatedReader;
import org.apache.iceberg.parquet.ParquetValueReaders.ReusableEntry;
import org.apache.iceberg.parquet.ParquetValueReaders.StructReader;
import org.apache.iceberg.parquet.ParquetValueReaders.UnboxedReader;
import org.apache.iceberg.parquet.ParquetVariantReaders.DelegatingValueReader;
import org.apache.iceberg.parquet.ParquetVariantVisitor;
import org.apache.iceberg.parquet.TypeWithSchemaVisitor;
import org.apache.iceberg.parquet.VariantReaderBuilder;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.spark.SparkUtil;
import org.apache.iceberg.types.Type.TypeID;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.UUIDUtil;
import org.apache.iceberg.variants.Variant;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation.DecimalLogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.catalyst.util.ArrayBasedMapData;
import org.apache.spark.sql.catalyst.util.ArrayData;
import org.apache.spark.sql.catalyst.util.GenericArrayData;
import org.apache.spark.sql.catalyst.util.MapData;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.unsafe.types.CalendarInterval;
import org.apache.spark.unsafe.types.UTF8String;
import org.apache.spark.unsafe.types.VariantVal;

public class SparkParquetReaders {
  private SparkParquetReaders() {}

  public static ParquetValueReader<InternalRow> buildReader(
      Schema expectedSchema, MessageType fileSchema) {
    return buildReader(expectedSchema, fileSchema, ImmutableMap.of());
  }

  @SuppressWarnings("unchecked")
  public static ParquetValueReader<InternalRow> buildReader(
      Schema expectedSchema, MessageType fileSchema, Map<Integer, ?> idToConstant) {
    if (ParquetSchemaUtil.hasIds(fileSchema)) {
      return (ParquetValueReader<InternalRow>)
          TypeWithSchemaVisitor.visit(
              expectedSchema.asStruct(), fileSchema, new ReadBuilder(fileSchema, idToConstant));
    } else {
      return (ParquetValueReader<InternalRow>)
          TypeWithSchemaVisitor.visit(
              expectedSchema.asStruct(),
              fileSchema,
              new FallbackReadBuilder(fileSchema, idToConstant));
    }
  }

  private static class FallbackReadBuilder extends ReadBuilder {
    FallbackReadBuilder(MessageType type, Map<Integer, ?> idToConstant) {
      super(type, idToConstant);
    }

    @Override
    public ParquetValueReader<?> message(
        Types.StructType expected, MessageType message, List<ParquetValueReader<?>> fieldReaders) {
      // the top level matches by ID, but the remaining IDs are missing
      return super.struct(expected, message, fieldReaders);
    }

    @Override
    public ParquetValueReader<?> struct(
        Types.StructType ignored, GroupType struct, List<ParquetValueReader<?>> fieldReaders) {
      // the expected struct is ignored because nested fields are never found when the
      List<ParquetValueReader<?>> newFields =
          Lists.newArrayListWithExpectedSize(fieldReaders.size());
      List<Type> fields = struct.getFields();
      for (int i = 0; i < fields.size(); i += 1) {
        Type fieldType = fields.get(i);
        int fieldD = type().getMaxDefinitionLevel(path(fieldType.getName())) - 1;
        newFields.add(ParquetValueReaders.option(fieldType, fieldD, fieldReaders.get(i)));
      }

      return new InternalRowReader(newFields);
    }
  }

  private static class ReadBuilder extends TypeWithSchemaVisitor<ParquetValueReader<?>> {
    private final MessageType type;
    private final Map<Integer, ?> idToConstant;

    ReadBuilder(MessageType type, Map<Integer, ?> idToConstant) {
      this.type = type;
      this.idToConstant = idToConstant;
    }

    @Override
    public ParquetValueReader<?> message(
        Types.StructType expected, MessageType message, List<ParquetValueReader<?>> fieldReaders) {
      return struct(expected, message.asGroupType(), fieldReaders);
    }

    @Override
    public ParquetValueReader<?> struct(
        Types.StructType expected, GroupType struct, List<ParquetValueReader<?>> fieldReaders) {
      if (null == expected) {
        return new InternalRowReader(ImmutableList.of());
      }

      // match the expected struct's order
      Map<Integer, ParquetValueReader<?>> readersById = Maps.newHashMap();
      List<Type> fields = struct.getFields();
      for (int i = 0; i < fields.size(); i += 1) {
        Type fieldType = fields.get(i);
        int fieldD = type.getMaxDefinitionLevel(path(fieldType.getName())) - 1;
        if (fieldType.getId() != null) {
          int id = fieldType.getId().intValue();
          readersById.put(id, ParquetValueReaders.option(fieldType, fieldD, fieldReaders.get(i)));
        }
      }

      int constantDefinitionLevel = type.getMaxDefinitionLevel(currentPath());
      List<Types.NestedField> expectedFields = expected.fields();
      List<ParquetValueReader<?>> reorderedFields =
          Lists.newArrayListWithExpectedSize(expectedFields.size());

      for (Types.NestedField field : expectedFields) {
        int id = field.fieldId();
        ParquetValueReader<?> reader =
            ParquetValueReaders.replaceWithMetadataReader(
                id, readersById.get(id), idToConstant, constantDefinitionLevel);
        reorderedFields.add(defaultReader(field, reader, constantDefinitionLevel));
      }

      return new InternalRowReader(reorderedFields);
    }

    private ParquetValueReader<?> defaultReader(
        Types.NestedField field, ParquetValueReader<?> reader, int constantDL) {
      if (reader != null) {
        return reader;
      } else if (field.initialDefault() != null) {
        return ParquetValueReaders.constant(
            SparkUtil.internalToSpark(field.type(), field.initialDefault()), constantDL);
      } else if (field.isOptional()) {
        return ParquetValueReaders.nulls();
      }

      throw new IllegalArgumentException(String.format("Missing required field: %s", field.name()));
    }

    @Override
    public ParquetValueReader<?> list(
        Types.ListType expectedList, GroupType array, ParquetValueReader<?> elementReader) {
      String[] repeatedPath = currentPath();

      int repeatedD = type.getMaxDefinitionLevel(repeatedPath) - 1;
      int repeatedR = type.getMaxRepetitionLevel(repeatedPath) - 1;

      Type elementType = ParquetSchemaUtil.determineListElementType(array);
      int elementD = type.getMaxDefinitionLevel(path(elementType.getName())) - 1;

      return new ArrayReader<>(
          repeatedD, repeatedR, ParquetValueReaders.option(elementType, elementD, elementReader));
    }

    @Override
    public ParquetValueReader<?> map(
        Types.MapType expectedMap,
        GroupType map,
        ParquetValueReader<?> keyReader,
        ParquetValueReader<?> valueReader) {
      GroupType repeatedKeyValue = map.getFields().get(0).asGroupType();
      String[] repeatedPath = currentPath();

      int repeatedD = type.getMaxDefinitionLevel(repeatedPath) - 1;
      int repeatedR = type.getMaxRepetitionLevel(repeatedPath) - 1;

      Type keyType = repeatedKeyValue.getType(0);
      int keyD = type.getMaxDefinitionLevel(path(keyType.getName())) - 1;
      Type valueType = repeatedKeyValue.getType(1);
      int valueD = type.getMaxDefinitionLevel(path(valueType.getName())) - 1;

      return new MapReader<>(
          repeatedD,
          repeatedR,
          ParquetValueReaders.option(keyType, keyD, keyReader),
          ParquetValueReaders.option(valueType, valueD, valueReader));
    }

    @Override
    public ParquetVariantVisitor<ParquetValueReader<?>> variantVisitor() {
      return new VariantReaderBuilder(type, Arrays.asList(currentPath()));
    }

    @Override
    public ParquetValueReader<?> variant(
        Types.VariantType iVariant, GroupType variant, ParquetValueReader<?> variantReader) {
      return new VariantReader(variantReader);
    }

    @Override
    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    public ParquetValueReader<?> primitive(
        org.apache.iceberg.types.Type.PrimitiveType expected, PrimitiveType primitive) {
      ColumnDescriptor desc = type.getColumnDescription(currentPath());

      if (primitive.getOriginalType() != null) {
        switch (primitive.getOriginalType()) {
          case ENUM:
          case JSON:
          case UTF8:
            return new StringReader(desc);
          case INT_8:
          case INT_16:
          case INT_32:
            if (expected != null && expected.typeId() == Types.LongType.get().typeId()) {
              return new IntAsLongReader(desc);
            } else {
              return new UnboxedReader<>(desc);
            }
          case DATE:
          case INT_64:
            return new UnboxedReader<>(desc);
          case TIMESTAMP_MICROS:
          case TIMESTAMP_MILLIS:
            return ParquetValueReaders.timestamps(desc);
          case DECIMAL:
            DecimalLogicalTypeAnnotation decimal =
                (DecimalLogicalTypeAnnotation) primitive.getLogicalTypeAnnotation();
            switch (primitive.getPrimitiveTypeName()) {
              case BINARY:
              case FIXED_LEN_BYTE_ARRAY:
                return new BinaryDecimalReader(desc, decimal.getScale());
              case INT64:
                return new LongDecimalReader(desc, decimal.getPrecision(), decimal.getScale());
              case INT32:
                return new IntegerDecimalReader(desc, decimal.getPrecision(), decimal.getScale());
              default:
                throw new UnsupportedOperationException(
                    "Unsupported base type for decimal: " + primitive.getPrimitiveTypeName());
            }
          case BSON:
            return new ParquetValueReaders.ByteArrayReader(desc);
          default:
            throw new UnsupportedOperationException(
                "Unsupported logical type: " + primitive.getOriginalType());
        }
      }

      switch (primitive.getPrimitiveTypeName()) {
        case FIXED_LEN_BYTE_ARRAY:
        case BINARY:
          if (expected != null && expected.typeId() == TypeID.UUID) {
            return new UUIDReader(desc);
          }
          return new ParquetValueReaders.ByteArrayReader(desc);
        case INT32:
          if (expected != null && expected.typeId() == TypeID.LONG) {
            return new IntAsLongReader(desc);
          } else {
            return new UnboxedReader<>(desc);
          }
        case FLOAT:
          if (expected != null && expected.typeId() == TypeID.DOUBLE) {
            return new FloatAsDoubleReader(desc);
          } else {
            return new UnboxedReader<>(desc);
          }
        case BOOLEAN:
        case INT64:
        case DOUBLE:
          return new UnboxedReader<>(desc);
        case INT96:
          // Impala & Spark used to write timestamps as INT96 without a logical type. For backwards
          // compatibility we try to read INT96 as timestamps.
          return ParquetValueReaders.int96Timestamps(desc);
        default:
          throw new UnsupportedOperationException("Unsupported type: " + primitive);
      }
    }

    protected MessageType type() {
      return type;
    }
  }

  private static class BinaryDecimalReader extends PrimitiveReader<Decimal> {
    private final int scale;

    BinaryDecimalReader(ColumnDescriptor desc, int scale) {
      super(desc);
      this.scale = scale;
    }

    @Override
    public Decimal read(Decimal ignored) {
      Binary binary = column.nextBinary();
      return Decimal.fromDecimal(new BigDecimal(new BigInteger(binary.getBytes()), scale));
    }
  }

  private static class IntegerDecimalReader extends PrimitiveReader<Decimal> {
    private final int precision;
    private final int scale;

    IntegerDecimalReader(ColumnDescriptor desc, int precision, int scale) {
      super(desc);
      this.precision = precision;
      this.scale = scale;
    }

    @Override
    public Decimal read(Decimal ignored) {
      return Decimal.apply(column.nextInteger(), precision, scale);
    }
  }

  private static class LongDecimalReader extends PrimitiveReader<Decimal> {
    private final int precision;
    private final int scale;

    LongDecimalReader(ColumnDescriptor desc, int precision, int scale) {
      super(desc);
      this.precision = precision;
      this.scale = scale;
    }

    @Override
    public Decimal read(Decimal ignored) {
      return Decimal.apply(column.nextLong(), precision, scale);
    }
  }

  private static class StringReader extends PrimitiveReader<UTF8String> {
    StringReader(ColumnDescriptor desc) {
      super(desc);
    }

    @Override
    public UTF8String read(UTF8String ignored) {
      Binary binary = column.nextBinary();
      ByteBuffer buffer = binary.toByteBuffer();
      if (buffer.hasArray()) {
        return UTF8String.fromBytes(
            buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
      } else {
        return UTF8String.fromBytes(binary.getBytes());
      }
    }
  }

  private static class UUIDReader extends PrimitiveReader<UTF8String> {
    UUIDReader(ColumnDescriptor desc) {
      super(desc);
    }

    @Override
    @SuppressWarnings("ByteBufferBackingArray")
    public UTF8String read(UTF8String ignored) {
      return UTF8String.fromString(UUIDUtil.convert(column.nextBinary().toByteBuffer()).toString());
    }
  }

  private static class ArrayReader<E> extends RepeatedReader<ArrayData, ReusableArrayData, E> {
    private int readPos = 0;
    private int writePos = 0;

    ArrayReader(int definitionLevel, int repetitionLevel, ParquetValueReader<E> reader) {
      super(definitionLevel, repetitionLevel, reader);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected ReusableArrayData newListData(ArrayData reuse) {
      this.readPos = 0;
      this.writePos = 0;

      if (reuse instanceof ReusableArrayData) {
        return (ReusableArrayData) reuse;
      } else {
        return new ReusableArrayData();
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected E getElement(ReusableArrayData list) {
      E value = null;
      if (readPos < list.capacity()) {
        value = (E) list.values[readPos];
      }

      readPos += 1;

      return value;
    }

    @Override
    protected void addElement(ReusableArrayData reused, E element) {
      if (writePos >= reused.capacity()) {
        reused.grow();
      }

      reused.values[writePos] = element;

      writePos += 1;
    }

    @Override
    protected ArrayData buildList(ReusableArrayData list) {
      list.setNumElements(writePos);
      return list;
    }
  }

  private static class MapReader<K, V>
      extends RepeatedKeyValueReader<MapData, ReusableMapData, K, V> {
    private int readPos = 0;
    private int writePos = 0;

    private final ReusableEntry<K, V> entry = new ReusableEntry<>();
    private final ReusableEntry<K, V> nullEntry = new ReusableEntry<>();

    MapReader(
        int definitionLevel,
        int repetitionLevel,
        ParquetValueReader<K> keyReader,
        ParquetValueReader<V> valueReader) {
      super(definitionLevel, repetitionLevel, keyReader, valueReader);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected ReusableMapData newMapData(MapData reuse) {
      this.readPos = 0;
      this.writePos = 0;

      if (reuse instanceof ReusableMapData) {
        return (ReusableMapData) reuse;
      } else {
        return new ReusableMapData();
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map.Entry<K, V> getPair(ReusableMapData map) {
      Map.Entry<K, V> kv = nullEntry;
      if (readPos < map.capacity()) {
        entry.set((K) map.keys.values[readPos], (V) map.values.values[readPos]);
        kv = entry;
      }

      readPos += 1;

      return kv;
    }

    @Override
    protected void addPair(ReusableMapData map, K key, V value) {
      if (writePos >= map.capacity()) {
        map.grow();
      }

      map.keys.values[writePos] = key;
      map.values.values[writePos] = value;

      writePos += 1;
    }

    @Override
    protected MapData buildMap(ReusableMapData map) {
      map.setNumElements(writePos);
      return map;
    }
  }

  /** Variant reader to convert from Variant to Spark VariantVal */
  private static class VariantReader extends DelegatingValueReader<Variant, VariantVal> {
    @SuppressWarnings("unchecked")
    private VariantReader(ParquetValueReader<?> reader) {
      super((ParquetValueReader<Variant>) reader);
    }

    @Override
    public VariantVal read(VariantVal reuse) {
      Variant variant = super.readFromDelegate(null);
      byte[] metadataBytes = new byte[variant.metadata().sizeInBytes()];
      ByteBuffer metadataBuffer = ByteBuffer.wrap(metadataBytes).order(ByteOrder.LITTLE_ENDIAN);
      variant.metadata().writeTo(metadataBuffer, 0);

      byte[] valueBytes = new byte[variant.value().sizeInBytes()];
      ByteBuffer valueBuffer = ByteBuffer.wrap(valueBytes).order(ByteOrder.LITTLE_ENDIAN);
      variant.value().writeTo(valueBuffer, 0);

      return new VariantVal(valueBytes, metadataBytes);
    }
  }

  private static class InternalRowReader extends StructReader<InternalRow, GenericInternalRow> {
    private final int numFields;

    InternalRowReader(List<ParquetValueReader<?>> readers) {
      super(readers);
      this.numFields = readers.size();
    }

    @Override
    protected GenericInternalRow newStructData(InternalRow reuse) {
      if (reuse instanceof GenericInternalRow) {
        return (GenericInternalRow) reuse;
      } else {
        return new GenericInternalRow(numFields);
      }
    }

    @Override
    protected Object getField(GenericInternalRow intermediate, int pos) {
      return intermediate.genericGet(pos);
    }

    @Override
    protected InternalRow buildStruct(GenericInternalRow struct) {
      return struct;
    }

    @Override
    protected void set(GenericInternalRow row, int pos, Object value) {
      row.update(pos, value);
    }

    @Override
    protected void setNull(GenericInternalRow row, int pos) {
      row.setNullAt(pos);
    }

    @Override
    protected void setBoolean(GenericInternalRow row, int pos, boolean value) {
      row.setBoolean(pos, value);
    }

    @Override
    protected void setInteger(GenericInternalRow row, int pos, int value) {
      row.setInt(pos, value);
    }

    @Override
    protected void setLong(GenericInternalRow row, int pos, long value) {
      row.setLong(pos, value);
    }

    @Override
    protected void setFloat(GenericInternalRow row, int pos, float value) {
      row.setFloat(pos, value);
    }

    @Override
    protected void setDouble(GenericInternalRow row, int pos, double value) {
      row.setDouble(pos, value);
    }
  }

  private static class ReusableMapData extends MapData {
    private final ReusableArrayData keys;
    private final ReusableArrayData values;
    private int numElements;

    private ReusableMapData() {
      this.keys = new ReusableArrayData();
      this.values = new ReusableArrayData();
    }

    private void grow() {
      keys.grow();
      values.grow();
    }

    private int capacity() {
      return keys.capacity();
    }

    public void setNumElements(int numElements) {
      this.numElements = numElements;
      keys.setNumElements(numElements);
      values.setNumElements(numElements);
    }

    @Override
    public int numElements() {
      return numElements;
    }

    @Override
    public MapData copy() {
      return new ArrayBasedMapData(keyArray().copy(), valueArray().copy());
    }

    @Override
    public ReusableArrayData keyArray() {
      return keys;
    }

    @Override
    public ReusableArrayData valueArray() {
      return values;
    }
  }

  private static class ReusableArrayData extends ArrayData {
    private static final Object[] EMPTY = new Object[0];

    private Object[] values = EMPTY;
    private int numElements = 0;

    private void grow() {
      if (values.length == 0) {
        this.values = new Object[20];
      } else {
        Object[] old = values;
        this.values = new Object[old.length << 2];
        // copy the old array in case it has values that can be reused
        System.arraycopy(old, 0, values, 0, old.length);
      }
    }

    private int capacity() {
      return values.length;
    }

    public void setNumElements(int numElements) {
      this.numElements = numElements;
    }

    @Override
    public Object get(int ordinal, DataType dataType) {
      return values[ordinal];
    }

    @Override
    public int numElements() {
      return numElements;
    }

    @Override
    public ArrayData copy() {
      return new GenericArrayData(array());
    }

    @Override
    public Object[] array() {
      return Arrays.copyOfRange(values, 0, numElements);
    }

    @Override
    public void setNullAt(int i) {
      values[i] = null;
    }

    @Override
    public void update(int ordinal, Object value) {
      values[ordinal] = value;
    }

    @Override
    public boolean isNullAt(int ordinal) {
      return null == values[ordinal];
    }

    @Override
    public boolean getBoolean(int ordinal) {
      return (boolean) values[ordinal];
    }

    @Override
    public byte getByte(int ordinal) {
      return (byte) values[ordinal];
    }

    @Override
    public short getShort(int ordinal) {
      return (short) values[ordinal];
    }

    @Override
    public int getInt(int ordinal) {
      return (int) values[ordinal];
    }

    @Override
    public long getLong(int ordinal) {
      return (long) values[ordinal];
    }

    @Override
    public float getFloat(int ordinal) {
      return (float) values[ordinal];
    }

    @Override
    public double getDouble(int ordinal) {
      return (double) values[ordinal];
    }

    @Override
    public Decimal getDecimal(int ordinal, int precision, int scale) {
      return (Decimal) values[ordinal];
    }

    @Override
    public UTF8String getUTF8String(int ordinal) {
      return (UTF8String) values[ordinal];
    }

    @Override
    public byte[] getBinary(int ordinal) {
      return (byte[]) values[ordinal];
    }

    @Override
    public CalendarInterval getInterval(int ordinal) {
      return (CalendarInterval) values[ordinal];
    }

    @Override
    public InternalRow getStruct(int ordinal, int numFields) {
      return (InternalRow) values[ordinal];
    }

    @Override
    public ArrayData getArray(int ordinal) {
      return (ArrayData) values[ordinal];
    }

    @Override
    public MapData getMap(int ordinal) {
      return (MapData) values[ordinal];
    }

    @Override
    public VariantVal getVariant(int ordinal) {
      throw new UnsupportedOperationException("Unsupported method: getVariant");
    }
  }
}
