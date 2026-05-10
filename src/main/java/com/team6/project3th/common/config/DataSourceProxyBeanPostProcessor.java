package com.team6.project3th.common.config;

import com.team6.project3th.common.logging.SqlLoggingListener;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
public class DataSourceProxyBeanPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<SqlLoggingListener> sqlLoggingListenerProvider;

    public DataSourceProxyBeanPostProcessor(ObjectProvider<SqlLoggingListener> sqlLoggingListenerProvider) {
        this.sqlLoggingListenerProvider = sqlLoggingListenerProvider;
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
                .listener(sqlLoggingListenerProvider.getObject())
                .countQuery()
                .build();
    }
}
