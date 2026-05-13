package com.team6.project3th.common.datasource;

import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class DataSourceProxyConfig {

    @Bean
    public BeanPostProcessor dataSourceProxyPostProcessor(
            QueryMetricsListener queryMetricsListener
    ) {
        return new BeanPostProcessor() {

            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof DataSource dataSource)) {
                    return bean;
                }

                if (bean instanceof ProxyDataSource) {
                    return bean;
                }

                return ProxyDataSourceBuilder
                        .create(dataSource)
                        .name(beanName)
                        .listener(queryMetricsListener)
                        .build();
            }
        };
    }
}

