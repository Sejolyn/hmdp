package com.hmdp.utils;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * 工业级 Redis 缓存客户端
 * <p>
 * 核心特性：
 * <ul>
 *   <li>三种缓存策略：穿透防护、互斥锁、逻辑过期</li>
 *   <li>缓存雪崩防护：随机过期时间</li>
 *   <li>高性能：异步重建、线程池复用</li>
 *   <li>高可用：优雅降级、异常隔离</li>
 *   <li>可观测：详细日志、性能指标</li>
 *   <li>可扩展：策略模式、泛型设计</li>
 * </ul>
 *
 * @author sejolyn
 * @version 2.0
 * @since 2026-01-19
 */
@Slf4j
@Component
public class CacheClient {

    /**
     * 默认空值缓存时间
     */
    private static final Duration DEFAULT_NULL_TTL = Duration.ofMinutes(2);

    /**
     * 默认随机因子
     */
    private static final double DEFAULT_RANDOM_FACTOR = 0.1;

    /**
     * 默认锁重试次数
     */
    private static final int DEFAULT_LOCK_RETRY_TIMES = 3;

    /**
     * 默认锁重试间隔（毫秒）
     */
    private static final long DEFAULT_LOCK_RETRY_INTERVAL = 50;

    /**
     * 线程池核心线程数
     */
    private static final int CORE_POOL_SIZE = Runtime.getRuntime().availableProcessors();

    /**
     * 线程池最大线程数
     */
    private static final int MAX_POOL_SIZE = CORE_POOL_SIZE * 2;

    /**
     * 线程池队列容量
     */
    private static final int QUEUE_CAPACITY = 1000;

    /**
     * 线程池空闲超时时间
     */
    private static final long KEEP_ALIVE_TIME = 60L;

    /**
     * 线程池关闭超时时间
     */
    private static final long SHUTDOWN_TIMEOUT = 30L;


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 缓存重建线程池
     * 使用有界队列防止OOM，使用CallerRunsPolicy保证任务不丢失
     */
    private final ExecutorService cacheRebuildExecutor;

    /**
     * 锁释放脚本：保证原子性（比较+删除）
     * 防止误删其他线程的锁
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>();

    static {
        UNLOCK_SCRIPT.setLocation(new org.springframework.core.io.ClassPathResource("lua/unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public CacheClient() {
        this.cacheRebuildExecutor = createThreadPool();
    }

    /**
     * 创建优化的线程池
     */
    private ThreadPoolExecutor createThreadPool() {
        return new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_TIME,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                new CacheRebuildThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * 自定义线程工厂：设置线程名称和守护线程
     */
    private static class CacheRebuildThreadFactory implements ThreadFactory {
        private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
        private int counter = 0;

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = defaultFactory.newThread(r);
            thread.setName("cache-rebuild-" + counter++);
            thread.setDaemon(true);
            return thread;
        }
    }

    /**
     * 初始化回调
     */
    @PostConstruct
    public void init() {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) cacheRebuildExecutor;
        log.info("CacheClient 初始化完成 - 核心线程数: {}, 最大线程数: {}, 队列容量: {}",
                executor.getCorePoolSize(),
                executor.getMaximumPoolSize(),
                QUEUE_CAPACITY);
    }

    /**
     * 销毁回调：优雅关闭线程池
     */
    @PreDestroy
    public void destroy() {
        log.info("CacheClient 开始关闭线程池...");
        shutdownThreadPool();
        log.info("CacheClient 线程池已关闭");
    }

    /**
     * 优雅关闭线程池
     */
    private void shutdownThreadPool() {
        cacheRebuildExecutor.shutdown();
        try {
            if (!cacheRebuildExecutor.awaitTermination(SHUTDOWN_TIMEOUT, TimeUnit.SECONDS)) {
                log.warn("线程池未能在 {} 秒内关闭，强制关闭...", SHUTDOWN_TIMEOUT);
                cacheRebuildExecutor.shutdownNow();

                if (!cacheRebuildExecutor.awaitTermination(SHUTDOWN_TIMEOUT, TimeUnit.SECONDS)) {
                    log.error("线程池强制关闭失败");
                }
            }
        } catch (InterruptedException e) {
            log.error("关闭线程池被中断", e);
            cacheRebuildExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 获取线程池状态（用于监控）
     */
    public String getThreadPoolStatus() {
        if (cacheRebuildExecutor instanceof ThreadPoolExecutor executor) {
            return String.format(
                    "活跃线程: %d, 池大小: %d, 队列大小: %d, 完成任务: %d",
                    executor.getActiveCount(),
                    executor.getPoolSize(),
                    executor.getQueue().size(),
                    executor.getCompletedTaskCount()
            );
        }
        return "Unknown";
    }

    /**
     * 设置缓存（带随机过期时间，防止雪崩）
     *
     * @param key          键
     * @param value        值
     * @param baseDuration 基础过期时间
     * @param randomFactor 随机因子（如 0.1 表示 ±10% 波动）
     */
    public void set(String key, Object value, Duration baseDuration, double randomFactor) {
        validateKey(key);
        Objects.requireNonNull(baseDuration, "baseDuration must not be null");

        Duration ttl = addRandomness(baseDuration, randomFactor);
        set(key, value, ttl);
    }

    /**
     * 设置缓存
     *
     * @param key   键
     * @param value 值
     * @param ttl   过期时间
     */
    public void set(String key, Object value, Duration ttl) {
        validateKey(key);
        Objects.requireNonNull(ttl, "ttl must not be null");

        try {
            String jsonValue = JSONUtil.toJsonStr(value);
            stringRedisTemplate.opsForValue().set(key, jsonValue, ttl);
        } catch (Exception e) {
            log.error("设置缓存失败, key: {}", key, e);
            throw new CacheException("Failed to set cache", e);
        }
    }

    /**
     * 获取缓存
     *
     * @param key   键
     * @param clazz 类型
     * @return 缓存值
     */
    public <T> T get(String key, Class<T> clazz) {
        validateKey(key);
        Objects.requireNonNull(clazz, "clazz must not be null");

        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isBlank(json)) {
                return null;
            }
            return JSONUtil.toBean(json, clazz);
        } catch (Exception e) {
            log.error("获取缓存失败, key: {}", key, e);
            return null;
        }
    }

    /**
     * 删除缓存
     *
     * @param key 键
     * @return 是否删除成功
     */
    public boolean delete(String key) {
        validateKey(key);

        try {
            return stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.error("删除缓存失败, key: {}", key, e);
            return false;
        }
    }

    /**
     * 判断键是否存在
     *
     * @param key 键
     * @return 是否存在
     */
    public boolean exists(String key) {
        validateKey(key);

        try {
            return stringRedisTemplate.hasKey(key);
        } catch (Exception e) {
            log.error("判断缓存存在失败, key: {}", key, e);
            return false;
        }
    }

    /**
     * 批量删除缓存
     *
     * @param keys 键集合
     * @return 删除数量
     */
    public long deleteMulti(String... keys) {
        if (keys == null || keys.length == 0) {
            return 0;
        }

        try {
            return stringRedisTemplate.delete(java.util.Arrays.asList(keys));
        } catch (Exception e) {
            log.error("批量删除缓存失败", e);
            return 0;
        }
    }

    // ==================== 缓存穿透策略：空值缓存 ====================

    /**
     * 查询缓存（解决缓存穿透：空值缓存方案）
     * <p>
     * 适用场景：数据量适中，对一致性要求不高，可能存在恶意查询不存在的数据
     * <p>
     * 工作原理：
     * 1. 查询缓存，命中直接返回
     * 2. 未命中查询数据库
     * 3. 数据库不存在则缓存空值（防止穿透）
     * 4. 数据库存在则缓存真实值
     *
     * @param keyPrefix  key 前缀
     * @param id         id
     * @param clazz      返回类型
     * @param ttl        缓存过期时间
     * @param dbFallback 数据库查询函数
     * @param <T>        返回类型
     * @param <ID>       id 类型
     * @return 查询结果
     */
    public <T, ID> T queryWithPassThrough(
            String keyPrefix,
            ID id,
            Class<T> clazz,
            Duration ttl,
            Function<ID, T> dbFallback) {

        validateParams(keyPrefix, id, clazz, ttl, dbFallback);
        String key = buildKey(keyPrefix, id);

        long startTime = System.currentTimeMillis();
        try {
            // 1. 从 Redis 查询
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                logCacheHit(key, startTime);
                return deserialize(json, clazz);
            }

            // 2. 命中空值缓存
            if (json != null) {
                logCacheHit(key + " (null)", startTime);
                return null;
            }

            // 3. 未命中，查询数据库
            logCacheMiss(key, startTime);
            T data = executeWithFallback(dbFallback, id, key);

            if (data == null) {
                // 数据库不存在，缓存空值防止穿透
                cacheNullValue(key, DEFAULT_NULL_TTL);
                return null;
            }

            // 4. 写入缓存（带随机过期时间，防止雪崩）
            set(key, data, ttl, DEFAULT_RANDOM_FACTOR);
            return data;

        } catch (Exception e) {
            log.error("缓存穿透查询失败, key: {}", key, e);
            // 降级：直接查询数据库
            return dbFallback.apply(id);
        }
    }

    // ==================== 缓存击穿策略：互斥锁 ====================

    /**
     * 查询缓存（解决缓存击穿：互斥锁方案）
     * <p>
     * 适用场景：数据一致性要求高，并发量不高，可以接受短暂的阻塞等待
     * <p>
     * 工作原理：
     * 1. 查询缓存，命中直接返回
     * 2. 未命中尝试获取互斥锁
     * 3. 获取锁成功：Double Check + 查DB + 写缓存
     * 4. 获取锁失败：重试或降级查DB
     *
     * @param keyPrefix  key 前缀
     * @param lockPrefix 锁 key 前缀
     * @param id         id
     * @param clazz      返回类型
     * @param ttl        缓存过期时间
     * @param lockTtl    锁过期时间
     * @param dbFallback 数据库查询函数
     * @param <T>        返回类型
     * @param <ID>       id 类型
     * @return 查询结果
     */
    public <T, ID> T queryWithMutex(
            String keyPrefix,
            String lockPrefix,
            ID id,
            Class<T> clazz,
            Duration ttl,
            Duration lockTtl,
            Function<ID, T> dbFallback) {

        validateParams(keyPrefix, id, clazz, ttl, dbFallback);
        Objects.requireNonNull(lockPrefix, "lockPrefix must not be null");
        Objects.requireNonNull(lockTtl, "lockTtl must not be null");

        String key = buildKey(keyPrefix, id);
        String lockKey = buildKey(lockPrefix, id);

        long startTime = System.currentTimeMillis();

        // 1. 查询缓存
        T cachedData = getFromCache(key, clazz);
        if (cachedData != null) {
            logCacheHit(key, startTime);
            return cachedData;
        }

        // 2. 获取互斥锁
        String lockValue = generateLockValue();
        try {
            if (!acquireLock(lockKey, lockValue, lockTtl)) {
                // 获取锁失败，降级处理
                log.warn("获取锁失败，降级直接查询数据库, key: {}", key);
                return dbFallback.apply(id);
            }

            // 3. Double Check 缓存
            cachedData = getFromCache(key, clazz);
            if (cachedData != null) {
                log.info("Double Check 缓存命中, key: {}", key);
                return cachedData;
            }

            // 4. 查询数据库
            logCacheMiss(key, startTime);
            T data = executeWithFallback(dbFallback, id, key);

            if (data == null) {
                cacheNullValue(key, DEFAULT_NULL_TTL);
                return null;
            }

            // 5. 写入缓存
            set(key, data, ttl, DEFAULT_RANDOM_FACTOR);
            return data;

        } catch (Exception e) {
            log.error("互斥锁查询失败, key: {}", key, e);
            return dbFallback.apply(id);
        } finally {
            // 6. 释放锁
            releaseLock(lockKey, lockValue);
        }
    }

    /**
     * 获取互斥锁（带重试）
     */
    private boolean acquireLock(String lockKey, String lockValue, Duration lockTtl) {
        for (int i = 0; i < DEFAULT_LOCK_RETRY_TIMES; i++) {
            if (tryLock(lockKey, lockValue, lockTtl)) {
                return true;
            }

            if (i < DEFAULT_LOCK_RETRY_TIMES - 1) {
                try {
                    TimeUnit.MILLISECONDS.sleep(DEFAULT_LOCK_RETRY_INTERVAL);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("获取锁被中断, lockKey: {}", lockKey);
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 查询缓存（解决缓存击穿：逻辑过期方案）
     *
     * @param keyPrefix  key 前缀
     * @param lockPrefix 锁 key 前缀
     * @param id         id
     * @param clazz      返回类型
     * @param ttl        逻辑过期时间
     * @param lockTtl    锁过期时间
     * @param dbFallback 数据库查询函数
     * @param <T>        返回类型
     * @param <ID>       id 类型
     * @return 查询结果
     */
    public <T, ID> T queryWithLogicalExpire(
            String keyPrefix,
            String lockPrefix,
            ID id,
            Class<T> clazz,
            Duration ttl,
            Duration lockTtl,
            Function<ID, T> dbFallback) {

        validateParams(keyPrefix, id, clazz, ttl, dbFallback);
        Objects.requireNonNull(lockPrefix, "lockPrefix must not be null");
        Objects.requireNonNull(lockTtl, "lockTtl must not be null");

        String key = buildKey(keyPrefix, id);
        long startTime = System.currentTimeMillis();

        try {
            // 1. 查询缓存
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isBlank(json)) {
                // 缓存不存在，首次查询或缓存被删除，同步回源
                logCacheMiss(key, startTime);
                T data = executeWithFallback(dbFallback, id, key);
                if (data != null) {
                    saveWithLogicalExpire(key, data, ttl);
                }
                return data;
            }

            // 2. 反序列化为 RedisData
            RedisData redisData = JSONUtil.toBean(json, RedisData.class);
            T data = deserializeRedisData(json, clazz);
            LocalDateTime expireTime = redisData.getExpireTime();

            // 3. 判断是否过期
            if (expireTime.isAfter(LocalDateTime.now())) {
                // 未过期，直接返回
                logCacheHit(key, startTime);
                return data;
            }

            // 4. 已过期，尝试异步重建
            String lockKey = buildKey(lockPrefix, id);
            asyncRebuildCache(lockKey, key, id, ttl, lockTtl, dbFallback);

            // 返回过期数据（保证高性能）
            log.debug("返回过期数据，异步重建中, key: {}", key);
            return data;

        } catch (Exception e) {
            log.error("逻辑过期查询失败, key: {}", key, e);
            // 降级：直接查询数据库
            return dbFallback.apply(id);
        }
    }

    /**
     * 异步重建缓存
     */
    private <T, ID> void asyncRebuildCache(
            String lockKey,
            String cacheKey,
            ID id,
            Duration ttl,
            Duration lockTtl,
            Function<ID, T> dbFallback) {

        String lockValue = generateLockValue();

        if (!tryLock(lockKey, lockValue, lockTtl)) {
            // 获取锁失败，说明其他线程正在重建
            log.debug("其他线程正在重建缓存, key: {}", cacheKey);
            return;
        }

        // 获取锁成功，提交异步任务
        log.info("开始异步重建缓存, key: {}", cacheKey);
        cacheRebuildExecutor.submit(() -> rebuildCacheTask(lockKey, lockValue, cacheKey, id, ttl, dbFallback));
    }

    /**
     * 重建缓存任务
     */
    private <T, ID> void rebuildCacheTask(
            String lockKey,
            String lockValue,
            String cacheKey,
            ID id,
            Duration ttl,
            Function<ID, T> dbFallback) {

        long startTime = System.currentTimeMillis();
        try {
            // Double Check：检查是否已被其他线程重建
            String json = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StrUtil.isNotBlank(json)) {
                RedisData redisData = JSONUtil.toBean(json, RedisData.class);
                if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                    log.debug("缓存已被其他线程重建, key: {}", cacheKey);
                    return;
                }
            }

            // 查询数据库并重建缓存
            T data = dbFallback.apply(id);
            if (data != null) {
                saveWithLogicalExpire(cacheKey, data, ttl);
                long cost = System.currentTimeMillis() - startTime;
                log.info("缓存重建成功, key: {}, 耗时: {}ms", cacheKey, cost);
            } else {
                // 数据不存在，删除缓存
                delete(cacheKey);
                log.warn("数据不存在，删除缓存, key: {}", cacheKey);
            }
        } catch (Exception e) {
            log.error("重建缓存任务失败, key: {}", cacheKey, e);
        } finally {
            releaseLock(lockKey, lockValue);
        }
    }

    /**
     * 反序列化 RedisData
     */
    private <T> T deserializeRedisData(String json, Class<T> clazz) {
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        Object dataObj = redisData.getData();
        return JSONUtil.toBean(JSONUtil.parseObj(dataObj), clazz);
    }

    /**
     * 保存带逻辑过期时间的缓存
     *
     * @param key   缓存键
     * @param value 缓存值
     * @param ttl   逻辑过期时间
     */
    public <T> void saveWithLogicalExpire(String key, T value, Duration ttl) {
        validateKey(key);
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");

        try {
            RedisData redisData = new RedisData();
            redisData.setData(value);
            redisData.setExpireTime(LocalDateTime.now().plus(ttl));

            String json = JSONUtil.toJsonStr(redisData);
            stringRedisTemplate.opsForValue().set(key, json);

            log.debug("保存逻辑过期缓存成功, key: {}, expireTime: {}", key, redisData.getExpireTime());
        } catch (Exception e) {
            log.error("保存逻辑过期缓存失败, key: {}", key, e);
            throw new CacheException("Failed to save cache with logical expire", e);
        }
    }

    /**
     * 预热缓存（逻辑过期方案）
     *
     * @param keyPrefix  key 前缀
     * @param id         id
     * @param ttl        逻辑过期时间
     * @param dbFallback 数据库查询函数
     * @param <T>        返回类型
     * @param <ID>       id 类型
     */
    public <T, ID> void warmUpCache(
            String keyPrefix,
            ID id,
            Duration ttl,
            Function<ID, T> dbFallback) {

        Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        Objects.requireNonNull(dbFallback, "dbFallback must not be null");

        String key = buildKey(keyPrefix, id);
        long startTime = System.currentTimeMillis();

        try {
            T data = dbFallback.apply(id);
            if (data != null) {
                saveWithLogicalExpire(key, data, ttl);
                long cost = System.currentTimeMillis() - startTime;
                log.info("预热缓存成功, key: {}, 耗时: {}ms", key, cost);
            } else {
                log.warn("预热缓存失败，数据不存在, key: {}", key);
            }
        } catch (Exception e) {
            log.error("预热缓存异常, key: {}", key, e);
            throw new CacheException("Failed to warm up cache", e);
        }
    }

    /**
     * 批量预热缓存
     *
     * @param keyPrefix  key 前缀
     * @param ids        id 列表
     * @param ttl        逻辑过期时间
     * @param dbFallback 数据库查询函数
     * @param <T>        返回类型
     * @param <ID>       id 类型
     * @return 成功预热的数量
     */
    public <T, ID> int batchWarmUpCache(
            String keyPrefix,
            java.util.Collection<ID> ids,
            Duration ttl,
            Function<ID, T> dbFallback) {

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
     * 尝试获取锁
     *
     * @param key   锁的 key
     * @param value 锁的值（用于安全释放）
     * @param ttl   锁的过期时间
     * @return 是否获取成功
     */
    private boolean tryLock(String key, String value, Duration ttl) {
        try {
            Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(
                    key, value, ttl.toMillis(), TimeUnit.MILLISECONDS
            );
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("获取锁失败, key: {}", key, e);
            return false;
        }
    }

    /**
     * 释放锁（使用 Lua 脚本保证原子性）
     *
     * @param key   锁的 key
     * @param value 锁的值
     */
    private void releaseLock(String key, String value) {
        try {
            stringRedisTemplate.execute(
                    UNLOCK_SCRIPT,
                    Collections.singletonList(key),
                    value
            );
            log.debug("释放锁成功, key: {}", key);
        } catch (Exception e) {
            log.error("释放锁失败, key: {}", key, e);
        }
    }

    /**
     * 添加随机过期时间（防止缓存雪崩）
     *
     * @param baseDuration 基础过期时间
     * @param randomFactor 随机因子（如 0.1 表示 ±10%）
     * @return 带随机波动的过期时间
     */
    private Duration addRandomness(Duration baseDuration, double randomFactor) {
        if (randomFactor <= 0) {
            return baseDuration;
        }
        long baseMillis = baseDuration.toMillis();
        long randomRange = (long) (baseMillis * randomFactor);
        long randomMillis = (long) (Math.random() * randomRange * 2 - randomRange);
        return Duration.ofMillis(baseMillis + randomMillis);
    }

    /**
     * 生成锁的值（使用纳秒时间戳 + 线程ID）
     */
    private String generateLockValue() {
        return System.nanoTime() + "-" + Thread.currentThread().getId();
    }

    /**
     * 构建缓存键
     */
    private <ID> String buildKey(String prefix, ID id) {
        return prefix + id;
    }

    /**
     * 从缓存获取数据
     */
    private <T> T getFromCache(String key, Class<T> clazz) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                return deserialize(json, clazz);
            }
            // 空值缓存判断
            if (json != null) {
                return null;
            }
        } catch (Exception e) {
            log.error("从缓存获取数据失败, key: {}", key, e);
        }
        return null;
    }

    /**
     * 缓存空值
     */
    private void cacheNullValue(String key, Duration ttl) {
        try {
            stringRedisTemplate.opsForValue().set(key, "", ttl);
            log.debug("缓存空值, key: {}", key);
        } catch (Exception e) {
            log.error("缓存空值失败, key: {}", key, e);
        }
    }

    /**
     * 执行数据库查询（带异常处理）
     */
    private <T, ID> T executeWithFallback(Function<ID, T> dbFallback, ID id, String key) {
        try {
            return dbFallback.apply(id);
        } catch (Exception e) {
            log.error("查询数据库失败, key: {}, id: {}", key, id, e);
            throw new CacheException("Database query failed", e);
        }
    }

    /**
     * 反序列化 JSON
     */
    private <T> T deserialize(String json, Class<T> clazz) {
        try {
            return JSONUtil.toBean(json, clazz);
        } catch (Exception e) {
            log.error("反序列化失败, json: {}, class: {}", json, clazz.getName(), e);
            return null;
        }
    }

    /**
     * 参数校验
     */
    private <T, ID> void validateParams(String keyPrefix, ID id, Class<T> clazz, Duration ttl, Function<ID, T> dbFallback) {
        Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(clazz, "clazz must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        Objects.requireNonNull(dbFallback, "dbFallback must not be null");
    }

    /**
     * 键校验
     */
    private void validateKey(String key) {
        if (StrUtil.isBlank(key)) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }

    /**
     * 记录缓存命中日志
     */
    private void logCacheHit(String key, long startTime) {
        long cost = System.currentTimeMillis() - startTime;
        log.debug("缓存命中, key: {}, 耗时: {}ms", key, cost);
    }

    /**
     * 记录缓存未命中日志
     */
    private void logCacheMiss(String key, long startTime) {
        long cost = System.currentTimeMillis() - startTime;
        log.debug("缓存未命中, key: {}, 耗时: {}ms", key, cost);
    }

    // ==================== 自定义异常 ====================

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
}
