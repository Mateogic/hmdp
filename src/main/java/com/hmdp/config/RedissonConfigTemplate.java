package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfigTemplate {
    @Bean
    public RedissonClient redissonClient(){
        // 配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://your_ip:your_port").setPassword("your_password");
        // 根据配置创建并返回RedissonClient对象
        return Redisson.create(config);
    }
//    @Bean
//    public RedissonClient redissonClient2(){
//        // 配置
//        Config config = new Config();
//        config.useSingleServer().setAddress("redis://your_ip:6380").setPassword("your_password");
//        // 根据配置创建并返回RedissonClient对象
//        return Redisson.create(config);
//    }
//    @Bean
//    public RedissonClient redissonClient3(){
//        // 配置
//        Config config = new Config();
//        config.useSingleServer().setAddress("redis://your_ip:6381").setPassword("your_password");
//        // 根据配置创建并返回RedissonClient对象
//        return Redisson.create(config);
//    }
}
