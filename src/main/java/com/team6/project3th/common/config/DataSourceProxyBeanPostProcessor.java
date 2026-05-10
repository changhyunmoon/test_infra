package com.team6.project3th.common.config;

import com.team6.project3th.common.logging.SqlLoggingListener;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
public class DataSourceProxyBeanPostProcessor implements BeanPostProcessor {

    private final SqlLoggingListener sqlLoggingListener;

    public DataSourceProxyBeanPostProcessor(SqlLoggingListener sqlLoggingListener) {
        this.sqlLoggingListener = sqlLoggingListener;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof DataSource dataSource)) {
            return bean;
        }

        if (!"dataSource".equals(beanName)) {
            return bean;
        }

        return ProxyDataSourceBuilder
                .create(dataSource)
                .name("mysqlDataSource")
                .listener(sqlLoggingListener)
                .countQuery()
                .build();
    }
}
