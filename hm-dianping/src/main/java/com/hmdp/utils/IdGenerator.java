package com.hmdp.utils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 雪花算法全局唯一ID生成器
 *
 * ID结构（64位）：
 * | 符号位(1) | 时间戳(41) | 数据中心ID(5) | 机器ID(5) | 序列号(12) |
 * |    0     |   41位    |     5位      |    5位    |    12位   |
 *
 */
public class IdGenerator {

    /**
     * 开始时间：2026-01-20 00:00:00
     */
    private static final long EPOCH = LocalDateTime.of(2026, 1, 20, 0, 0, 0)
            .toInstant(ZoneOffset.ofHours(8)).toEpochMilli();

    /**
     * 数据中心ID所占位数
     */
    private static final long DATA_CENTER_ID_BITS = 5L;

    /**
     * 机器ID所占位数
     */
    private static final long WORKER_ID_BITS = 5L;

    /**
     * 序列号所占位数
     */
    private static final long SEQUENCE_BITS = 12L;

    /**
     * 数据中心ID最大值
     */
    private static final long MAX_DATA_CENTER_ID = (1L << DATA_CENTER_ID_BITS) - 1;

    /**
     * 机器ID最大值
     */
    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;

    /**
     * 序列号最大值
     */
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    /**
     * 机器ID向左位移
     */
    private static final long WORKER_ID_LEFT_SHIFT = SEQUENCE_BITS;

    /**
     * 数据中心ID向左位移
     */
    private static final long DATA_CENTER_ID_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    /**
     * 时间戳向左位移
     */
    private static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATA_CENTER_ID_BITS;


    /**
     * 业务生成器缓存
     */
    private static final ConcurrentHashMap<String, IdGenerator> GENERATOR_CACHE = new ConcurrentHashMap<>();

    /**
     * 业务ID计数器
     */
    private static final AtomicLong BUSINESS_ID_COUNTER = new AtomicLong(0);

    private final long dataCenterId;
    private final long workerId;

    /**
     * 序列号
     */
    private final AtomicLong sequence = new AtomicLong(0L);

    /**
     * 上次生成ID的时间戳
     */
    private volatile long lastTimestamp = -1L;

    /**
     * 根据业务key获取对应的ID生成器实例
     *
     * @param businessKey
     * @return
     */
    public static IdGenerator getInstance(String businessKey) {
        return GENERATOR_CACHE.computeIfAbsent(businessKey, k -> {
            long businessId = BUSINESS_ID_COUNTER.incrementAndGet();
            long dataCenterId = businessId >> WORKER_ID_BITS; // 高5位作为数据中心ID
            long workerId = businessId & MAX_WORKER_ID; // 低5位作为机器ID
            return new IdGenerator(dataCenterId, workerId);
        });
    }

    /**
     * 创建ID生成器实例
     *
     * @param dataCenterId 数据中心ID
     * @param workerId 机器ID
     * @return
     */
    public IdGenerator create(long dataCenterId, long workerId) {
        return new IdGenerator(dataCenterId, workerId);
    }

    private IdGenerator(long dataCenterId, long workerId) {
        if (dataCenterId < 0 || dataCenterId > MAX_DATA_CENTER_ID) {
            throw new IllegalArgumentException("数据中心ID超出范围: " + dataCenterId);
        }
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("机器ID超出范围: " + workerId);
        }

        this.dataCenterId = dataCenterId;
        this.workerId = workerId;
    }

    /**
     * 生成下一个ID
     *
     * @return
     */
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        if (timestamp < lastTimestamp) {
            throw new IllegalStateException("时钟回拨，拒绝生成ID");
        }

        if (timestamp == lastTimestamp) {
            // 同一毫秒内，序列号递增
            long currentSeq = this.sequence.incrementAndGet() & MAX_SEQUENCE;
            if (currentSeq == 0) {
                // 序列号溢出，等待下一毫秒
                while (timestamp == lastTimestamp) {
                    timestamp = System.currentTimeMillis();
                }
            }
        } else {
            // 不同毫秒内，序列号重置
            this.sequence.set(0L);
        }

        lastTimestamp = timestamp;

        // 组装ID
        return ((timestamp - EPOCH) << (DATA_CENTER_ID_BITS + WORKER_ID_BITS + SEQUENCE_BITS))
                | (dataCenterId << (WORKER_ID_BITS + SEQUENCE_BITS))
                | (workerId << SEQUENCE_BITS)
                | this.sequence.get();
    }

    /**
     * 生成下一个ID的字符串形式
     *
     * @return
     */
    public String nextIdStr() {
        return String.valueOf(nextId());
    }

    /**
     * 解析ID获取时间戳
     *
     * @param id ID
     * @return 生成ID时的时间戳（毫秒）
     */
    public static long getTimestamp(long id) {
        return ((id >> TIMESTAMP_LEFT_SHIFT) + EPOCH);
    }

    /**
     * 解析ID获取数据中心ID
     *
     * @param id ID
     * @return 数据中心ID
     */
    public static long getDataCenterId(long id) {
        return (id >> DATA_CENTER_ID_LEFT_SHIFT) & MAX_DATA_CENTER_ID;
    }

    /**
     * 解析ID获取机器ID
     *
     * @param id ID
     * @return 机器ID
     */
    public static long getWorkerId(long id) {
        return (id >> WORKER_ID_LEFT_SHIFT) & MAX_WORKER_ID;
    }

    /**
     * 解析ID获取序列号
     *
     * @param id ID
     * @return 序列号
     */
    public static long getSequence(long id) {
        return id & MAX_SEQUENCE;
    }

    /**
     * 获取生成器信息
     *
     * @return 生成器信息字符串
     */
    public String getInfo() {
        return String.format("SnowflakeIdGenerator{dcId=%d, workerId=%d}", dataCenterId, workerId);
    }
}
