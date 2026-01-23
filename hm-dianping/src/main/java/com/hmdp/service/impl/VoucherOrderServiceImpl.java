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
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    /**
     * 判断库存是否充足 && 判断用户是否重复抢购 && 扣减库存 && 保存订单
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 线程池
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 消息队列名称
     */
    private static final String QUEUE_NAME = "stream.orders";

    /**
     * 订单处理器
     */
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 从消息队列中获取订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(1)),
                            StreamOffset.create(QUEUE_NAME, ReadOffset.lastConsumed())
                    );

                    // 2. 判断是否获取到消息
                    if (list == null || list.isEmpty()) {
                        // 如果没有，说明没有消息，继续下一次循环
                        continue;
                    }

                    // 3. 解析订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4. 创建订单
                    handleVoucherOrder(voucherOrder);
                    // 5. 确认消息已处理
                    stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }
    }

    /**
     * 处理 pending-list 中的消息
     */
    private void handlePendingList() {
        while (true) {
            try {
                // 1. 从pending-list中获取订单信息
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(QUEUE_NAME, ReadOffset.from("0"))
                );

                // 2. 判断是否获取到消息
                if (list == null || list.isEmpty()) {
                    // 如果没有，说明没有消息，结束循环
                    break;
                }

                // 3. 解析订单信息
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> values = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                // 4. 创建订单
                handleVoucherOrder(voucherOrder);
                // 5. 确认消息已处理
                stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, "g1", record.getId());
            } catch (Exception e) {
                log.error("处理pending-list订单异常", e);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interruptedException) {
                    interruptedException.printStackTrace();
                }
            }
        }
    }

    /**
     * 代理对象
     */
    private IVoucherOrderService proxy;

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1. 获取用户 id
        Long userId = voucherOrder.getUserId();
        // 2. 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 3. 获取锁
        boolean success = lock.tryLock();
        if (!success) {
            log.error("不允许重复下单");
            return;
        }
        try {
            // 4. 创建订单
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 5. 释放锁
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户 id
        Long userId = UserHolder.getUser().getId();
        // 获取订单 id
        long orderId = redisIdWorker.nextId("order");
        // 1. 执行 Lua 脚本
        Long res = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );

        // 2. 判断结果是否为 0
        int resInt = res.intValue();
        if (resInt != 0) {
            // 2.1 不为 0，代表没有购买资格
            return switch (resInt) {
                case 1 -> Result.fail("库存不足");
                case 2 -> Result.fail("不能重复购买");
                default -> Result.fail("抢购失败");
            };
        }

        // 2.2 获取代理对象
        this.proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 3. 返回订单 id
        return Result.ok(orderId);
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5. 一人一单
        Long userId = voucherOrder.getUserId();
        // 判断是否已经购买过
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("用户已经购买过一次");
            return;
        }

        // 6. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0) // 乐观锁，stock > 0 时才能扣减成功
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }

        save(voucherOrder);
    }
}
