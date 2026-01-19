package com.hmdp.utils;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    // 线程池核心线程数
    private static final int CORE_POOL_SIZE = Runtime.getRuntime().availableProcessors();
    // 线程池最大线程数
    private static final int MAX_POOL_SIZE = CORE_POOL_SIZE * 2;
    // 线程池队列容量
    private static final int QUEUE_CAPACITY = 1000;
    // 线程池线程空闲存活时间
    private static final long KEEP_ALIVE_TIME = 60L;
    // 获取互斥锁的最大重试次数
    private static final int MAX_RETRY_COUNT = 100;
    // 默认随机因子，防止缓存雪崩
    private static final double DEFAULT_RANDOM_FACTOR = 0.1;

    // 锁释放脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("lua/unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final ExecutorService cacheRebuildExecutor;

    public CacheClient() {
        this.cacheRebuildExecutor = createThreadPool();
    }

    /**
     * 创建自定义线程池
     */
    private ExecutorService createThreadPool() {
        return new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_TIME,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new CacheRebuildThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * 通过缓存空值解决缓存穿透的查询方法
     *
     * @param keyPrefix  缓存key前缀
     * @param id         数据id
     * @param clazz      数据类型
     * @param ttl        缓存过期时间
     * @param dbFallback 数据库查询函数
     * @return 查询结果
     */
    public <ID, T> T queryWithPassThrough(
            String keyPrefix,
            ID id,
            Class<T> clazz,
            Duration ttl,
            Function<ID, T> dbFallback
    ) {
        // 参数校验
        validateParams(keyPrefix, id, clazz, ttl, dbFallback);
        String key = buildKey(keyPrefix, id);

        // 1. 查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            // 缓存命中，反序列化并返回
            return JSONUtil.toBean(json, clazz);
        }
        if (json != null) {
            // 说明是之前缓存的空值，返回null
            return null;
        }

        // 2. 缓存不存在，查询数据库
        T data = executeWithFallback(dbFallback, id);
        if (data == null) {
            // 数据库不存在，缓存空值防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", Duration.ofMinutes(2));
            return null;
        }

        // 3. 数据库存在，写入缓存
        set(key, data, ttl, DEFAULT_RANDOM_FACTOR);
        return data;
    }

    /**
     * 通过缓存空值解决缓存穿透的查询方法（List集合版本）
     *
     * @param keyPrefix  缓存key前缀
     * @param id         数据id (如果没有id可以传空字符串)
     * @param clazz      集合元素类型
     * @param ttl        缓存过期时间
     * @param dbFallback 数据库查询函数
     * @return 查询结果
     */
    public <ID, T> java.util.List<T> queryListWithPassThrough(
            String keyPrefix,
            ID id,
            Class<T> clazz,
            Duration ttl,
            Function<ID, java.util.List<T>> dbFallback
    ) {
        // 参数校验
        Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
        Objects.requireNonNull(clazz, "clazz must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        Objects.requireNonNull(dbFallback, "dbFallback must not be null");
        
        String key = buildKey(keyPrefix, id);

        // 1. 查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            // 缓存命中，反序列化并返回
            return JSONUtil.toList(json, clazz);
        }
        if (json != null) {
            // 说明是之前缓存的空值，返回空集合
            return Collections.emptyList();
        }

        // 2. 缓存不存在，查询数据库
        java.util.List<T> data = executeWithFallback(dbFallback, id);
        if (data == null || data.isEmpty()) {
            // 数据库不存在，缓存空值防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", Duration.ofMinutes(2));
            return Collections.emptyList();
        }

        // 3. 数据库存在，写入缓存
        set(key, data, ttl, DEFAULT_RANDOM_FACTOR);
        return data;
    }

    /**
     * 设置缓存值并指定过期时间
     *
     * @param key          缓存key
     * @param value        缓存值
     * @param baseDuration 基础过期时间
     * @param randomFactor 随机因子（如0.2表示±20%的随机范围）
     */
    public void set(String key, Object value, Duration baseDuration, double randomFactor) {
        validateKey(key);
        Objects.requireNonNull(baseDuration, "baseDuration不能为null");

        Duration ttl = addRandomness(baseDuration, randomFactor);
        set(key, value, ttl);
    }

    /**
     * 设置缓存值并指定过期时间
     *
     * @param key   缓存key
     * @param value 缓存值
     * @param ttl   过期时间
     */
     public void set(String key, Object value, Duration ttl) {
        validateKey(key);
        Objects.requireNonNull(ttl, "ttl不能为null");

        try {
            String json = JSONUtil.toJsonStr(value);
            stringRedisTemplate.opsForValue().set(key, json, ttl);
        } catch (Exception e) {
            log.error("缓存写入失败，key：{}", key, e);
            throw new CacheException("缓存写入失败", e);
        }
    }

    /**
     * 为基础过期时间添加随机量，防止缓存雪崩
     *
     * @param baseDuration 基础过期时间
     * @param randomFactor 随机因子（如0.2表示±20%的随机范围）
     * @return 添加随机量后的过期时间
     */
    private Duration addRandomness(Duration baseDuration, double randomFactor) {
        if (randomFactor <= 0) {
            return baseDuration;
        }
        long baseMillis = baseDuration.toMillis();
        long randomRange = (long) (baseMillis * randomFactor);
        // 生成[-randomRange, +randomRange]范围内的随机数
        long randomMillis = (long) (Math.random() * randomRange * 2 - randomRange);
        return Duration.ofMillis(baseMillis + randomMillis);
    }

    /**
     * 通过互斥锁解决缓存击穿的查询方法
     *
     * @param keyPrefix  缓存key前缀
     * @param lockPrefix 互斥锁key前缀
     * @param id         数据id
     * @param clazz      数据类型
     * @param ttl        缓存过期时间
     * @param lockTtl    互斥锁过期时间
     * @param dbFallback 数据库查询函数
     * @return 查询结果
     */
    public <ID, T> T queryWithMutex(
            String keyPrefix,
            String lockPrefix,
            ID id,
            Class<T> clazz,
            Duration ttl,
            Duration lockTtl,
            Function<ID, T> dbFallback
    ) {
        // 参数校验
        validateParams(keyPrefix, id, clazz, ttl, dbFallback);
        Objects.requireNonNull(lockPrefix, "lockPrefix must not be null");
        Objects.requireNonNull(lockTtl, "lockTtl must not be null");

        String key = buildKey(keyPrefix, id);

        // 1. 查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            // 缓存命中，刷新TTL后返回
            stringRedisTemplate.expire(key, ttl);
            return JSONUtil.toBean(json, clazz);
        }
        if (json != null) {
            // 说明是之前缓存的空值，返回null
            return null;
        }

        // 2. 缓存不存在，尝试获取互斥锁
        String lockKey = buildKey(lockPrefix, id);
        String lockId = IdUtil.simpleUUID();
        T data = null;
        int retryCount = 0;
        while (retryCount < MAX_RETRY_COUNT) {
            // 尝试获取互斥锁
            if (tryLock(lockKey, lockId, lockTtl)) {
                try {
                    // Double Check：获取锁成功后，再次检查缓存是否已被其他线程更新
                    json = stringRedisTemplate.opsForValue().get(key);
                    if (StrUtil.isNotBlank(json)) {
                        // 缓存已经被更新，刷新TTL后返回
                        stringRedisTemplate.expire(key, ttl);
                        return JSONUtil.toBean(json, clazz);
                    }
                    if (json != null) {
                        // 说明是之前缓存的空值，返回null
                        return null;
                    }

                    // 缓存未被更新，查询数据库
                    data = executeWithFallback(dbFallback, id);
                    if (data == null) {
                        // 数据库不存在，缓存空值防止缓存穿透
                        stringRedisTemplate.opsForValue().set(key, "", Duration.ofMinutes(2));
                        return null;
                    }

                    // 数据库存在，写入缓存
                    set(key, data, ttl, DEFAULT_RANDOM_FACTOR);
                    return data;
                } finally {
                    // 释放锁
                    unlock(lockKey, lockId);
                }
            }

            // 获取锁失败，休眠并重试
            retryCount++;
            if (retryCount < MAX_RETRY_COUNT) {
                try {
                    // 等待50毫秒后重试
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    // 恢复中断状态
                    Thread.currentThread().interrupt();
                    throw new CacheException("线程被中断", e);
                }
            }
        }

        // 兜底方案：重试多次仍未获取锁，直接查询数据库
        // 说明系统压力极大，为了不让用户等太久，直接查询数据库
        data = executeWithFallback(dbFallback, id);
        if (data != null) {
            // 写入缓存
            set(key, data, ttl, DEFAULT_RANDOM_FACTOR);
        }
        return data;
    }

    /**
     * 反序列化JSON字符串为指定类型对象
     */
    private <T> T deserialize(String json, Class<T> clazz) {
        try {
            return JSONUtil.toBean(json, clazz);
        } catch (Exception e) {
            log.error("缓存反序列化失败，json：{}, class：{}", json, clazz.getName(), e);
            return null;
        }
    }

    /**
     * 带逻辑过期时间的缓存查询（解决缓存击穿）
     *
     * @param keyPrefix  缓存key前缀
     * @param lockPrefix 互斥锁key前缀
     * @param id         数据id
     * @param clazz      数据类型
     * @param ttl        逻辑过期时间
     * @param lockTtl    互斥锁过期时间
     * @param dbFallback 数据库查询函数
     * @return 查询结果
     */
    public <ID, T> T queryWithLogicalExpire (
            String keyPrefix,
            String lockPrefix,
            ID id,
            Class<T> clazz,
            Duration ttl,
            Duration lockTtl,
            Function<ID, T> dbFallback
    ) {
        // 参数校验
        validateParams(keyPrefix, id, clazz, ttl, dbFallback);
        Objects.requireNonNull(lockPrefix, "lockPrefix must not be null");
        Objects.requireNonNull(lockTtl, "lockTtl must not be null");

        String key = buildKey(keyPrefix, id);

        // 1. 查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            // 缓存不存在，首次查询或缓存被删除，同步回源
            T data = executeWithFallback(dbFallback, id);
            if (data != null) {
                saveWithLogicalExpire(key, data, ttl);
            }
            return data;
        }

        // 2. 缓存命中，反序列化为RedisData
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        T data = JSONUtil.toBean(JSONUtil.parseObj(redisData.getData()), clazz);

        // 3. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期，直接返回数据
            return data;
        }

        // 4. 已过期，尝试获取互斥锁
        String lockKey = buildKey(lockPrefix, id);
        String lockId = IdUtil.simpleUUID();
        if (tryLock(lockKey, lockId, lockTtl)) {
            // Double Check：获取锁成功后，再次检查缓存是否已被其他线程更新
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                redisData = JSONUtil.toBean(json, RedisData.class);
                // 如果缓存已经被更新（未过期），刷新逻辑过期时间，然后返回
                if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                    // 更新逻辑过期时间
                    data = JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), clazz);
                    saveWithLogicalExpire(key, data, ttl);
                    unlock(lockKey, lockId); // 释放锁
                    return data;
                }
            }

            // 5. 获取锁成功，异步构建缓存
            cacheRebuildExecutor.submit(() -> {
                try {
                    // 查询数据库
                    T t = executeWithFallback(dbFallback, id);
                    if (t != null) {
                        // 写入缓存
                        this.saveWithLogicalExpire(key, t, ttl);
                    }
                } catch (Exception e) {
                    // 记录日志，避免异常丢失
                    log.error("缓存重建失败，key：{}", key, e);
                } finally {
                    // 释放锁
                    unlock(lockKey, lockId);
                }
            });
        }

        // 6. 返回过期的缓存数据
        return data;
    }

    /**
     * 释放互斥锁
     */
    private void unlock(String key, String lockId) {
        // 使用 Lua 脚本保证释放锁的原子性
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(key),
                lockId
        );
    }

    /**
     * 尝试获取互斥锁
     */
    private boolean tryLock(String key, String lockId, Duration ttl) {
        try {
            Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, lockId, ttl);
            return Boolean.TRUE.equals(success);
        } catch (Exception e) {
            log.error("获取互斥锁失败，key：{}", key, e);
            return false;
        }
    }

    /**
     * 带逻辑过期时间的缓存写入
     */
    private <T> void saveWithLogicalExpire(String key, T data, Duration ttl) {
        try {
            // 1. 封装RedisData
            RedisData redisData = new RedisData();
            redisData.setData(data);
            redisData.setExpireTime(LocalDateTime.now().plus(ttl));

            // 2. 写入Redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
        } catch (Exception e) {
            log.error("缓存写入失败，key：{}", key, e);
            throw new CacheException("缓存写入失败", e);
        }
    }

    /**
     * 参数校验
     */
    private <ID, T> void validateParams(String keyPrefix, ID id, Class<T> clazz, Duration ttl, Function<ID, T> dbFallback) {
        Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(clazz, "clazz must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        Objects.requireNonNull(dbFallback, "dbFallback must not be null");
    }

    /**
     * 构建缓存key
     */
    private <ID> String buildKey(String keyPrefix, ID id) {
        return keyPrefix + id;
    }

    /**
     * 执行数据库查询并处理异常
     */
    private <ID, T> T executeWithFallback(Function<ID, T> dbFallback, ID id) {
        try {
            return dbFallback.apply(id);
        } catch (Exception e) {
            log.debug("数据库查询失败，id：{}", id, e);
            throw new CacheException("数据库查询失败", e);
        }
    }

    /**
     * 自定义线程工厂，设置线程为守护线程并命名
     */
    private static class CacheRebuildThreadFactory implements ThreadFactory {
        private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
        private int counter = 0;

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = defaultFactory.newThread(r);
            thread.setDaemon(true);
            thread.setName("cache-rebuild-thread-" + counter++);
            return thread;
        }
    }

    /**
     * 预热缓存（用于逻辑过期策略）
     *
     * @param keyPrefix  缓存key前缀
     * @param id         数据id
     * @param ttl        逻辑过期时间
     * @param dbFallback 数据库查询函数
     */
    public <ID, T> void warmUpCache(
            String keyPrefix,
            ID id,
            Duration ttl,
            Function<ID, T> dbFallback
    ) {
        // 参数校验
        Objects.requireNonNull(keyPrefix);
        Objects.requireNonNull(id);
        Objects.requireNonNull(ttl);
        Objects.requireNonNull(dbFallback);

        String key = buildKey(keyPrefix, id);
        LocalDateTime currentTime = LocalDateTime.now();

        try {
            T data = dbFallback.apply(id);
            if (data != null) {
                saveWithLogicalExpire(key, data, ttl);
                long cost = Duration.between(currentTime, LocalDateTime.now()).toMillis();
                log.info("缓存预热成功，key：{}，耗时：{} ms", key, cost);
            } else {
                log.warn("缓存预热失败，数据库中不存在对应数据，key：{}", key);
            }
        } catch (Exception e) {
            log.error("缓存预热失败，key：{}", key, e);
            throw new CacheException("缓存预热失败", e);
        }
    }

    /**
     * 批量预热缓存（用于逻辑过期策略）
     *
     * @param keyPrefix  缓存key前缀
     * @param ids        数据id集合
     * @param ttl        逻辑过期时间
     * @param dbFallback 数据库查询函数
     * @return 预热成功的数量
     */
    public <ID, T> int batchWarmUpCache(
            String keyPrefix,
            Collection<ID> ids,
            Duration ttl,
            Function<ID, T> dbFallback
    ) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }

        log.info("开始批量预热缓存, 数量: {}", ids.size());
        int successCount = 0;

        for (ID id : ids) {
            try {
                warmUpCache(keyPrefix, id, ttl, dbFallback);
                successCount++;
            } catch (Exception e) {
                log.error("批量预热失败, id: {}", id, e);
            }
        }

        log.info("批量预热完成, 成功: {}, 失败: {}", successCount, ids.size() - successCount);
        return successCount;
    }

    /**
     * 缓存异常
     */
    public static class CacheException extends RuntimeException {
        public CacheException(String message) {
            super(message);
        }

        public CacheException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 删除缓存
     *
     * @param key 缓存key
     * @return 删除是否成功
     */
    public boolean delete(String key) {
        validateKey(key);

        try {
            return stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.error("缓存删除失败，key：{}", key, e);
            return false;
        }
    }

    /**
     * 批量删除缓存
     *
     * @param keys 缓存key数组
     * @return 删除的数量
     */
    public long deleteBatch(String... keys) {
        if (keys == null || keys.length == 0) {
            return 0;
        }

        try {
            return stringRedisTemplate.delete(Arrays.asList(keys));
        } catch (Exception e) {
            log.error("批量缓存删除失败", e);
            return 0;
        }
    }

    /**
     * 校验 key 是否为空
     */
    private void validateKey(String key) {
        if (StrUtil.isBlank(key)) {
            throw new IllegalArgumentException("Key 必须不能为空");
        }
    }
}
