package com.hmdp.utils;

public interface ILock {
    /**
     *
     * @param timeoutSec 锁的过期时间，之后自动释放
     * @return true表示获取成功，false表示获取失败
     */
    boolean tryLock(long timeoutSec);
    void unLock();
}