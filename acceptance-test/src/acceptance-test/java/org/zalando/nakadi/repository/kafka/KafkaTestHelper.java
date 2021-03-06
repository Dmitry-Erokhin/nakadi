package org.zalando.nakadi.repository.kafka;

import kafka.admin.RackAwareMode;
import kafka.server.ConfigType;
import kafka.zk.AdminZkClient;
import kafka.zk.KafkaZkClient;
import kafka.zookeeper.ZooKeeperClient;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.utils.Time;
import org.zalando.nakadi.view.Cursor;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.zalando.nakadi.repository.kafka.KafkaCursor.toKafkaOffset;
import static org.zalando.nakadi.repository.kafka.KafkaCursor.toNakadiOffset;

public class KafkaTestHelper {

    public static final int CURSOR_OFFSET_LENGTH = 18;
    private final String kafkaUrl;

    public KafkaTestHelper(final String kafkaUrl) {
        this.kafkaUrl = kafkaUrl;
    }

    public KafkaConsumer<String, String> createConsumer() {
        return new KafkaConsumer<>(createKafkaProperties());
    }

    public KafkaProducer<String, String> createProducer() {
        return new KafkaProducer<>(createKafkaProperties());
    }

    private Properties createKafkaProperties() {
        final Properties props = new Properties();
        props.put("bootstrap.servers", kafkaUrl);
        props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        return props;
    }

    public void writeMessageToPartition(final String partition, final String topic, final String message)
            throws ExecutionException, InterruptedException {
        final String messageToSend = String.format("\"%s\"", message);
        final ProducerRecord<String, String> producerRecord = new ProducerRecord<>(topic, Integer.parseInt(partition),
                "someKey", messageToSend);
        createProducer().send(producerRecord).get();
    }

    public void writeMultipleMessageToPartition(final String partition, final String topic, final String message,
                                                final int times)
            throws ExecutionException, InterruptedException {
        for (int i = 0; i < times; i++) {
            writeMessageToPartition(partition, topic, message);
        }
    }

    public List<Cursor> getOffsetsToReadFromLatest(final String topic) {
        return getNextOffsets(topic)
                .stream()
                .map(cursor -> {
                    if ("0".equals(cursor.getOffset())) {
                        return new Cursor(cursor.getPartition(), "001-0001--1");
                    } else {
                        final long lastEventOffset = toKafkaOffset(cursor.getOffset()) - 1;
                        final String offset = StringUtils.leftPad(toNakadiOffset(lastEventOffset),
                                CURSOR_OFFSET_LENGTH, '0');
                        return new Cursor(cursor.getPartition(), String.format("001-0001-%s", offset));
                    }
                })
                .collect(Collectors.toList());
    }

    public List<Cursor> getNextOffsets(final String topic) {

        final KafkaConsumer<String, String> consumer = createConsumer();
        final List<TopicPartition> partitions = consumer
                .partitionsFor(topic)
                .stream()
                .map(pInfo -> new TopicPartition(topic, pInfo.partition()))
                .collect(Collectors.toList());

        consumer.assign(partitions);
        consumer.seekToEnd(partitions);

        return partitions
                .stream()
                .map(partition -> new Cursor(Integer.toString(partition.partition()),
                        Long.toString(consumer.position(partition))))
                .collect(Collectors.toList());
    }

    public void createTopic(final String topic, final String zkUrl) {
        try (KafkaZkClient zkClient = createZkClient(zkUrl)) {
            final AdminZkClient adminZkClient = new AdminZkClient(zkClient);
            adminZkClient.createTopic(topic, 1, 1,
                    new Properties(), RackAwareMode.Safe$.MODULE$);
        }
    }

    private static KafkaZkClient createZkClient(final String zkUrl) {
        return new KafkaZkClient(
                new ZooKeeperClient(
                        zkUrl,
                        30000,
                        10000,
                        1000,
                        Time.SYSTEM,
                        "dummyMetricGroup",
                        "dummyMetricType"
                ),
                false,
                Time.SYSTEM
        );
    }

    public static Long getTopicRetentionTime(final String topic, final String zkPath) {
        return Long.valueOf(getTopicProperty(topic, zkPath, "retention.ms"));
    }

    public static String getTopicCleanupPolicy(final String topic, final String zkPath) {
        return getTopicProperty(topic, zkPath, "cleanup.policy");
    }

    public static String getTopicProperty(final String topic, final String zkPath, final String propertyName) {
        try (KafkaZkClient zkClient = createZkClient(zkPath)) {
            final AdminZkClient adminZkClient = new AdminZkClient(zkClient);
            final Properties topicConfig = adminZkClient.fetchEntityConfig(ConfigType.Topic(), topic);
            return topicConfig.getProperty(propertyName);
        }
    }
}
