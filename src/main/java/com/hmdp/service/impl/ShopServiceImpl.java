package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    StringRedisTemplate stringRedisTemplate;
    // redis里存储shop的字符串形式
    // 返回数据为封装shop实例的result对象
    @Resource
    private CacheClient cacheClient;// 注入CacheClient对象
    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
//        Shop shop = queryWithPassThrough(id);
        // 调用缓存工具类解决缓存穿透
//        Shop shop = cacheClient.queryWithPassThrough(
//                CACHE_SHOP_KEY, id, Shop.class, myID->getById(myID), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
        // 调用缓存工具类的互斥锁方案解决缓存击穿
        Shop shop = cacheClient.queryWithMutex(
                CACHE_SHOP_KEY, id, Shop.class,this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicExpire(id);
        // 调用缓存工具类的逻辑过期方案解决缓存击穿
//        Shop shop = cacheClient.queryWithLogicalExpire(
//                CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null){
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
    }
    // 创建线程池用于异步重建缓存
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    // 核心逻辑:默认一定会命中缓存
    public Shop queryWithLogicExpire(Long id){// 逻辑过期解决缓存击穿
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis根据id查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if(StrUtil.isBlank(shopJson)){
            // 3.未命中缓存
            return null;
        }
        // 4.命中缓存，将JSON反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);// 从RedisData对象中获取商铺数据
        LocalDateTime expireTime = redisData.getExpireTime();// 获取逻辑过期时间
        // 5.判断是否逻辑过期
        if (expireTime.isAfter(LocalDateTime.now())){
            // 5.1未过期则返回缓存数据
            return shop;
        }
        // 5.2如果已过期则异步重建缓存
        // 6.缓存重建
        // 6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = getLock(lockKey);
        // 6.2判断获取锁是否成功
        if(isLock){
            // 6.3获取成功，开启新线程重建缓存
            // 6.3.1再次查询redis并判断是过期(DoubleCheck)
            shopJson = stringRedisTemplate.opsForValue().get(key);
            redisData = JSONUtil.toBean(shopJson, RedisData.class);
            shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);// 从RedisData对象中获取商铺数据
            expireTime = redisData.getExpireTime();// 获取逻辑过期时间
            if (expireTime.isAfter(LocalDateTime.now())){
                // 6.3.2 未过期则返回缓存数据
                return shop;
            }
            // 6.3.3已过期则开启新线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 6.3.4释放互斥锁
                    releaseLock(lockKey);
                }
            });
        }
        // 6.4无论获取成功还是失败，都要返回过期的缓存数据
        return shop;
    }
    private boolean getLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void releaseLock(String key) {
        stringRedisTemplate.delete(key);
    }
    public Shop queryWithMutex(Long id){// 互斥锁解决缓存击穿
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis根据id查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            // 3.存在则返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中缓存的键值是否为null(穿透)
        if(shopJson != null){
            return null;
        }
        // 4.实现缓存重建
        // 4.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = getLock(lockKey);
            // 4.2判断是否获取到锁
            if (!isLock){
                // 4.3如果失败则休眠一段时间后重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 4.4如果获取互斥锁成功
            // 4.4.1再次查询redis并判断是否存在
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(shopJson)){
                // 4.4.2存在则返回
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            // 4.4.3不存在则查询数据库重建缓存
            shop = getById(id);
            // 模拟查询数据库的延迟
            Thread.sleep(200);
            if (shop == null) {
                // 5.不存在
                // 5.1将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 5.2返回null
                return null;
            }
            // 6.存在则写入redis重建缓存
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 7.释放互斥锁
            releaseLock(lockKey);
        }
        // 8.返回数据
        return shop;
    }
    public Shop queryWithPassThrough(Long id){// 解决缓存穿透问题
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis根据id查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            // 3.存在则返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中缓存的键值是否为null
        if(shopJson != null){
            return null;
        }
        // 4.不存在则根据id查询数据库
        Shop shop = getById(id);
        if (shop == null) {
            // 5.不存在
            // 5.1将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 5.2则返回错误信息
            return null;
        }
        // 6.存在则写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7.返回数据
        return shop;
    }
    // 活动之前添加热点商铺key到redis
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 查询商铺数据
        Shop shop = getById(id);
        // 模拟缓存重建延迟
        Thread.sleep(50);
        // 封装成RedisData对象
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));// 设置逻辑过期时间
        // 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));// 不添加TTL设置为永久生效
    }

    @Override
    @Transactional// 使用事务控制同一个方法中的数据库和缓存操作满足原子性
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("商铺id为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除redis缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryByType(Integer typeId, Integer current, Double x, Double y) {
        // 1 根据是否传入x和y判断是否需要按照距离排序
        if(x == null || y == null){
            // 1.1 不需要坐标查询，查找MySQL数据库
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 1.2 返回数据
            return Result.ok(page.getRecords());
        }
        // 2 计算分页参数(逻辑分页查询)
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        // 3 查询Redis(shopId、distance)，按照距离排序、分页
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)// 永远从0开始截取到end
                );
        // 4 解析出id
        if(results == null ){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from){
            return Result.ok(Collections.emptyList());
        }
        // 4.1 截取from - end部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2 获取商铺id
            String shopId = result.getContent().getName();
            ids.add(Long.parseLong(shopId));
            // 4.3 获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopId, distance);
        });
        // 5 根据id查询Shop
        String idsStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD (id," + idsStr + ")").list();
        for (Shop shop : shops) {
            // 5.1 设置距离
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6 返回数据
        return Result.ok(shops);
    }
}