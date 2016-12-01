/**
 * Copyright 2016 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 **/

package io.confluent.connect.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.connect.data.ConnectSchema;
import org.apache.kafka.connect.data.Date;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.data.Time;
import org.apache.kafka.connect.data.Timestamp;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.storage.Converter;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import static io.confluent.connect.elasticsearch.ElasticsearchSinkConnectorConstants.MAP_KEY;
import static io.confluent.connect.elasticsearch.ElasticsearchSinkConnectorConstants.MAP_VALUE;

public class DataConverter {

  private static final Converter JSON_CONVERTER;
  private static final ObjectMapper JSON_OBJECT_MAPPER;

  static {
    JSON_CONVERTER = new JsonConverter();
    JSON_CONVERTER.configure(Collections.singletonMap("schemas.enable", "false"), false);
    JSON_OBJECT_MAPPER = new ObjectMapper();
  }

  private static String convertKey(Schema keySchema, Object key) {
    if (key == null) {
      throw new ConnectException("Key is used as document id and can not be null.");
    }

    final Schema.Type schemaType;
    if (keySchema == null) {
      schemaType = ConnectSchema.schemaType(key.getClass());
      if (schemaType == null) {
        throw new DataException("Java class " + key.getClass() + " does not have corresponding schema type.");
      }
    } else {
      schemaType = keySchema.type();
    }

    switch (schemaType) {
      case INT8:
      case INT16:
      case INT32:
      case INT64:
      case STRING:
        return String.valueOf(key);
      default:
        throw new DataException(schemaType.name() + " is not supported as the document id.");
    }
  }

  public static IndexableRecord convertRecord(SinkRecord record, String index, String type, boolean jsonKey, boolean ignoreKey, boolean ignoreSchema) {
    final Schema schema;
    final Object value;
    final String id;

    if (jsonKey) {
      /*
       * SPECIAL CRUNCHBASE-SPECIFIC JSON KEY CONSISTING OF A UUID+INDEX PAIR THAT OVERRIDES THE STANDARD CONNECTOR LOGIC
       */
      schema = record.valueSchema();
      value = record.value();

      try {
        byte[] keyBytes = JSON_CONVERTER.fromConnectData(record.topic(), record.keySchema(), record.key());
        String keyString = new String(keyBytes, StandardCharsets.UTF_8);
        Map keyJson = JSON_OBJECT_MAPPER.readValue(keyString, Map.class);
        id = keyJson.get("uuid").toString();
        index = (record.topic() + "-" + keyJson.get("index").toString()).toLowerCase();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

    } else {
      /*
       * STANDARD CONNECTOR LOGIC
       */
      if (ignoreKey) {
        id = record.topic() + "+" + String.valueOf((int) record.kafkaPartition()) + "+" + String.valueOf(record.kafkaOffset());
      } else {
        id = DataConverter.convertKey(record.keySchema(), record.key());
      }

      if (!ignoreSchema) {
        schema = preProcessSchema(record.valueSchema());
        value = preProcessValue(record.value(), record.valueSchema(), schema);
      } else {
        schema = record.valueSchema();
        value = record.value();
      }

    }

    final String payload = new String(JSON_CONVERTER.fromConnectData(record.topic(), schema, value), StandardCharsets.UTF_8);
    return new IndexableRecord(new Key(index, type, id), payload, record.kafkaOffset());
  }

  // We need to pre process the Kafka Connect schema before converting to JSON as Elasticsearch
  // expects a different JSON format from the current JSON converter provides. Rather than completely
  // rewrite a converter for Elasticsearch, we will refactor the JSON converter to support customized
  // translation. The pre process is no longer needed once we have the JSON converter refactored.
  static Schema preProcessSchema(Schema schema) {
    if (schema == null) {
      return null;
    }
    // Handle logical types
    String schemaName = schema.name();
    if (schemaName != null) {
      switch (schemaName) {
        case Decimal.LOGICAL_NAME:
          return copySchemaBasics(schema, SchemaBuilder.float64()).build();
        case Date.LOGICAL_NAME:
        case Time.LOGICAL_NAME:
        case Timestamp.LOGICAL_NAME:
          return schema;
      }
    }

    Schema.Type schemaType = schema.type();
    switch (schemaType) {
      case ARRAY: {
        return copySchemaBasics(schema, SchemaBuilder.array(preProcessSchema(schema.valueSchema()))).build();
      }
      case MAP: {
        Schema keySchema = schema.keySchema();
        Schema valueSchema = schema.valueSchema();
        String keyName = keySchema.name() == null ? keySchema.type().name() : keySchema.name();
        String valueName = valueSchema.name() == null ? valueSchema.type().name() : valueSchema.name();
        Schema elementSchema = SchemaBuilder.struct().name(keyName + "-" + valueName)
            .field(MAP_KEY, preProcessSchema(keySchema))
            .field(MAP_VALUE, preProcessSchema(valueSchema))
            .build();
        return copySchemaBasics(schema, SchemaBuilder.array(elementSchema)).build();
      }
      case STRUCT: {
        SchemaBuilder structBuilder = copySchemaBasics(schema, SchemaBuilder.struct().name(schemaName));
        for (Field field : schema.fields()) {
          structBuilder.field(field.name(), preProcessSchema(field.schema()));
        }
        return structBuilder.build();
      }
      default: {
        return schema;
      }
    }
  }

  private static SchemaBuilder copySchemaBasics(Schema source, SchemaBuilder target) {
    if (source.isOptional()) {
      target.optional();
    }
    if (source.defaultValue() != null && source.type() != Schema.Type.STRUCT) {
      final Object preProcessedDefaultValue = preProcessValue(source.defaultValue(), source, target);
      target.defaultValue(preProcessedDefaultValue);
    }
    return target;
  }

  // visible for testing
  static Object preProcessValue(Object value, Schema schema, Schema newSchema) {
    if (schema == null) {
      return value;
    }
    if (value == null) {
      if (schema.defaultValue() != null) {
        return schema.defaultValue();
      }
      if (schema.isOptional()) {
        return null;
      }
      throw new DataException("null value for field that is required and has no default value");
    }

    // Handle logical types
    String schemaName = schema.name();
    if (schemaName != null) {
      switch (schemaName) {
        case Decimal.LOGICAL_NAME:
          return ((BigDecimal) value).doubleValue();
        case Date.LOGICAL_NAME:
        case Time.LOGICAL_NAME:
        case Timestamp.LOGICAL_NAME:
          return value;
      }
    }

    Schema.Type schemaType = schema.type();
    Schema keySchema;
    Schema valueSchema;
    switch (schemaType) {
      case ARRAY:
        Collection collection = (Collection) value;
        ArrayList<Object> result = new ArrayList<>();
        for (Object element: collection) {
          result.add(preProcessValue(element, schema.valueSchema(), newSchema.valueSchema()));
        }
        return result;
      case MAP:
        keySchema = schema.keySchema();
        valueSchema = schema.valueSchema();
        ArrayList<Struct> mapStructs = new ArrayList<>();
        Map<?, ?> map = (Map<?, ?>) value;
        Schema newValueSchema = newSchema.valueSchema();
        for (Map.Entry<?, ?> entry: map.entrySet()) {
          Struct mapStruct = new Struct(newValueSchema);
          mapStruct.put(MAP_KEY, preProcessValue(entry.getKey(), keySchema, newValueSchema.field(MAP_KEY).schema()));
          mapStruct.put(MAP_VALUE, preProcessValue(entry.getValue(), valueSchema, newValueSchema.field(MAP_VALUE).schema()));
          mapStructs.add(mapStruct);
        }
        return mapStructs;
      case STRUCT:
        Struct struct = (Struct) value;
        Struct newStruct = new Struct(newSchema);
        for (Field field : schema.fields()) {
          Object converted =  preProcessValue(struct.get(field), field.schema(), newSchema.field(field.name()).schema());
          newStruct.put(field.name(), converted);
        }
        return newStruct;
      default:
        return value;
    }
  }
}
