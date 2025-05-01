package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
@RunWith(SpringRunner.class)
public class HmDianPingApplicationTests {
    @Resource
    private CacheClient cacheClient;
    @Resource// 注入service
    private ShopServiceImpl shopService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private ExecutorService es = Executors.newFixedThreadPool(500);
    @Test// 测试生成订单ID的并发能力
    public void testNextId() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        // 生成订单ID
        Runnable task = () -> {
            for(int i = 0;i<100;i++){
                long id = redisIdWorker.nextId("order");
                System.out.println("ID:" + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for(int i = 0;i<300;i++){// 300*100=30000个ID
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("耗时:" + (end - begin) + "ms");

    }
    @Test
    public void testSaveShop() throws InterruptedException {
        // 使用缓存工具类缓存预热
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }

    @Test
    public void loadShopData(){
        // 1 查询商户信息
        List<Shop> list = shopService.list();
        // 2 按照typeId把商户分组
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3 写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 3.1 获取商户类型typeId
            Long typeId = entry.getKey();
            // 3.2 拼接key
            String key = SHOP_GEO_KEY + typeId;
            // 3.3 获取同类型的商户集合
            List<Shop> shops = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());
            // 3.4 遍历商户集合，转换为GeoLocation
            for (Shop shop : shops) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())
                ));
            }
            // 3.5 一次性写入Redis
            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }
    @Test
    public void testHyperLogLog(){
        String[] values = new String[1000];
        int j = 0;
        // 插入数据
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if(j == 999){
                stringRedisTemplate.opsForHyperLogLog().add("hll",values);
            }
        }
        // 查询数据
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hll");
        System.out.println("count:" + count);
    }
}
