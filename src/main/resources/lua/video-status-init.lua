-- KEYS[1] current stats hash
-- KEYS[2] dirty set
-- KEYS[3] hot videos zset
-- ARGV[1] vid
-- ARGV[2] hot score
-- ARGV[3..10] current values
-- ARGV[11] has pending events

local function redisType(key)
    local result = redis.call('TYPE', key)
    if type(result) == 'table' then
        return result['ok']
    end
    return result
end

if redis.call('EXISTS', KEYS[1]) == 1 then
    return 'ALREADY_INITIALIZED'
end

local currentType = redisType(KEYS[1])
local dirtyType = redisType(KEYS[2])
local hotType = redisType(KEYS[3])

if currentType ~= 'none' then
    return 'INVALID_REDIS_TYPE'
end
if dirtyType ~= 'none' and dirtyType ~= 'set' then
    return 'INVALID_REDIS_TYPE'
end
if hotType ~= 'none' and hotType ~= 'zset' then
    return 'INVALID_REDIS_TYPE'
end

if ARGV[1] == nil or ARGV[1] == '' then
    return 'INVALID_ARGUMENT'
end

local hotScore = tonumber(ARGV[2])
if hotScore == nil or hotScore ~= hotScore then
    return 'INVALID_ARGUMENT'
end

local values = {}
for i = 3, 10 do
    values[i] = tonumber(ARGV[i])
    if values[i] == nil or values[i] < 0 then
        return 'INVALID_ARGUMENT'
    end
end

local hasPending = ARGV[11]
if hasPending ~= 'true' and hasPending ~= 'false' then
    return 'INVALID_ARGUMENT'
end

-- 所有校验完成后再写入。
redis.call('HSET', KEYS[1],
    'vid', ARGV[1],
    'playTimes', ARGV[3],
    'likeTimes', ARGV[4],
    'unlikeTimes', ARGV[5],
    'commentTimes', ARGV[6],
    'coinTimes', ARGV[7],
    'shareTimes', ARGV[8],
    'collectTimes', ARGV[9],
    'danmuTimes', ARGV[10])

if hasPending == 'true' then
    redis.call('SADD', KEYS[2], ARGV[1])
else
    redis.call('SREM', KEYS[2], ARGV[1])
end

redis.call('ZADD', KEYS[3], hotScore, ARGV[1])
return 'INITIALIZED'
