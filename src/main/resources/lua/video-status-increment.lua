-- KEYS[1] video:status:v1:{vid}
-- KEYS[2] feed:hot:videos:v2
-- KEYS[3] video:status:processed:v1:{eventId}
-- ARGV[1] field
-- ARGV[2] delta
-- ARGV[3] vid member
-- ARGV[4] hot score delta
-- ARGV[5] event ttl seconds
-- ARGV[6] aggregate sequence

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