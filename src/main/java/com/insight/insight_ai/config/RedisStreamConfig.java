package com.insight.insight_ai.config;

import com.insight.insight_ai.listener.DocumentStreamListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

import java.time.Duration;

@Configuration
public class RedisStreamConfig {

    @Bean
    public StreamMessageListenerContainer<String, ObjectRecord<String, String>> streamMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            DocumentStreamListener streamListener) {

        // 1. 配置监听器容器 (每 100 毫秒去 Redis 拉取一次)
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, ObjectRecord<String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofMillis(100))
                        .targetType(String.class)
                        .build();

        StreamMessageListenerContainer<String, ObjectRecord<String, String>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);

        // 2. 绑定队列和消费者
        // StreamOffset.create(队列名, ReadOffset.lastConsumed()) 表示从最新的一条消息开始读
        container.receive(
                StreamOffset.create("insight:document:stream", ReadOffset.latest()),
                streamListener
        );

        // 3. 启动后台监听线程
        container.start();
        return container;
    }
}