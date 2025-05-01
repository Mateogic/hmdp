package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource// 注入RedisIdWorker
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource// 注入RedissonClient
    private RedissonClient redissonClient;

    // 加载Lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 定义线程池(单线程)
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct// 标记为初始化方法，在构造方法执行后执行
    public void init(){
        // 启动线程
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{
    String queueName  = "stream.orders";// 消息队列名称
    @Override
    public void run(){
        while(true){// 不断地从消息队列中获取订单信息
            try {
                // 1 从消息队列中获取订单信息XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(queueName, ReadOffset.lastConsumed())
                );// 因为不一定仅读取一个，所以返回值为List集合
                // 2 判断是否获取消息成功
                if(list == null || list.isEmpty()){
                    // 2.1 若获取失败，则继续获取
                    continue;
                }
                // 2.2 若获取成功，则解析消息中的订单信息
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                // 将消息中的订单信息解析为VoucherOrder对象
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                // 3 处理消息执行异步下单操作
                handleVoucherOrder(voucherOrder);
                // 4 ACK确认处理过的消息 SACK stream.orders g1 id
                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
            } catch (Exception e) {
                log.error("订单处理异常", e);
                handlePendingList();
            }
        }
    }
        private void handlePendingList() {
            while(true){// 不断地从消息队列中获取订单信息
                try {
                    // 1 从pending-list中获取订单信息XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );// 因为不一定仅读取一个，所以返回值为List集合
                    if(list == null || list.isEmpty()){
                    // 2 判断是否获取消息成功
                        // 2.1 若获取失败，说明pending-list中没有消息，结束循环
                        break;
                    }
                    // 2.2 若获取成功，则解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    // 将消息中的订单信息解析为VoucherOrder对象
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3 处理消息执行异步下单操作
                    handleVoucherOrder(voucherOrder);
                    // 4 ACK确认处理过的消息 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }
    // 定义阻塞队列
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    // 线程任务
//    private class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run(){
//            while(true){// 不断地从阻塞队列中获取订单信息，执行异步下单操作
//                try {
//                    // 1 从阻塞队列中获取订单信息
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    // 2 保存订单信息到数据库
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("订单处理异常", e);
//                }
//            }
//        }
//    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 获取用户id(子线程不能从主线程的ThreadLocal获取，应该从VourcherOrder中获取)
        Long userId = voucherOrder.getUserId();
        // 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();// 多种参数重载
        if (!isLock) {
            log.error("不允许重复下单");
            return ;// 异步处理无需返回数据
        }
        try {
            // 获取代理对象(事务)(同样地，子线程无法获取主线程中的代理对象，应该从主线程获取)
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    // 基于基于Redis和Lua脚本实现秒杀资格判断
    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.1 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 1.2 生成订单id
        long orderId = redisIdWorker.nextId("order");// 生成订单id
        // 2 执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId)
        );
        // 3 判断结果是否为0
        int r = result.intValue();
        if(r!=0){
            // 3.1 非0，秒杀不成功
            return Result.fail(r==1?"库存不足":"请勿重复提交订单");
        }
        // 4 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 5 返回订单id
        return Result.ok(orderId);
    }
    /** public Result seckillVoucher(Long voucherId) {
        // 1 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 2 执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        // 3 判断结果是否为0
        int r = result.intValue();
        if(r!=0){
            // 3.1 非0，秒杀不成功
            return Result.fail(r==1?"库存不足":"请勿重复提交订单");
        }
        // 3.2 是0，秒杀成功
        // 4 将订单信息保存到阻塞队列
        // 4.1 封装订单信息
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");// 生成订单id
        voucherOrder.setId(orderId);// 设置订单id
        voucherOrder.setUserId(userId);// 设置用户id
        voucherOrder.setVoucherId(voucherId);// 设置优惠券id
        // 4.2 保存到阻塞队列
        orderTasks.add(voucherOrder);
        // 5 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 5 返回订单id
        return Result.ok(orderId);
    } */

    @Transactional// 事务
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5.一人一单
        // 5.1根据用户id和优惠券id联合查询是否存在相关订单
        Long userId = voucherOrder.getUserId();// 用户id
        int count = query().eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2判断是否存在
        if (count > 0) {
            // 存在订单
            log.error("请勿重复提交订单");
            return ;
        }
        // 6.扣减库存
        Boolean isSuccess = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)// 乐观锁，判断当前库存是否仍然大于0
                .update();
        if (!isSuccess) {// 扣减库存失败
            log.error("库存不足");
            return ;
        }
        // 7.创建订单
        save(voucherOrder);
    }
    /**
     * @Override public Result seckillVoucher(Long voucherId) {
     * // 1.查询优惠券信息
     * SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
     * // 2.判断抢购是否开始
     * if(voucher.getBeginTime().isAfter(LocalDateTime.now())){// 抢购未开始
     * return Result.fail("抢购尚未开始");
     * <p>
     * }
     * // 3.判断抢购是否结束
     * if(voucher.getEndTime().isBefore(LocalDateTime.now())){// 抢购已结束
     * return Result.fail("抢购已经结束");
     * <p>
     * }
     * // 4.判断库存是否充足
     * if(voucher.getStock()<=0){// 库存不足
     * return Result.fail("库存不足");
     * }
     * Long userId = UserHolder.getUser().getId();// 用户id
     * //        synchronized(userId.toString().intern()) {// 先获取锁再调用方法
     * // 创建锁对象，键名为lock:order:用户id，键值为当前线程id
     * //        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
     * // 创建锁对象
     * RLock lock = redissonClient.getLock("lock:order:" + userId);
     * // 获取锁
     * boolean isLock = lock.tryLock();// 多种参数重载
     * if(!isLock){
     * return Result.fail("请勿重复提交订单");
     * }
     * try {
     * // 获取代理对象(事务)
     * IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
     * return proxy.createVoucherOrder(voucherId);
     * } finally {
     * // 释放锁
     * lock.unlock();
     * }
     * //        }// 方法结束提交事务完毕后释放锁
     * }
     */
}
