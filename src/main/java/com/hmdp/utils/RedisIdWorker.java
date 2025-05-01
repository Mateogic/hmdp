package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    private static final int COUNT_BITS = 32;// 序列号位数
    @Resource// 注入StringRedisTemplate
    private StringRedisTemplate stringRedisTemplate;
    // 2022年1月1日的时间戳，以此为基准
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    public long nextId(String keyPrefix) {// 针对不同业务添加不同订单ID前缀
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowTimestamp = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowTimestamp - BEGIN_TIMESTAMP;
        // 2.生成序列号
        // 2.1获取当前日期精确到天，避免超过32位上限，并且便于统计
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2自增长
        long count = stringRedisTemplate.opsForValue().increment("icr" + keyPrefix + ":" + date);
        // 3.拼接并返回
        return timestamp << COUNT_BITS | count;// 位运算结合或运算拼接填充
    }
}
