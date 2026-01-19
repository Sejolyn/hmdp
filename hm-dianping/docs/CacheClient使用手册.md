# CacheClient 使用手册

> 工业级 Redis 缓存客户端 - 新手友好的完整指南

---

## 📚 目录

- [1. 快速了解](#1-快速了解)
- [2. 为什么需要它](#2-为什么需要它)
- [3. 三种策略对比](#3-三种策略对比)
- [4. 快速开始](#4-快速开始)
- [5. 详细教程](#5-详细教程)
- [6. 实战案例](#6-实战案例)
- [7. 常见问题](#7-常见问题)

---

## 1. 快速了解

### CacheClient 是什么？

CacheClient 是一个**智能的缓存工具**，帮你解决 Redis 缓存的常见问题。你只需要调用一个方法，它就能自动帮你：

- ✅ 查询缓存
- ✅ 缓存不存在时查数据库
- ✅ 自动写入缓存
- ✅ 防止缓存穿透、击穿、雪崩

**一句话总结：让缓存变简单！**

---

## 2. 为什么需要它

### 问题场景

想象你开发了一个电商网站，用户点击商品详情页：

```java
// ❌ 传统写法（有问题）
public Shop getShop(Long id) {
    // 直接查数据库
    return shopMapper.selectById(id);
}
```

**问题来了：**

#### 🔴 问题 1：数据库压力大
- 1000 个用户同时查同一个商品
- 1000 次数据库查询
- 数据库可能被打垮

#### 🔴 问题 2：响应速度慢
- 每次都查数据库
- 响应时间 100ms+
- 用户体验差

#### 🔴 问题 3：恶意攻击
- 黑客查询不存在的 id（如 id=-1）
- 缓存无法命中
- 每次都打数据库

### 解决方案

```java
// ✅ 使用 CacheClient（完美解决）
public Shop getShop(Long id) {
    return cacheClient.queryWithLogicalExpire(
        "cache:shop:", 
        "lock:shop:", 
        id, 
        Shop.class,
        Duration.ofMinutes(30),
        Duration.ofSeconds(10),
        this::getById
    );
}
```

**效果：**
- 🚀 响应速度：从 100ms → 5ms
- 🛡️ 防止穿透：自动缓存空值
- 🔥 防止击穿：智能加锁
- ⚡ 高并发：支持上万 QPS

---

## 3. 三种策略对比

### 简单对比表

| 策略 | 用途 | 性能 | 适合场景 | 难度 |
|------|------|------|---------|------|
| **空值缓存** | 防穿透 | ⭐⭐⭐ | 普通查询 | ⭐ 简单 |
| **互斥锁** | 防击穿 | ⭐⭐ | 强一致性 | ⭐⭐ 中等 |
| **逻辑过期** | 防击穿 | ⭐⭐⭐⭐⭐ | 热点数据 | ⭐⭐⭐ 稍复杂 |

### 如何选择？

```
你的场景是什么？

├─ 热点数据（如热门商品）
│  └─ 用逻辑过期 ⭐ 推荐
│
├─ 需要强一致性（如用户余额）
│  └─ 用互斥锁
│
└─ 可能有恶意查询（如搜索）
   └─ 用空值缓存
```

**新手建议：90% 的场景用逻辑过期就够了！**

---

## 4. 快速开始

### Step 1: 注入 CacheClient

```java
@Service
public class ShopServiceImpl implements ShopService {

    @Resource
    private CacheClient cacheClient;  // 自动注入
    
}
```

### Step 2: 查询商品（最简单版本）

```java
@Override
public Shop getShop(Long id) {
    // 一行代码搞定！
    return cacheClient.queryWithPassThrough(
        "cache:shop:",           // 缓存前缀
        id,                      // 商品 ID
        Shop.class,              // 返回类型
        Duration.ofMinutes(30),  // 缓存 30 分钟
        this::getById            // 查数据库的方法
    );
}

// 查数据库的方法（已有的）
private Shop getById(Long id) {
    return shopMapper.selectById(id);
}
```

**完成！** 你已经实现了：
- ✅ 缓存查询
- ✅ 自动写缓存
- ✅ 防止穿透

### Step 3: 测试一下

```java
// 第一次查询：从数据库加载，写入缓存（慢）
Shop shop1 = shopService.getShop(1L);  // 耗时：100ms

// 第二次查询：从缓存读取（快！）
Shop shop2 = shopService.getShop(1L);  // 耗时：5ms ⚡
```

---

## 5. 详细教程

### 5.1 策略一：空值缓存（最简单）

#### 什么时候用？

- 普通的查询场景
- 可能有人查询不存在的数据
- 对速度要求不是特别高

#### 代码示例

```java
/**
 * 查询商铺（防止缓存穿透）
 */
public Shop getShop(Long id) {
    return cacheClient.queryWithPassThrough(
        "cache:shop:",           // key 前缀
        id,                      // 商铺 ID
        Shop.class,              // 返回类型
        Duration.ofMinutes(30),  // 缓存 30 分钟
        this::getById            // 查询方法
    );
}
```

#### 工作流程

```
用户请求 id=1
    ↓
查询 Redis: cache:shop:1
    ↓
    ├─ 有数据？ → 直接返回 ✅
    │
    └─ 没数据？
        ↓
        查询数据库
        ↓
        ├─ 数据存在？ → 写入缓存 → 返回
        │
        └─ 数据不存在？ → 缓存空值（""）→ 返回 null
                         ↑
                    防止穿透的关键！
```

#### 优缺点

**优点：**
- 实现简单，容易理解
- 能防止缓存穿透
- 性能还不错

**缺点：**
- 热点数据过期时，可能导致数据库压力大
- 会缓存空值，占用一点内存

---

### 5.2 策略二：互斥锁（强一致性）

#### 什么时候用？

- 对数据一致性要求很高
- 必须读到最新数据
- 并发量不是特别大（几千 QPS 以内）

#### 代码示例

```java
/**
 * 查询用户信息（需要强一致性）
 */
public User getUser(Long userId) {
    return cacheClient.queryWithMutex(
        "cache:user:",           // 缓存 key 前缀
        "lock:user:",            // 锁 key 前缀
        userId,
        User.class,
        Duration.ofMinutes(10),  // 缓存 10 分钟
        Duration.ofSeconds(5),   // 锁 5 秒过期
        this::getUserById
    );
}
```

#### 工作流程

```
100 个并发请求查询 id=1
    ↓
都查询 Redis
    ↓
都没命中（缓存过期）
    ↓
100 个线程尝试获取锁：lock:user:1
    ↓
    ├─ 线程 A 获取锁成功 ✅
    │   ↓
    │   查询数据库
    │   ↓
    │   写入缓存
    │   ↓
    │   释放锁
    │
    └─ 其他 99 个线程获取锁失败 ❌
        ↓
        等待或重试
        ↓
        从缓存读取（线程 A 已写入）
```

#### 优缺点

**优点：**
- 数据强一致性
- 只有一个线程查数据库
- 防止缓存击穿

**缺点：**
- 性能相对较低（需要等锁）
- 高并发下可能排队

---

### 5.3 策略三：逻辑过期（高性能）⭐ 推荐

#### 什么时候用？

- 热点数据（如热门商品、明星店铺）
- 超高并发（上万 QPS）
- 能接受短暂的旧数据（几秒钟）

#### 代码示例

```java
/**
 * 查询热门商品（高性能）
 */
public Goods getGoods(Long id) {
    return cacheClient.queryWithLogicalExpire(
        "cache:goods:",          // 缓存 key 前缀
        "lock:goods:",           // 锁 key 前缀
        id,
        Goods.class,
        Duration.ofMinutes(30),  // 逻辑过期 30 分钟
        Duration.ofSeconds(10),  // 锁 10 秒过期
        this::getGoodsById
    );
}

/**
 * 系统启动时预热缓存（重要！）
 */
@PostConstruct
public void init() {
    // 预热热门商品
    List<Long> hotIds = Arrays.asList(1L, 2L, 3L);
    for (Long id : hotIds) {
        cacheClient.warmUpCache(
            "cache:goods:",
            id,
            Duration.ofMinutes(30),
            this::getGoodsById
        );
    }
    log.info("缓存预热完成");
}
```

#### 工作流程

```
Redis 中的数据：
{
  "data": {"id": 1, "name": "iPhone"},
  "expireTime": "2026-01-19 15:00:00"  ← 逻辑过期时间
}

1000 个并发请求 id=1
    ↓
查询 Redis
    ↓
    ├─ 未过期？ → 直接返回 ⚡ 超快！
    │
    └─ 已过期？
        ↓
        立即返回旧数据 ⚡ 不等待！
        ↓
        同时：尝试获取锁
        ↓
        ├─ 获取锁成功？
        │   ↓
        │   后台异步查询数据库
        │   ↓
        │   更新缓存
        │
        └─ 获取锁失败？
            ↓
            说明其他线程正在更新，不管它
```

#### 为什么推荐？

**场景对比：**

| 策略 | 1000 个并发查询热点数据 |
|------|----------------------|
| 互斥锁 | 999 个线程等待，响应慢 |
| 逻辑过期 | 1000 个线程立即返回！⚡ |

**优点：**
- 🚀 性能极高（所有请求立即返回）
- 🔥 支持超高并发
- ⚡ 用户体验好
- 🛡️ 防止缓存击穿

**缺点：**
- 可能返回几秒钟前的旧数据
- 需要提前预热缓存

**为什么旧数据可以接受？**

对于商品详情、店铺信息这类数据，几秒钟前的数据完全没问题！用户根本感觉不出来。

---

## 6. 实战案例

### 案例 1：商品详情页

```java
@Service
public class GoodsServiceImpl implements GoodsService {
    
    @Resource
    private CacheClient cacheClient;
    
    @Resource
    private GoodsMapper goodsMapper;
    
    /**
     * 查询商品详情（推荐逻辑过期）
     */
    @Override
    public Goods getById(Long id) {
        return cacheClient.queryWithLogicalExpire(
            "cache:goods:",
            "lock:goods:",
            id,
            Goods.class,
            Duration.ofMinutes(30),
            Duration.ofSeconds(10),
            this::queryDB
        );
    }
    
    /**
     * 从数据库查询
     */
    private Goods queryDB(Long id) {
        return goodsMapper.selectById(id);
    }
    
    /**
     * 系统启动时预热（重要！）
     */
    @PostConstruct
    public void warmUp() {
        log.info("开始预热商品缓存...");
        
        // 查询热门商品 ID
        List<Long> hotGoodsIds = goodsMapper.selectHotGoods();
        
        // 批量预热
        int count = cacheClient.batchWarmUpCache(
            "cache:goods:",
            hotGoodsIds,
            Duration.ofMinutes(30),
            this::queryDB
        );
        
        log.info("预热完成，成功 {} 个", count);
    }
}
```

### 案例 2：用户信息查询

```java
@Service
public class UserServiceImpl implements UserService {
    
    @Resource
    private CacheClient cacheClient;
    
    /**
     * 查询用户（推荐互斥锁，保证强一致性）
     */
    @Override
    public User getById(Long userId) {
        return cacheClient.queryWithMutex(
            "cache:user:",
            "lock:user:",
            userId,
            User.class,
            Duration.ofMinutes(10),
            Duration.ofSeconds(5),
            userMapper::selectById
        );
    }
}
```

### 案例 3：搜索功能

```java
@Service
public class SearchServiceImpl implements SearchService {
    
    @Resource
    private CacheClient cacheClient;
    
    /**
     * 搜索商品（推荐空值缓存，防止恶意搜索）
     */
    @Override
    public List<Goods> search(String keyword) {
        return cacheClient.queryWithPassThrough(
            "cache:search:",
            keyword,
            List.class,
            Duration.ofMinutes(5),
            this::searchDB
        );
    }
    
    private List<Goods> searchDB(String keyword) {
        return goodsMapper.search(keyword);
    }
}
```

### 案例 4：更新数据

```java
/**
 * 更新商品（记得删除缓存）
 */
@Transactional
public void updateGoods(Goods goods) {
    // 1. 先更新数据库
    goodsMapper.updateById(goods);
    
    // 2. 再删除缓存
    String key = "cache:goods:" + goods.getId();
    cacheClient.delete(key);
    
    // 3. 如果是热门商品，可以立即预热
    if (goods.isHot()) {
        cacheClient.warmUpCache(
            "cache:goods:",
            goods.getId(),
            Duration.ofMinutes(30),
            this::queryDB
        );
    }
}
```

---

## 7. 常见问题

### Q1: 我该用哪种策略？

**快速决策树：**

```
你的场景？
├─ 热门商品、店铺详情
│  └─ 用逻辑过期 ⭐⭐⭐⭐⭐
│
├─ 用户余额、积分
│  └─ 用互斥锁 ⭐⭐⭐⭐
│
└─ 搜索、普通查询
   └─ 用空值缓存 ⭐⭐⭐
```

**90% 的场景用逻辑过期就对了！**

### Q2: 逻辑过期为什么要预热？

**对比：**

| 情况 | 第一次查询 | 第二次查询 |
|------|----------|----------|
| 不预热 | 100ms（查数据库）| 5ms |
| 预热 | 5ms ⚡ | 5ms |

预热后，从第一次查询开始就很快！

### Q3: 怎么预热缓存？

```java
// 方式 1：系统启动时预热
@PostConstruct
public void init() {
    cacheClient.warmUpCache("cache:goods:", 1L, Duration.ofMinutes(30), this::queryDB);
}

// 方式 2：批量预热
List<Long> ids = Arrays.asList(1L, 2L, 3L);
cacheClient.batchWarmUpCache("cache:goods:", ids, Duration.ofMinutes(30), this::queryDB);

// 方式 3：定时任务预热
@Scheduled(cron = "0 0 * * * ?")  // 每小时
public void scheduledWarmUp() {
    // 预热热门数据
}
```

### Q4: 更新数据时怎么办？

```java
// ✅ 正确做法
@Transactional
public void update(Goods goods) {
    // 1. 先更新数据库
    goodsMapper.updateById(goods);
    
    // 2. 再删除缓存
    cacheClient.delete("cache:goods:" + goods.getId());
}

// ❌ 错误做法
@Transactional
public void update(Goods goods) {
    // 先删缓存，再更新数据库 ← 可能导致脏数据
    cacheClient.delete("cache:goods:" + goods.getId());
    goodsMapper.updateById(goods);
}
```

### Q5: 缓存会占用很多内存吗？

**占用内存估算：**

```
一条商品数据：约 1KB
10000 个商品：约 10MB
100000 个商品：约 100MB

结论：完全可以接受！
```

**省内存技巧：**
- 只缓存热点数据
- 设置合理的过期时间
- 使用 Redis 的 LRU 淘汰策略

### Q6: 如何监控缓存？

```java
// 1. 查看线程池状态
String status = cacheClient.getThreadPoolStatus();
log.info("线程池状态: {}", status);
// 输出：活跃线程: 2, 池大小: 4, 队列大小: 3, 完成任务: 156

// 2. 开启 DEBUG 日志
// application.yml
logging:
  level:
    com.hmdp.utils.CacheClient: DEBUG

// 3. 监控关键指标
// - 缓存命中率 >95%
// - 平均响应时间 <10ms
// - 线程池队列积压 <80%
```

### Q7: 出现问题怎么调试？

```java
// 1. 查看 Redis 中的数据
redis-cli
> GET cache:goods:1
> TTL cache:goods:1

// 2. 查看日志
// 看是否有缓存命中、未命中的日志

// 3. 检查预热是否成功
// 看启动日志是否有 "预热完成" 的提示

// 4. 检查线程池状态
String status = cacheClient.getThreadPoolStatus();
```

---

## 8. 最佳实践总结

### ✅ 推荐做法

1. **热点数据用逻辑过期**
   ```java
   cacheClient.queryWithLogicalExpire(...)
   ```

2. **系统启动时预热**
   ```java
   @PostConstruct
   public void init() {
       cacheClient.batchWarmUpCache(...);
   }
   ```

3. **更新数据先更新 DB 再删缓存**
   ```java
   goodsMapper.updateById(goods);
   cacheClient.delete(key);
   ```

4. **设置合理的过期时间**
   ```java
   热点数据：30-60 分钟
   普通数据：10-20 分钟
   ```

5. **监控线程池状态**
   ```java
   定期检查队列积压情况
   ```

### ❌ 避免做法

1. ❌ 使用逻辑过期但不预热
2. ❌ 所有数据设置相同的过期时间
3. ❌ 先删缓存再更新数据库
4. ❌ 不监控缓存命中率
5. ❌ 在事务中执行缓存操作导致长事务

---

## 9. 完整配置示例

### application.yml

```yaml
spring:
  redis:
    host: localhost
    port: 6379
    database: 0
    lettuce:
      pool:
        max-active: 8
        max-idle: 8
        min-idle: 0

logging:
  level:
    com.hmdp.utils.CacheClient: INFO  # 生产环境用 INFO，调试用 DEBUG
```

### RedisConstants.java

```java
public class RedisConstants {
    // 商品缓存
    public static final String CACHE_GOODS_KEY = "cache:goods:";
    public static final Long CACHE_GOODS_TTL = 30L;
    
    // 商品锁
    public static final String LOCK_GOODS_KEY = "lock:goods:";
    public static final Long LOCK_GOODS_TTL = 10L;
    
    // 用户缓存
    public static final String CACHE_USER_KEY = "cache:user:";
    public static final Long CACHE_USER_TTL = 10L;
}
```

---

## 10. 学习路径

### 第一步：理解概念（1天）
- [ ] 什么是缓存穿透、击穿、雪崩
- [ ] 三种策略的区别
- [ ] 为什么推荐逻辑过期

### 第二步：动手实践（2天）
- [ ] 在自己项目中集成 CacheClient
- [ ] 实现商品查询（用逻辑过期）
- [ ] 添加缓存预热
- [ ] 测试性能对比

### 第三步：深入学习（3天）
- [ ] 理解线程池工作原理
- [ ] 学习 Lua 脚本的作用
- [ ] 监控缓存命中率
- [ ] 学习性能调优

### 第四步：生产实践（持续）
- [ ] 在真实项目中使用
- [ ] 收集监控数据
- [ ] 优化缓存策略
- [ ] 分享使用经验

---

## 11. 快速参考

### 三种策略对比

| 场景 | 推荐策略 | 代码 |
|------|---------|------|
| 商品详情 | 逻辑过期 | `queryWithLogicalExpire(...)` |
| 用户余额 | 互斥锁 | `queryWithMutex(...)` |
| 搜索结果 | 空值缓存 | `queryWithPassThrough(...)` |

### 常用方法

```java
// 查询（三种策略）
queryWithPassThrough()      // 空值缓存
queryWithMutex()            // 互斥锁
queryWithLogicalExpire()    // 逻辑过期 ⭐

// 预热
warmUpCache()               // 预热单个
batchWarmUpCache()          // 批量预热

// 基础操作
set()                       // 设置缓存
get()                       // 获取缓存
delete()                    // 删除缓存
exists()                    // 判断存在

// 监控
getThreadPoolStatus()       // 线程池状态
```

---

## 12. 获取帮助

### 文档资源
- 📖 本手册
- 📖 CacheClient 源码注释
- 📖 Redis 官方文档

### 常见错误

1. **CacheException: Failed to set cache**
   - 原因：Redis 连接失败
   - 解决：检查 Redis 是否启动

2. **返回数据总是 null**
   - 原因：数据库查询方法有问题
   - 解决：检查 `dbFallback` 方法

3. **线程池队列满**
   - 原因：缓存重建太频繁
   - 解决：增加线程池容量或优化查询

---

**🎉 恭喜你完成了学习！现在开始使用 CacheClient 吧！**

有任何问题，随时查看本手册。祝你开发顺利！
