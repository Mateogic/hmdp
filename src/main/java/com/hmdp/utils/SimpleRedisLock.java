package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private String name;// 用于区分不同业务的锁
    private static final String KEY_PREFIX = "lock:";// 锁key的前缀
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";// 用UUID区分不同的JVM
    private StringRedisTemplate stringRedisTemplate;// 注入StringRedisTemplate
    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static{
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();// UUID拼接线程ID
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }
//    @Override
//    public void unLock(){
//        // 调用lua脚本
//        stringRedisTemplate.execute(
//                UNLOCK_SCRIPT,
//                Collections.singletonList(KEY_PREFIX + name),
//                ID_PREFIX + Thread.currentThread().getId());
//    }
    @Override
    public void unLock() {
        // 获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();// UUID拼接线程ID
        // 获取锁中标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        // 判断二者是否一致
        if(threadId.equals(id)){
            // 一致则释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
        // 不一致则无操作
    }
}
