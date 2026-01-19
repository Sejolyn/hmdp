-- Redis 分布式锁释放脚本
-- 功能：原子性地比较锁的值并删除锁
-- 参数：
--   KEYS[1]: 锁的 key
--   ARGV[1]: 锁的 value（用于验证锁的持有者）
-- 返回值：
--   1: 成功释放锁
--   0: 锁不存在或锁的值不匹配（防止误删其他线程的锁）

if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
