package io.quarkus.hibernate.reactive.mapping.id.optimizer.optimizer;

import org.hibernate.id.enhanced.PooledLoOptimizer;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.hibernate.reactive.SchemaUtil;
import io.quarkus.test.QuarkusUnitTest;

public class IdOptimizerDefaultDefaultTest extends AbstractIdOptimizerDefaultTest {

    @RegisterExtension
    static QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot((jar) -> jar
                    .addClasses(EntityWithDefaultGenerator.class, EntityWithGenericGenerator.class,
                            EntityWithSequenceGenerator.class, EntityWithTableGenerator.class,
                            EntityWithGenericGeneratorAndPooledOptimizer.class,
                            EntityWithGenericGeneratorAndPooledLoOptimizer.class)
                    .addClasses(SchemaUtil.class))
            .withConfigurationResource("application.properties");

    @Override
    Class<?> defaultOptimizerType() {
        return PooledLoOptimizer.class;
    }
}
