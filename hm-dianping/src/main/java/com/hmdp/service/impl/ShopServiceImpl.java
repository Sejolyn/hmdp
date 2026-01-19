package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisUtils.getShopCacheKey;
import static com.hmdp.utils.RedisUtils.getShopLockKey;

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
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ShopMapper shopMapper;

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空！");
        }
        // 1. 更新数据库
        updateById(shop);

        // 2. 删除缓存
        stringRedisTemplate.delete(getShopCacheKey(id));
        return Result.ok();
    }

    public Shop queryPassThrough(Long id) {
        String cacheKey = getShopCacheKey(id);
        // 1. 从 Redis 查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotBlank(shopJson)) {
            // 命中，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 缓存命中，但值为空，说明数据库中不存在该商铺
        if (shopJson == null) {
            return null;
        }

        // 2. 缓存未命中，查询数据库
        Shop shop = getById(id);
        if (shop == null) {
            // 数据库中不存在该商铺，缓存空值
            stringRedisTemplate.opsForValue().set(cacheKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 3. 存在，将商铺信息写入 Redis
        stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

    public Shop queryWithMutex(Long id) {
        String cacheKey = getShopCacheKey(id);
        // 1. 从 Redis 查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotBlank(shopJson)) {
            // 命中，刷新过期时间并返回
            stringRedisTemplate.expire(cacheKey, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 空值缓存
        if (shopJson != null) {
            return null;
        }

        // 2. 缓存未命中，查询数据库
        // 2.1 获取互斥锁
        String lockKey = getShopLockKey(id);
        Shop shop = null;
        try {
            Boolean lock = tryLock(lockKey);
            if (!lock) {
                // 获取锁失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // 2.2 获取锁成功，查询数据库
            shop = getById(id);
            if (shop == null) {
                // 数据库中不存在该商铺，缓存空值
                stringRedisTemplate.opsForValue().set(cacheKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 3. 存在，将商铺信息写入 Redis
            stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 4. 释放锁
            unlock(lockKey);
        }

        return shop;
    }

    @Override
    public Result queryById(Long id) {
        // 缓存空值解决缓存穿透
        // Shop shop = queryPassThrough(id);

        // 互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("商铺不存在！");
        }
        return Result.ok(shop);
    }

    Boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
    }

    void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
