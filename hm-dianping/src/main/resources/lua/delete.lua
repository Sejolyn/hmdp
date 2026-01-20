-- KEYS[1] 代表锁的key， ARGV[1] 代表当前线程的标识
-- 获取锁中的标识，判断与当前线程的标识是否一致
if (redis.call('GET', KEYS[1]) == ARGV[1]) then
    -- 一致则删除锁
    return redis.call('DEL', KEYS[1])
end
-- 不一致则返回0
return 0