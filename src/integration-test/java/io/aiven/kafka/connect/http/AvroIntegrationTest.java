/*
 * Copyright 2021 Aiven Oy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.aiven.kafka.connect.http;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import io.aiven.kafka.connect.http.mockserver.BodyRecorderHandler;
import io.aiven.kafka.connect.http.mockserver.MockServer;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertIterableEquals;

@Testcontainers
public class AvroIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(IntegrationTest.class);

    private static final String HTTP_PATH = "/send-data-here";
    private static final String AUTHORIZATION = "Bearer some-token";
    private static final String CONTENT_TYPE = "application/json";

    private static final String CONNECTOR_NAME = "test-source-connector";

    private static final String TEST_TOPIC = "test-topic";
    private static final int TEST_TOPIC_PARTITIONS = 1;

    static final String JSON_PATTERN = "{\"name\":\"%s\",\"value\":\"%s\"}";

    static final Schema VALUE_RECORD_SCHEMA = new Schema.Parser()
            .parse("{\"type\":\"record\",\"name\":\"record\","
                    + "\"fields\":["
                    + "{\"name\":\"name\",\"type\":\"string\"}, "
                    + "{\"name\":\"value\",\"type\":\"string\"}]}");


    private static File pluginsDir;

    @Container
    private final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.0.0"))
            .withNetwork(Network.newNetwork())
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @Container
    private final SchemaRegistryContainer schemaRegistry = new SchemaRegistryContainer(kafka);

    private AdminClient adminClient;
    private KafkaProducer<String, GenericRecord> producer;

    private ConnectRunner connectRunner;

    private MockServer mockServer;

    @BeforeAll
    static void setUpAll() throws IOException, InterruptedException {
        final File testDir = Files.createTempDirectory("http-connector-for-apache-kafka-").toFile();
        testDir.deleteOnExit();

        pluginsDir = new File(testDir, "plugins/");
        assert pluginsDir.mkdirs();

        // Unpack the library distribution.
        final File transformDir = new File(pluginsDir, "http-connector-for-apache-kafka/");
        assert transformDir.mkdirs();
        final File distFile = new File(System.getProperty("integration-test.distribution.file.path"));
        assert distFile.exists();
        final String cmd = String.format("tar -xf %s --strip-components=1 -C %s",
                distFile, transformDir);
        final Process p = Runtime.getRuntime().exec(cmd);
        assert p.waitFor() == 0;
    }

    @BeforeEach
    void setUp() throws ExecutionException, InterruptedException {
        mockServer = new MockServer(HTTP_PATH, AUTHORIZATION, CONTENT_TYPE);

        final Properties adminClientConfig = new Properties();
        adminClientConfig.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        adminClient = AdminClient.create(adminClientConfig);

        final Map<String, Object> producerProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "io.confluent.kafka.serializers.KafkaAvroSerializer",
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "io.confluent.kafka.serializers.KafkaAvroSerializer",
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1",
                "schema.registry.url", schemaRegistry.getSchemaRegistryUrl()
        );
        producer = new KafkaProducer<>(producerProps);

        final NewTopic testTopic = new NewTopic(TEST_TOPIC, TEST_TOPIC_PARTITIONS, (short) 1);
        adminClient.createTopics(List.of(testTopic)).all().get();
        connectRunner = new ConnectRunner(pluginsDir, kafka.getBootstrapServers());
        connectRunner.start();
    }

    @AfterEach
    final void tearDown() {
        connectRunner.stop();
        adminClient.close();
        producer.close();

        connectRunner.awaitStop();

        mockServer.stop();
    }

    @Test
    @Timeout(30)
    final void testBasicDelivery() throws ExecutionException, InterruptedException {
        final BodyRecorderHandler bodyRecorderHandler = new BodyRecorderHandler();
        mockServer.addHandler(bodyRecorderHandler);
        mockServer.start();

        connectRunner.createConnector(basicConnectorConfig());

        final List<String> expectedBodies = new ArrayList<>();
        final List<Future<RecordMetadata>> sendFutures = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            for (int partition = 0; partition < TEST_TOPIC_PARTITIONS; partition++) {
                final String key = "key-" + i;
                final var recordName = "user-" + i;
                final var recordValue = "value-" + i;
                final var value = createRecord(recordName, recordValue);
                expectedBodies.add(String.format(JSON_PATTERN, recordName, recordValue));
                sendFutures.add(sendMessageAsync(partition, key, value));
            }
        }
        producer.flush();

        TestUtils.waitForCondition(() -> bodyRecorderHandler.recorderBodies().size() >= expectedBodies.size(),
                15000,
                "All requests received by HTTP server"
        );
        log.info("Recorded request bodies: {}", bodyRecorderHandler.recorderBodies());
        assertIterableEquals(expectedBodies, bodyRecorderHandler.recorderBodies());

        log.info("{} HTTP requests were expected, {} were successfully delivered",
                expectedBodies.size(),
                bodyRecorderHandler.recorderBodies().size());
    }

    private Map<String, String> basicConnectorConfig() {
        final var config = new HashMap<String, String>();
        config.put("name", CONNECTOR_NAME);
        config.put("connector.class", HttpSinkConnector.class.getName());
        config.put("topics", TEST_TOPIC);
        config.put("key.converter", "io.confluent.connect.avro.AvroConverter");
        config.put("key.converter.schema.registry.url", schemaRegistry.getSchemaRegistryUrl());
        config.put("value.converter", "io.confluent.connect.avro.AvroConverter");
        config.put("value.converter.schema.registry.url", schemaRegistry.getSchemaRegistryUrl());
        config.put("tasks.max", "1");
        config.put("http.url", "http://localhost:" + mockServer.localPort() + HTTP_PATH);
        config.put("http.authorization.type", "static");
        config.put("http.headers.authorization", AUTHORIZATION);
        config.put("http.headers.content.type", CONTENT_TYPE);
        return config;
    }

    private GenericData.Record createRecord(final String name, final String value) {
        final var valueRecord = new GenericData.Record(VALUE_RECORD_SCHEMA);
        valueRecord.put("name", name);
        valueRecord.put("value", value);
        return valueRecord;
    }

    private Future<RecordMetadata> sendMessageAsync(final int partition,
                                                    final String key,
                                                    final GenericRecord value) {
        final ProducerRecord<String, GenericRecord> msg = new ProducerRecord<>(
                TEST_TOPIC, partition, key, value);
        return producer.send(msg);
    }
}
