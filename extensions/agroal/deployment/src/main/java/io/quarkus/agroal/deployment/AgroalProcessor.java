package io.quarkus.agroal.deployment;

import static io.quarkus.agroal.deployment.AgroalDataSourceBuildUtil.qualifiers;
import static io.quarkus.arc.deployment.OpenTelemetrySdkBuildItem.isOtelSdkEnabled;
import static io.quarkus.deployment.Capability.OPENTELEMETRY_TRACER;

import java.sql.Driver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.logging.Level;
import java.util.stream.Collectors;

import javax.sql.XADataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Singleton;

import org.jboss.jandex.ClassType;
import org.jboss.jandex.DotName;
import org.jboss.logging.Logger;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.AgroalPoolInterceptor;
import io.quarkus.agroal.DataSource;
import io.quarkus.agroal.runtime.AgroalDataSourceSupport;
import io.quarkus.agroal.runtime.AgroalOpenTelemetryWrapper;
import io.quarkus.agroal.runtime.AgroalRecorder;
import io.quarkus.agroal.runtime.DataSourceJdbcBuildTimeConfig;
import io.quarkus.agroal.runtime.DataSources;
import io.quarkus.agroal.runtime.DataSourcesJdbcBuildTimeConfig;
import io.quarkus.agroal.runtime.JdbcDriver;
import io.quarkus.agroal.runtime.TransactionIntegration;
import io.quarkus.agroal.spi.JdbcDataSourceBuildItem;
import io.quarkus.agroal.spi.JdbcDriverBuildItem;
import io.quarkus.arc.BeanDestroyer;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.OpenTelemetrySdkBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.processor.DotNames;
import io.quarkus.datasource.common.runtime.DataSourceUtil;
import io.quarkus.datasource.deployment.spi.DefaultDataSourceDbKindBuildItem;
import io.quarkus.datasource.runtime.DataSourceBuildTimeConfig;
import io.quarkus.datasource.runtime.DataSourcesBuildTimeConfig;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.Feature;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Consume;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ExtensionSslNativeSupportBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.LogCategoryBuildItem;
import io.quarkus.deployment.builditem.SslNativeConfigBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBundleBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.narayana.jta.deployment.NarayanaInitBuildItem;
import io.quarkus.runtime.configuration.ConfigurationException;
import io.quarkus.smallrye.health.deployment.spi.HealthBuildItem;

@SuppressWarnings("deprecation")
class AgroalProcessor {

    private static final Logger log = Logger.getLogger(AgroalProcessor.class);

    private static final String OPEN_TELEMETRY_DRIVER = "io.opentelemetry.instrumentation.jdbc.OpenTelemetryDriver";
    private static final DotName DATA_SOURCE = DotName.createSimple(javax.sql.DataSource.class.getName());
    private static final DotName AGROAL_DATA_SOURCE = DotName.createSimple(AgroalDataSource.class.getName());

    @BuildStep
    void agroal(BuildProducer<FeatureBuildItem> feature) {
        feature.produce(new FeatureBuildItem(Feature.AGROAL));
    }

    @BuildStep
    void build(
            DataSourcesBuildTimeConfig dataSourcesBuildTimeConfig,
            DataSourcesJdbcBuildTimeConfig dataSourcesJdbcBuildTimeConfig,
            List<DefaultDataSourceDbKindBuildItem> defaultDbKinds,
            List<JdbcDriverBuildItem> jdbcDriverBuildItems,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClass,
            BuildProducer<NativeImageResourceBuildItem> resource,
            BuildProducer<ServiceProviderBuildItem> service,
            Capabilities capabilities,
            BuildProducer<ExtensionSslNativeSupportBuildItem> sslNativeSupport,
            BuildProducer<AggregatedDataSourceBuildTimeConfigBuildItem> aggregatedConfig,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            CurateOutcomeBuildItem curateOutcomeBuildItem) throws Exception {
        if (dataSourcesBuildTimeConfig.driver().isPresent() || dataSourcesBuildTimeConfig.url().isPresent()) {
            throw new ConfigurationException(
                    "quarkus.datasource.url and quarkus.datasource.driver have been deprecated in Quarkus 1.3 and removed in 1.9. "
                            + "Please use the new datasource configuration as explained in https://quarkus.io/guides/datasource.");
        }

        List<AggregatedDataSourceBuildTimeConfigBuildItem> aggregatedDataSourceBuildTimeConfigs = getAggregatedConfigBuildItems(
                dataSourcesBuildTimeConfig,
                dataSourcesJdbcBuildTimeConfig, curateOutcomeBuildItem,
                jdbcDriverBuildItems, defaultDbKinds);

        if (aggregatedDataSourceBuildTimeConfigs.isEmpty()) {
            log.warn("The Agroal dependency is present but no JDBC datasources have been defined.");
            return;
        }

        boolean otelJdbcInstrumentationActive = false;
        for (AggregatedDataSourceBuildTimeConfigBuildItem aggregatedDataSourceBuildTimeConfig : aggregatedDataSourceBuildTimeConfigs) {
            validateBuildTimeConfig(aggregatedDataSourceBuildTimeConfig);

            if (aggregatedDataSourceBuildTimeConfig.getJdbcConfig().telemetry()) {
                otelJdbcInstrumentationActive = true;
            }

            reflectiveClass
                    .produce(ReflectiveClassBuildItem.builder(aggregatedDataSourceBuildTimeConfig.getResolvedDriverClass())
                            .methods().build());

            aggregatedConfig.produce(aggregatedDataSourceBuildTimeConfig);
        }

        if (otelJdbcInstrumentationActive && capabilities.isPresent(OPENTELEMETRY_TRACER)) {
            // at least one datasource is using OpenTelemetry JDBC instrumentation,
            // therefore we register the OpenTelemetry data source wrapper bean
            additionalBeans.produce(new AdditionalBeanBuildItem.Builder()
                    .addBeanClass(AgroalOpenTelemetryWrapper.class)
                    .setDefaultScope(DotNames.SINGLETON).build());
        }

        // For now, we can't push the security providers to Agroal so we need to include
        // the service file inside the image. Hopefully, we will get an entry point to
        // resolve them at build time and push them to Agroal soon.
        resource.produce(new NativeImageResourceBuildItem(
                "META-INF/services/" + io.agroal.api.security.AgroalSecurityProvider.class.getName()));

        // accessed through io.quarkus.agroal.runtime.DataSources.loadDriversInTCCL
        service.produce(ServiceProviderBuildItem.allProvidersFromClassPath(Driver.class.getName()));

        reflectiveClass.produce(ReflectiveClassBuildItem.builder(io.agroal.pool.ConnectionHandler[].class.getName(),
                io.agroal.pool.ConnectionHandler.class.getName(),
                io.agroal.api.security.AgroalDefaultSecurityProvider.class.getName(),
                io.agroal.api.security.AgroalKerberosSecurityProvider.class.getName(),
                java.sql.Statement[].class.getName(),
                java.sql.Statement.class.getName(),
                java.sql.ResultSet.class.getName(),
                java.sql.ResultSet[].class.getName()).build());

        // Enable SSL support by default
        sslNativeSupport.produce(new ExtensionSslNativeSupportBuildItem(Feature.AGROAL.getName()));
    }

    private static void validateBuildTimeConfig(AggregatedDataSourceBuildTimeConfigBuildItem aggregatedConfig) {
        DataSourceJdbcBuildTimeConfig jdbcBuildTimeConfig = aggregatedConfig.getJdbcConfig();

        String fullDataSourceName = aggregatedConfig.isDefault() ? "default datasource"
                : "datasource named '" + aggregatedConfig.getName() + "'";

        String driverName = aggregatedConfig.getResolvedDriverClass();
        Class<?> driver;
        try {
            driver = Class.forName(driverName, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new ConfigurationException(
                    "Unable to load the datasource driver " + driverName + " for the " + fullDataSourceName, e);
        }
        if (jdbcBuildTimeConfig.transactions() == TransactionIntegration.XA) {
            if (!XADataSource.class.isAssignableFrom(driver)) {
                throw new ConfigurationException(
                        "Driver is not an XA dataSource, while XA has been enabled in the configuration of the "
                                + fullDataSourceName + ": either disable XA or switch the driver to an XADataSource");
            }
        } else {
            if (driver != null && !javax.sql.DataSource.class.isAssignableFrom(driver)
                    && !Driver.class.isAssignableFrom(driver)) {
                if (aggregatedConfig.isDefault()) {
                    throw new ConfigurationException(
                            "Driver " + driverName
                                    + " is an XA datasource, but XA transactions have not been enabled on the default datasource; please either set 'quarkus.datasource.jdbc.transactions=xa' or switch to a standard non-XA JDBC driver implementation");
                } else {
                    throw new ConfigurationException(
                            "Driver " + driverName
                                    + " is an XA datasource, but XA transactions have not been enabled on the datasource named '"
                                    + fullDataSourceName + "'; please either set 'quarkus.datasource." + fullDataSourceName
                                    + ".jdbc.transactions=xa' or switch to a standard non-XA JDBC driver implementation");
                }
            }
        }
    }

    private AgroalDataSourceSupport getDataSourceSupport(
            List<AggregatedDataSourceBuildTimeConfigBuildItem> aggregatedBuildTimeConfigBuildItems,
            SslNativeConfigBuildItem sslNativeConfig, Capabilities capabilities) {
        Map<String, AgroalDataSourceSupport.Entry> dataSourceSupportEntries = new HashMap<>();
        for (AggregatedDataSourceBuildTimeConfigBuildItem aggregatedDataSourceBuildTimeConfig : aggregatedBuildTimeConfigBuildItems) {
            String dataSourceName = aggregatedDataSourceBuildTimeConfig.getName();
            dataSourceSupportEntries.put(dataSourceName,
                    new AgroalDataSourceSupport.Entry(dataSourceName, aggregatedDataSourceBuildTimeConfig.getDbKind(),
                            aggregatedDataSourceBuildTimeConfig.getDataSourceConfig().dbVersion(),
                            aggregatedDataSourceBuildTimeConfig.getResolvedDriverClass(),
                            aggregatedDataSourceBuildTimeConfig.isDefault()));
        }

        return new AgroalDataSourceSupport(sslNativeConfig.isExplicitlyDisabled(),
                capabilities.isPresent(Capability.METRICS), dataSourceSupportEntries);
    }

    @Record(ExecutionTime.STATIC_INIT)
    @BuildStep
    void generateDataSourceSupportBean(AgroalRecorder recorder,
            List<AggregatedDataSourceBuildTimeConfigBuildItem> aggregatedBuildTimeConfigBuildItems,
            SslNativeConfigBuildItem sslNativeConfig,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeanBuildItemBuildProducer,
            BuildProducer<UnremovableBeanBuildItem> unremovableBeans) {
        additionalBeans.produce(new AdditionalBeanBuildItem(JdbcDriver.class));

        if (aggregatedBuildTimeConfigBuildItems.isEmpty()) {
            // No datasource has been configured so bail out
            return;
        }

        // make a DataSources bean
        additionalBeans.produce(AdditionalBeanBuildItem.builder().addBeanClasses(DataSources.class).setUnremovable()
                .setDefaultScope(DotNames.SINGLETON).build());
        // add the @DataSource class otherwise it won't be registered as a qualifier
        additionalBeans.produce(AdditionalBeanBuildItem.builder().addBeanClass(DataSource.class).build());

        // make AgroalPoolInterceptor beans unremovable, users still have to make them beans
        unremovableBeans.produce(UnremovableBeanBuildItem.beanTypes(AgroalPoolInterceptor.class));

        // create the AgroalDataSourceSupport bean that DataSources/DataSourceHealthCheck use as a dependency
        AgroalDataSourceSupport agroalDataSourceSupport = getDataSourceSupport(aggregatedBuildTimeConfigBuildItems,
                sslNativeConfig,
                capabilities);
        syntheticBeanBuildItemBuildProducer.produce(SyntheticBeanBuildItem.configure(AgroalDataSourceSupport.class)
                .supplier(recorder.dataSourceSupportSupplier(agroalDataSourceSupport))
                .scope(Singleton.class)
                .unremovable()
                .setRuntimeInit()
                .done());
    }

    @Record(ExecutionTime.RUNTIME_INIT)
    @BuildStep
    @Consume(NarayanaInitBuildItem.class)
    void generateDataSourceBeans(AgroalRecorder recorder,
            List<AggregatedDataSourceBuildTimeConfigBuildItem> aggregatedBuildTimeConfigBuildItems,
            SslNativeConfigBuildItem sslNativeConfig,
            Capabilities capabilities,
            Optional<OpenTelemetrySdkBuildItem> openTelemetrySdkBuildItem,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeanBuildItemBuildProducer,
            BuildProducer<JdbcDataSourceBuildItem> jdbcDataSource) {
        if (aggregatedBuildTimeConfigBuildItems.isEmpty()) {
            // No datasource has been configured so bail out
            return;
        }

        for (AggregatedDataSourceBuildTimeConfigBuildItem aggregatedBuildTimeConfigBuildItem : aggregatedBuildTimeConfigBuildItems) {

            String dataSourceName = aggregatedBuildTimeConfigBuildItem.getName();

            SyntheticBeanBuildItem.ExtendedBeanConfigurator configurator = SyntheticBeanBuildItem
                    .configure(AgroalDataSource.class)
                    .addType(DATA_SOURCE)
                    .addType(AGROAL_DATA_SOURCE)
                    .scope(ApplicationScoped.class)
                    .qualifiers(qualifiers(dataSourceName))
                    .setRuntimeInit()
                    .unremovable()
                    .addInjectionPoint(ClassType.create(DotName.createSimple(DataSources.class)))
                    .startup()
                    .checkActive(recorder.agroalDataSourceCheckActiveSupplier(dataSourceName))
                    .createWith(recorder.agroalDataSourceSupplier(dataSourceName, isOtelSdkEnabled(openTelemetrySdkBuildItem)))
                    .destroyer(BeanDestroyer.AutoCloseableDestroyer.class);

            if (!DataSourceUtil.isDefault(dataSourceName)) {
                // this definitely not ideal, but 'elytron-jdbc-security' uses it (although it could be easily changed)
                // which means that perhaps other extensions might depend on this as well...
                configurator.name(dataSourceName);
            }

            syntheticBeanBuildItemBuildProducer.produce(configurator.done());

            jdbcDataSource.produce(new JdbcDataSourceBuildItem(dataSourceName,
                    aggregatedBuildTimeConfigBuildItem.getDbKind(),
                    aggregatedBuildTimeConfigBuildItem.getDataSourceConfig().dbVersion(),
                    aggregatedBuildTimeConfigBuildItem.getJdbcConfig().transactions() != TransactionIntegration.DISABLED,
                    aggregatedBuildTimeConfigBuildItem.isDefault()));
        }
    }

    private List<AggregatedDataSourceBuildTimeConfigBuildItem> getAggregatedConfigBuildItems(
            DataSourcesBuildTimeConfig dataSourcesBuildTimeConfig,
            DataSourcesJdbcBuildTimeConfig dataSourcesJdbcBuildTimeConfig,
            CurateOutcomeBuildItem curateOutcomeBuildItem,
            List<JdbcDriverBuildItem> jdbcDriverBuildItems,
            List<DefaultDataSourceDbKindBuildItem> defaultDbKinds) {
        List<AggregatedDataSourceBuildTimeConfigBuildItem> dataSources = new ArrayList<>();

        for (Entry<String, DataSourceBuildTimeConfig> entry : dataSourcesBuildTimeConfig.dataSources().entrySet()) {
            DataSourceJdbcBuildTimeConfig jdbcBuildTimeConfig = dataSourcesJdbcBuildTimeConfig
                    .dataSources().get(entry.getKey()).jdbc();
            if (!jdbcBuildTimeConfig.enabled()) {
                continue;
            }

            boolean enableImplicitResolution = DataSourceUtil.isDefault(entry.getKey())
                    ? entry.getValue().devservices().enabled().orElse(!dataSourcesBuildTimeConfig.hasNamedDataSources())
                    : true;

            Optional<String> effectiveDbKind = DefaultDataSourceDbKindBuildItem
                    .resolve(entry.getValue().dbKind(), defaultDbKinds,
                            enableImplicitResolution,
                            curateOutcomeBuildItem);

            if (!effectiveDbKind.isPresent()) {
                continue;
            }

            dataSources.add(new AggregatedDataSourceBuildTimeConfigBuildItem(entry.getKey(),
                    entry.getValue(),
                    jdbcBuildTimeConfig,
                    effectiveDbKind.get(),
                    resolveDriver(entry.getKey(), effectiveDbKind.get(), jdbcBuildTimeConfig, jdbcDriverBuildItems)));
        }

        return dataSources;
    }

    private String resolveDriver(String dataSourceName, String dbKind,
            DataSourceJdbcBuildTimeConfig dataSourceJdbcBuildTimeConfig, List<JdbcDriverBuildItem> jdbcDriverBuildItems) {
        if (dataSourceJdbcBuildTimeConfig.driver().isPresent()) {
            return dataSourceJdbcBuildTimeConfig.driver().get();
        }

        Optional<JdbcDriverBuildItem> matchingJdbcDriver = jdbcDriverBuildItems.stream()
                .filter(i -> dbKind.equals(i.getDbKind()))
                .findFirst();

        if (matchingJdbcDriver.isPresent()) {
            if (io.quarkus.agroal.runtime.TransactionIntegration.XA == dataSourceJdbcBuildTimeConfig.transactions()) {
                if (matchingJdbcDriver.get().getDriverXAClass().isPresent()) {
                    return matchingJdbcDriver.get().getDriverXAClass().get();
                }
            } else {
                return matchingJdbcDriver.get().getDriverClass();
            }
        }

        throw new ConfigurationException(String.format(
                "Unable to find a JDBC driver corresponding to the database kind '%s' for the %s (available: '%s'). "
                        + "Check if it's a typo, otherwise provide a suitable JDBC driver extension, define the driver manually,"
                        + " or disable the JDBC datasource by adding '%s=false' to your configuration if you don't need it.",
                dbKind, DataSourceUtil.isDefault(dataSourceName) ? "default datasource" : "datasource '" + dataSourceName + "'",
                jdbcDriverBuildItems.stream().map(JdbcDriverBuildItem::getDbKind).collect(Collectors.joining("','")),
                DataSourceUtil.dataSourcePropertyKey(dataSourceName, "jdbc")));
    }

    @BuildStep
    HealthBuildItem addHealthCheck(Capabilities capabilities, DataSourcesBuildTimeConfig dataSourcesBuildTimeConfig) {
        if (capabilities.isPresent(Capability.SMALLRYE_HEALTH)) {
            return new HealthBuildItem("io.quarkus.agroal.runtime.health.DataSourceHealthCheck",
                    dataSourcesBuildTimeConfig.healthEnabled());
        } else {
            return null;
        }
    }

    @BuildStep
    void registerRowSetSupport(
            BuildProducer<NativeImageResourceBundleBuildItem> resourceBundleProducer,
            BuildProducer<NativeImageResourceBuildItem> nativeResourceProducer,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClassProducer) {
        resourceBundleProducer.produce(new NativeImageResourceBundleBuildItem("com.sun.rowset.RowSetResourceBundle"));
        nativeResourceProducer.produce(new NativeImageResourceBuildItem("javax/sql/rowset/rowset.properties"));
        reflectiveClassProducer.produce(ReflectiveClassBuildItem.builder(
                "com.sun.rowset.providers.RIOptimisticProvider",
                "com.sun.rowset.providers.RIXMLProvider").build());
    }

    @BuildStep
    void reduceLogging(BuildProducer<LogCategoryBuildItem> logCategories) {
        logCategories.produce(new LogCategoryBuildItem("io.agroal.pool", Level.WARNING));
    }
}
