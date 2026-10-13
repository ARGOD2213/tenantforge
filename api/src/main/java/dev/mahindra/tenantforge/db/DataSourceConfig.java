package dev.mahindra.tenantforge.db;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import dev.mahindra.tenantforge.TenantForgeProperties;
import dev.mahindra.tenantforge.tenancy.TenantCatalog;

import jakarta.persistence.EntityManagerFactory;

/**
 * Two ways into the database:
 * <ul>
 *   <li>{@code dataSource} (primary, used by JPA and {@code jdbcTemplate}): routed to the tenant's database and bound
 *       to the tenant, so Row-Level Security applies to every query.</li>
 *   <li>{@code catalogJdbc}: the shared catalog (tenants, plans, processed Stripe events). Not tenant data.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TenantMigrations tenantMigrations(TenantForgeProperties props) {
        return new TenantMigrations(props);
    }

    /** Runs migrations before Hibernate starts. */
    @Bean
    static EntityManagerFactoryDependsOnPostProcessor entityManagerFactoryDependsOnMigrations() {
        return new EntityManagerFactoryDependsOnPostProcessor("tenantMigrations");
    }

    @Bean(destroyMethod = "close")
    HikariDataSource pooledDataSource(TenantForgeProperties props, TenantMigrations migrationsFirst) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("pooled");
        config.setJdbcUrl(props.db().url());
        config.setUsername(props.db().appUsername());
        config.setPassword(props.db().appPassword());
        config.setMaximumPoolSize(props.db().poolSize());
        return new HikariDataSource(config);
    }

    @Bean
    SiloDataSources siloDataSources(TenantForgeProperties props, TenantMigrations migrations) {
        return new SiloDataSources(props, migrations);
    }

    @Bean
    @Primary
    DataSource dataSource(@Qualifier("pooledDataSource") DataSource pooled, TenantCatalog catalog, SiloDataSources silos) {
        return new TenantAwareDataSource(new TenantRoutingDataSource(pooled, catalog, silos));
    }

    @Bean
    @Primary
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    JdbcTemplate catalogJdbc(@Qualifier("pooledDataSource") DataSource pooled) {
        return new JdbcTemplate(pooled);
    }

    @Bean
    @Primary
    PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    @Bean
    PlatformTransactionManager catalogTransactionManager(@Qualifier("pooledDataSource") DataSource pooled) {
        return new DataSourceTransactionManager(pooled);
    }

    @Bean
    TransactionTemplate catalogTx(@Qualifier("catalogTransactionManager") PlatformTransactionManager tm) {
        return new TransactionTemplate(tm);
    }
}
