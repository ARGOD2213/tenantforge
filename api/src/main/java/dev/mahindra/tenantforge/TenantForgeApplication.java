package dev.mahindra.tenantforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TenantForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(TenantForgeApplication.class, args);
    }
}
