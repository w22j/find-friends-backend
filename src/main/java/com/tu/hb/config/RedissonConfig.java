package com.tu.hb.config;


import lombok.Data;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties("spring.redis")
@Data
public class RedissonConfig {

    private String host;

    private String port;

    private String password;

    @Bean
    public RedissonClient redissonClient() {
        // 1.创建配置
        Config config = new Config();
        String redis = String.format("redis://%s:%s", host, port);
        config.useSingleServer().setAddress(redis).setDatabase(3).setPassword(password);

        // 2.创建Redisson实例
        return Redisson.create(config);
    }
}
