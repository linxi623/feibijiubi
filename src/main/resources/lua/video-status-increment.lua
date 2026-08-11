-- KEYS[1] current stats hash
-- KEYS[2] pending delta hash
-- KEYS[3] dirty set
-- KEYS[4] processed event key
-- KEYS[5] hot videos zset
-- ARGV[1] current field
-- ARGV[2] delta field
-- ARGV[3] delta
-- ARGV[4] vid
-- ARGV[5] hot score delta
-- ARGV[6] processed ttl seconds

local function redisType(key)
    local result = redis.call('TYPE', key)
    -- 此时 result 可能是：
        --   - 字符串: "hash"
        --   - Table:  { ok = "hash" }

    if type(result) == 'table' then
        return result['ok']
    end
    return result
end

if redis.call('EXISTS', KEYS[1]) == 0
        or redis.call('EXISTS', KEYS[2]) == 0 then
    return 'NEEDS_REBUILD'
end

-- 检验key的类型是否正确
local currentType = redisType(KEYS[1])
local deltaType = redisType(KEYS[2])
local dirtyType = redisType(KEYS[3])
local hotType = redisType(KEYS[5])

if currentType ~= 'hash' or deltaType ~= 'hash' then
    return 'INVALID_REDIS_TYPE'
end
if dirtyType ~= 'none' and dirtyType ~= 'set' then
    return 'INVALID_REDIS_TYPE'
end
if hotType ~= 'none' and hotType ~= 'zset' then
    return 'INVALID_REDIS_TYPE'
end

local allowedCurrent = {
    playTimes = true,
    likeTimes = true,
    unlikeTimes = true,
    commentTimes = true,
    coinTimes = true,
    shareTimes = true,
    collectTimes = true,
    danmuTimes = true
}

local allowedDelta = {
    playDelta = true,
    likeDelta = true,
    unlikeDelta = true,
    commentDelta = true,
    coinDelta = true,
    shareDelta = true,
    collectDelta = true,
    danmuDelta = true
}
-- 判断在hash中字段是否存在
local currentField = ARGV[1]
local deltaField = ARGV[2]
if allowedCurrent[currentField] ~= true
        or allowedDelta[deltaField] ~= true then
    return 'INVALID_FIELD'
end

local current = tonumber(redis.call('HGET', KEYS[1], currentField))
local pending = tonumber(redis.call('HGET', KEYS[2], deltaField))
local delta = tonumber(ARGV[3])
local hotScoreDelta = tonumber(ARGV[5])
local processedTtl = tonumber(ARGV[6])

if current == nil
        or pending == nil
        or delta == nil
        or hotScoreDelta == nil
        or processedTtl == nil
        or processedTtl <= 0
        or ARGV[4] == nil
        or ARGV[4] == '' then
    return 'NEEDS_REBUILD'
end

-- 先确认承载统计的 Hash 完整，再判断事件幂等。
-- 否则 current/delta 丢失但 processed Key 仍在时会错误返回 DUPLICATE。
if redis.call('EXISTS', KEYS[4]) == 1 then
    return 'DUPLICATE'
end

if current + delta < 0 then
    return 'NEGATIVE_RESULT'
end

-- 所有校验必须位于第一次写命令之前。
redis.call('HINCRBY', KEYS[1], currentField, delta)
redis.call('HINCRBY', KEYS[2], deltaField, delta)
redis.call('SADD', KEYS[3], ARGV[4])
redis.call('ZINCRBY', KEYS[5], hotScoreDelta, ARGV[4])
redis.call('SET', KEYS[4], '1', 'EX', processedTtl)
return 'APPLIED'