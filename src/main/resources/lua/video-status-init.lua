-- KEYS[1] current stats hash
-- KEYS[2] pending delta hash
-- KEYS[3] dirty set
-- KEYS[4] hot videos zset
-- ARGV[1] vid
-- ARGV[2] generation
-- ARGV[3] hot score
-- ARGV[4..11] current values
-- ARGV[12..19] delta values

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
local deltaType = redisType(KEYS[2])
local dirtyType = redisType(KEYS[3])
local hotType = redisType(KEYS[4])

if currentType ~= 'none' then
    return 'INVALID_REDIS_TYPE'
end
if deltaType ~= 'none' and deltaType ~= 'hash' then
    return 'INVALID_REDIS_TYPE'
end
if dirtyType ~= 'none' and dirtyType ~= 'set' then
    return 'INVALID_REDIS_TYPE'
end
if hotType ~= 'none' and hotType ~= 'zset' then
    return 'INVALID_REDIS_TYPE'
end

if ARGV[1] == nil or ARGV[1] == ''
        or ARGV[2] == nil or ARGV[2] == '' then
    return 'INVALID_ARGUMENT'
end

local hotScore = tonumber(ARGV[3])
if hotScore == nil or hotScore ~= hotScore then
    return 'INVALID_ARGUMENT'
end

local values = {}
for i = 4, 19 do
    values[i] = tonumber(ARGV[i])
    if values[i] == nil then
        return 'INVALID_ARGUMENT'
    end
end

for i = 4, 11 do
    if values[i] < 0 then
        return 'INVALID_ARGUMENT'
    end
end

local hasDelta = false
for i = 12, 19 do
    if values[i] ~= 0 then
        hasDelta = true
        break
    end
end

-- 所有校验完成后才开始写入。
redis.call('HSET', KEYS[1],
    'vid', ARGV[1],
    'generation', ARGV[2],
    'playTimes', ARGV[4],
    'likeTimes', ARGV[5],
    'unlikeTimes', ARGV[6],
    'commentTimes', ARGV[7],
    'coinTimes', ARGV[8],
    'shareTimes', ARGV[9],
    'collectTimes', ARGV[10],
    'danmuTimes', ARGV[11])

-- current 不存在时，旧 delta 只能是异常残留；在同一 Lua 中覆盖为快照值。
redis.call('DEL', KEYS[2])
redis.call('HSET', KEYS[2],
    'playDelta', ARGV[12],
    'likeDelta', ARGV[13],
    'unlikeDelta', ARGV[14],
    'commentDelta', ARGV[15],
    'coinDelta', ARGV[16],
    'shareDelta', ARGV[17],
    'collectDelta', ARGV[18],
    'danmuDelta', ARGV[19])

if hasDelta then
    redis.call('SADD', KEYS[3], ARGV[1])
else
    redis.call('SREM', KEYS[3], ARGV[1])
end

redis.call('ZADD', KEYS[4], ARGV[3], ARGV[1])
return 'INITIALIZED'