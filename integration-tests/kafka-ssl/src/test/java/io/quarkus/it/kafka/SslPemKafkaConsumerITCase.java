package io.quarkus.it.kafka;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.junit.QuarkusIntegrationTest;

@QuarkusIntegrationTest
@QuarkusTestResource(value = KafkaSSLTestResource.class, initArgs = {
        @ResourceArg(name = "kafka.tls-configuration-name", value = "custom-pem")
}, restrictToAnnotatedClass = true)
public class SslPemKafkaConsumerITCase extends SslPemKafkaConsumerTest {

}
