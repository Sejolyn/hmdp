package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;

/**
 * 基于Redis的全局唯一ID生成器
 *
 * ID结构（64位）：
 * | 时间戳(32) | 序列号(32) |
 *
 * 支持约136年（2^32秒 ≈ 136年）
 * 每天每业务可生成约42亿（2^32）个ID
 */
@Slf4j
@Component
public class RedisIdWorker {

    /**
     * 开始时间：2026-01-20 00:00:00（UTC时间戳）
     */
    private static final long BEGIN_TIMESTAMP = LocalDateTime.of(2026, 1, 20, 0, 0, 0)
            .toEpochSecond(ZoneOffset.UTC);

    /**
     * 序列号位数
     */
    private static final int SEQUENCE_BITS = 32;

    private final StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 生成指定业务的下一个ID
     *
     * @param keyPrefix 业务前缀（如 "order", "voucher"）
     * @return 生成的ID
     */
    public long nextId(String keyPrefix) {
        // 1. 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2. 生成序列号
        // 2.1. 获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2. Redis自增长，保证唯一和递增
        String key = "icr:" + keyPrefix + ":" + date;
        Long count = stringRedisTemplate.opsForValue().increment(key);

        // 3. 拼接并返回：timestamp << 32 | count
        return (timestamp << SEQUENCE_BITS) | count;
    }

    /**
     * 解析ID获取时间戳
     *
     * @param id ID
     * @return 时间戳（秒）
     */
    public static long getTimestamp(long id) {
        return (id >> SEQUENCE_BITS) + BEGIN_TIMESTAMP;
    }

    /**
     * 解析ID获取序列号
     *
     * @param id ID
     * @return 序列号
     */
    public static long getSequence(long id) {
        return id & 0xFFFFFFFFL;
    }
}
