-- KEYS[1] video:status:v1:{vid}          -- 视频统计的 Hash（点赞数、播放数等）
-- KEYS[2] feed:hot:videos:v1             -- 热点榜的 ZSet（按热度分排序）
-- KEYS[3] video:status:processed:v1:{eventId}  -- 幂等 Key（防止重复消费）

-- ARGV[1] field                           -- 要更新的字段名（如 "likeTimes"）
-- ARGV[2] delta                           -- 变化量（+1 或 -1）
-- ARGV[3] vid member                      -- 视频 ID（作为 ZSet 的 member）
-- ARGV[4] hot score delta                 -- 热点分变化量（如 +5.0）
-- ARGV[5] event ttl seconds               -- 幂等 Key 的过期时间（秒）
-- ARGV[6] aggregate sequence              -- 当前事件的序列号（用于顺序校验）

if redis.call('EXISTS', KEYS[3]) == 1 then
    return 'DUPLICATE'
end

if redis.call('EXISTS', KEYS[1]) == 0 then
    return 'NEEDS_REBUILD'
end

local allowed = {
    playTimes = true,
    likeTimes = true,
    unlikeTimes = true,
    commentTimes = true,
    coinTimes = true,
    shareTimes = true,
    collectTimes = true,
    danmuTimes = true
}

local field = ARGV[1]
if allowed[field] ~= true then
    return 'INVALID_FIELD'
end

local current = tonumber(redis.call('HGET', KEYS[1], field))
local delta = tonumber(ARGV[2])
local incomingSequence = tonumber(ARGV[6])
local lastSequence = tonumber(redis.call('HGET', KEYS[1], 'lastSequence'))

if current == nil or delta == nil
        or incomingSequence == nil or lastSequence == nil then
    return 'NEEDS_REBUILD'
end

if incomingSequence <= lastSequence then
    return 'OLD_SEQUENCE'
end

if incomingSequence ~= lastSequence + 1 then
    return 'SEQUENCE_GAP'
end

if current + delta < 0 then
    return 'NEGATIVE_RESULT'
end

redis.call('HINCRBY', KEYS[1], field, delta)
redis.call('HSET', KEYS[1], 'lastSequence', incomingSequence)
redis.call('ZINCRBY', KEYS[2], ARGV[4], ARGV[3])
redis.call('SET', KEYS[3], '1', 'EX', ARGV[5])
return 'APPLIED'