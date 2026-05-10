package com.team6.project3th.monitoring;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class TestTableInitializer {

    @Bean
    public ApplicationRunner initializeTestTable(JdbcTemplate jdbcTemplate) {
        return args -> jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS test (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    name VARCHAR(100) NOT NULL,
                    created_at DATETIME(6) NOT NULL,
                    PRIMARY KEY (id)
                )
                """);
    }
}
