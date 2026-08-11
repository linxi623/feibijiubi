-- KEYS[1]: 限流计数器的Redis Key
-- ARGV[1]: 窗口长度，单位为秒

local current = redis.call('INCR', KEYS[1])
local ttl = redis.call('TTL', KEYS[1])

-- current == 1：正常情况下，这是该窗口内的第一次请求
-- ttl < 0：兼容并修复历史上可能遗留的“没有过期时间”的计数器
if current == 1 or ttl < 0 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end

return current