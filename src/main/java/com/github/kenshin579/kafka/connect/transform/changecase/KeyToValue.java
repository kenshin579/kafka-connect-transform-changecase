package com.github.kenshin579.kafka.connect.transform.changecase;

import org.apache.kafka.common.cache.Cache;
import org.apache.kafka.common.cache.LRUCache;
import org.apache.kafka.common.cache.SynchronizedCache;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.transforms.Transformation;
import org.apache.kafka.connect.transforms.util.SchemaUtil;
import org.apache.kafka.connect.transforms.util.SimpleConfig;

import static org.apache.kafka.connect.transforms.util.Requirements.requireStruct;

import java.util.Map;

public class KeyToValue<R extends ConnectRecord<R>> implements Transformation<R> {

    public static final String OVERVIEW_DOC =
            "Update the record's value by inserting a new column with the key of the record";

    public static final ConfigDef CONFIG_DEF = new ConfigDef()
            .define(ConfigName.FIELD_NAME, ConfigDef.Type.STRING,  ConfigDef.Importance.MEDIUM,
                    "Field name");

    private interface ConfigName {
        String FIELD_NAME = "key.field.name";
    }

    private static final String PURPOSE = "insert key into value struct";

    private String fieldName;

    private Cache<String, Schema> schemaUpdateCache;

    @Override
    public void configure(Map<String, ?> props) {
        final SimpleConfig config = new SimpleConfig(CONFIG_DEF, props);
        fieldName = config.getString(ConfigName.FIELD_NAME);
        schemaUpdateCache = new SynchronizedCache<>(new LRUCache<>(16));
    }

    @Override
    public R apply(R record) {
        final Struct value = requireStruct(record.value(), PURPOSE);

        final String hashCode = String.valueOf(value.schema().hashCode());

        Schema updatedSchema = schemaUpdateCache.get(hashCode);
        if (updatedSchema == null) {
            updatedSchema = makeUpdatedSchema(value.schema());
            schemaUpdateCache.put(hashCode, updatedSchema);
        }

        final Struct updatedValue = new Struct(updatedSchema);

        for (Field field: updatedValue.schema().fields()) {
            if (field.name().equals(fieldName)) {
                updatedValue.put(field.name(), record.key() == null ? "": record.key().toString());
            } else {
                updatedValue.put(field.name(), value.get(field));
            }
        }
        return record.newRecord(
                record.topic(),
                record.kafkaPartition(),
                record.keySchema(),
                record.key(),
                updatedSchema,
                updatedValue,
                record.timestamp()
        );
    }

    private Schema makeUpdatedSchema(Schema schema) {
        final SchemaBuilder builder = SchemaUtil.copySchemaBasics(schema, SchemaBuilder.struct());
        for (Field field : schema.fields()) {
            builder.field(field.name(), field.schema());
        }
        builder.field(fieldName, Schema.OPTIONAL_STRING_SCHEMA);

        return builder.build();
    }

    @Override
    public void close() {
    }

    @Override
    public ConfigDef config() {
        return CONFIG_DEF;
    }

}