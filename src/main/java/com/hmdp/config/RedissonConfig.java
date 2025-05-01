package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient(){
        // 配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://182.92.127.179:63790").setPassword("Ljc13512881480!");
        // 根据配置创建并返回RedissonClient对象
        return Redisson.create(config);
    }
//    @Bean
//    public RedissonClient redissonClient2(){
//        // 配置
//        Config config = new Config();
//        config.useSingleServer().setAddress("redis://182.92.127.179:6380").setPassword("Ljc13512881480!");
//        // 根据配置创建并返回RedissonClient对象
//        return Redisson.create(config);
//    }
//    @Bean
//    public RedissonClient redissonClient3(){
//        // 配置
//        Config config = new Config();
//        config.useSingleServer().setAddress("redis://182.92.127.179:6381").setPassword("Ljc13512881480!");
//        // 根据配置创建并返回RedissonClient对象
//        return Redisson.create(config);
//    }
}
