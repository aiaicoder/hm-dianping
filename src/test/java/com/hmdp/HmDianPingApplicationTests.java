package com.hmdp;

import ch.qos.logback.core.net.SyslogOutputStream;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.service.IShopService;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.ShopTypeServiceImpl;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.CacheClientRe;
import com.hmdp.utils.RedisIdWorkerRe;
import io.lettuce.core.api.async.RedisGeoAsyncCommands;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import sun.rmi.runtime.Log;

import javax.annotation.Resource;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

@SpringBootTest
@Slf4j
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private CacheClientRe cacheClientRe;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RedisIdWorkerRe redisIdWorkerRe;
    @Resource
    private IUserService UserServiceImpl;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private RLock lock;


    @Test
    public void test(){
        //shopService.redisShop2(1L, 2L);
        Shop shop = shopService.getById(1);
        cacheClientRe.LogicalExpire(CACHE_SHOP_KEY+1L, shop,10L, TimeUnit.SECONDS);
    }
    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    public void test2(){
        Runnable task = () ->{
            for (int i = 0; i < 100; i++) {
                long order = redisIdWorkerRe.getId("order");
                System.out.println("id="+order);
            }
        };
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
    }

    @BeforeEach
    void setUp() {
       lock = redissonClient.getLock("order");
    }

    @Test

    void method1(){
        //???????????????
        boolean isLock = false;
        try {
            isLock = lock.tryLock(10L,20,TimeUnit.SECONDS);
            //Thread.sleep(30000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (!isLock){
            log.error("??????????????? ?????????1");
            return;
        }

        try {
            log.info("??????????????? ??????1");
            method2();
            log.info("?????????????????? ?????????1");
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            log.warn("??????????????? ?????????1");
            lock.unlock();
        }
    }
    void method2() throws InterruptedException {
        boolean isLock = lock.tryLock(10L,TimeUnit.SECONDS);
        if(!isLock){
            log.error("???????????????????????????2");
            return;
        }

        try {
            log.info("???????????????");
            log.info("??????????????????...2");
        }finally {
            log.warn("????????????????????????2");
            lock.unlock();
        }
    }


    @Test
    void testMultiLogin() throws IOException {
        List<User> userList = UserServiceImpl.lambdaQuery().last("limit 500").list();
        for (User user : userList) {
            String token = UUID.randomUUID().toString(true);
            UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);

            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create().ignoreNullValue().setFieldValueEditor((fieldName,fieladValue) -> fieladValue.toString()));
            String tokenKey = LOGIN_USER_KEY+token;
            stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
            stringRedisTemplate.expire(tokenKey, 30,TimeUnit.MINUTES);
        }
        Set<String> keys = stringRedisTemplate.keys(LOGIN_USER_KEY+"*");
        @Cleanup FileWriter fileWriter = new FileWriter(System.getProperty("user.dir") + "\\tokens.txt");
        @Cleanup BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);
        assert keys != null;
        for (String key : keys) {
            String token = key.substring(LOGIN_USER_KEY.length());
            String text = token + "\n";
            bufferedWriter.write(text);

    }
    }

    @Test
    void addShop(){
        //???????????????????????????
        List<Shop> list = shopService.list();
        //??????map??????????????????????????????
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //??????map
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            //?????????????????????
            Long typeId = entry.getKey();
            String key = "geo:shop:typeId" + typeId;
            //??????????????????????????????
            List<Shop> shops = entry.getValue();
            //GeoLocation,?????????????????????????????????????????????
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());
            shops.stream().forEach(shop -> {
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(),shop.getY())));
            });
            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }

}
