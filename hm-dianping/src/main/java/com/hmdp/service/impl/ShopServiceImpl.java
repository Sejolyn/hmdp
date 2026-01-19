package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.Duration;

import static com.hmdp.utils.RedisUtils.getShopCacheKey;

/**
 * <p>
 *  服务实现类 - 展示三种缓存策略的使用
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 方案一：缓存空值解决缓存穿透（适用于数据量适中，对一致性要求不高）
        // Shop shop = queryWithPassThrough(id);

        // 方案二：互斥锁解决缓存击穿（适用于数据一致性要求高，并发量不高）
        // Shop shop = queryWithMutex(id);

        // 方案三：逻辑过期解决缓存击穿（推荐，适用于热点数据，并发量极高）
        Shop shop = queryWithLogicalExpire(id);
        
        if (shop == null) {
            return Result.fail("商铺不存在！");
        }
        return Result.ok(shop);
    }

    /**
     * 策略一：使用缓存工具类解决缓存穿透（空值缓存）
     */
    public Shop queryWithPassThrough(Long id) {
        return cacheClient.queryWithPassThrough(
                RedisConstants.CACHE_SHOP_KEY,
                id,
                Shop.class,
                Duration.ofMinutes(RedisConstants.CACHE_SHOP_TTL),
                this::getById
        );
    }

    /**
     * 策略二：使用缓存工具类解决缓存击穿（互斥锁）
     */
    public Shop queryWithMutex(Long id) {
        return cacheClient.queryWithMutex(
                RedisConstants.CACHE_SHOP_KEY,
                RedisConstants.LOCK_SHOP_KEY,
                id,
                Shop.class,
                Duration.ofMinutes(RedisConstants.CACHE_SHOP_TTL),
                Duration.ofSeconds(RedisConstants.LOCK_SHOP_TTL),
                this::getById
        );
    }

    /**
     * 策略三：使用缓存工具类解决缓存击穿（逻辑过期 - 推荐）
     */
    public Shop queryWithLogicalExpire(Long id) {
        return cacheClient.queryWithLogicalExpire(
                RedisConstants.CACHE_SHOP_KEY,
                RedisConstants.LOCK_SHOP_KEY,
                id,
                Shop.class,
                Duration.ofMinutes(RedisConstants.CACHE_SHOP_TTL),
                Duration.ofSeconds(RedisConstants.LOCK_SHOP_TTL),
                this::getById
        );
    }

    /**
     * 预热缓存（用于逻辑过期策略）
     * @param id 商铺ID
     */
    public void warmUpShopCache(Long id) {
        cacheClient.warmUpCache(
                RedisConstants.CACHE_SHOP_KEY,
                id,
                Duration.ofMinutes(RedisConstants.CACHE_SHOP_TTL),
                this::getById
        );
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空！");
        }
        
        // 1. 更新数据库
        updateById(shop);

        // 2. 删除缓存（双写一致性策略：先更新数据库，再删除缓存）
        String cacheKey = RedisConstants.CACHE_SHOP_KEY + id;
        boolean deleted = cacheClient.delete(cacheKey);
        
        log.info("更新商铺成功，已删除缓存，商铺ID: {}", id);
        
        // 3. 如果使用逻辑过期策略，可以选择立即预热缓存
        // warmUpShopCache(id);
        
        return Result.ok();
    }
}
