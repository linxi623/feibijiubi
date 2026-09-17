-- KEYS[1] current stats hash
-- KEYS[2] dirty set
-- KEYS[3] processed event key
-- KEYS[4] hot videos zset
-- ARGV[1] current field
-- ARGV[2] event delta
-- ARGV[3] vid
-- ARGV[4] hot score delta
-- ARGV[5] processed ttl seconds

local function redisType(key)
    local result = redis.call('TYPE', key)
    if type(result) == 'table' then
        return result['ok']
    end
    return result
end

if redis.call('EXISTS', KEYS[1]) == 0 then
    return 'NEEDS_REBUILD'
end

local currentType = redisType(KEYS[1])
local dirtyType = redisType(KEYS[2])
local hotType = redisType(KEYS[4])

if currentType ~= 'hash' then
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

local currentField = ARGV[1]
if allowedCurrent[currentField] ~= true then
    return 'INVALID_FIELD'
end

local current = tonumber(redis.call('HGET', KEYS[1], currentField))
local delta = tonumber(ARGV[2])
local hotScoreDelta = tonumber(ARGV[4])
local processedTtl = tonumber(ARGV[5])

if current == nil
        or delta == nil
        or hotScoreDelta == nil
        or processedTtl == nil
        or processedTtl <= 0
        or ARGV[3] == nil
        or ARGV[3] == '' then
    return 'NEEDS_REBUILD'
end

-- 先确认统计 Hash 完整，再判断事件幂等。
-- 否则 current 丢失但 processed Key 仍在时会错误返回 DUPLICATE。
if redis.call('EXISTS', KEYS[3]) == 1 then
    return 'DUPLICATE'
end

if current + delta < 0 then
    return 'NEGATIVE_RESULT'
end

-- 所有校验必须位于第一次写命令之前。
redis.call('HINCRBY', KEYS[1], currentField, delta)
redis.call('SADD', KEYS[2], ARGV[3])
redis.call('ZINCRBY', KEYS[4], hotScoreDelta, ARGV[3])
redis.call('SET', KEYS[3], '1', 'EX', processedTtl)
return 'APPLIED'
