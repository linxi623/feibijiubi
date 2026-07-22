-- KEYS[1] current stats hash
-- KEYS[2] pending delta hash
-- KEYS[3] dirty set
-- KEYS[4] flush-cleaned key
-- ARGV[1] vid
-- ARGV[2] expected generation
-- ARGV[3..10] flushed deltas

local fields = {
    'playDelta',
    'likeDelta',
    'unlikeDelta',
    'commentDelta',
    'coinDelta',
    'shareDelta',
    'collectDelta',
    'danmuDelta'
}

local function redisType(key)
    local result = redis.call('TYPE', key)
    if type(result) == 'table' then
        return result['ok']
    end
    return result
end

if redis.call('EXISTS', KEYS[4]) == 1 then
    return 'DUPLICATE_CLEANUP'
end

if redis.call('EXISTS', KEYS[1]) == 0
        or redis.call('EXISTS', KEYS[2]) == 0 then
    return 'NEEDS_REBUILD'
end

if redisType(KEYS[1]) ~= 'hash'
        or redisType(KEYS[2]) ~= 'hash' then
    return 'NEEDS_REBUILD'
end

local dirtyType = redisType(KEYS[3])
if dirtyType ~= 'none' and dirtyType ~= 'set' then
    return 'NEEDS_REBUILD'
end

local generation = redis.call('HGET', KEYS[1], 'generation')
if generation == false then
    return 'NEEDS_REBUILD'
end
if generation ~= ARGV[2] then
    return 'GENERATION_CHANGED'
end

if ARGV[1] == nil or ARGV[1] == ''
        or ARGV[2] == nil or ARGV[2] == '' then
    return 'INVALID_ARGUMENT'
end

local currentValues = {}
local flushedValues = {}
for i = 1, #fields do
    currentValues[i] = tonumber(
        redis.call('HGET', KEYS[2], fields[i])
    )
    flushedValues[i] = tonumber(ARGV[i + 2])
    if currentValues[i] == nil or flushedValues[i] == nil then
        return 'INVALID_ARGUMENT'
    end
end

-- 所有校验完成后才开始写入。
local hasRemaining = false
for i = 1, #fields do
    local value = redis.call(
        'HINCRBY',
        KEYS[2],
        fields[i],
        -flushedValues[i]
    )
    if value ~= 0 then
        hasRemaining = true
    end
end

if hasRemaining then
    redis.call('SADD', KEYS[3], ARGV[1])
    redis.call('SET', KEYS[4], '1')
    return 'REMAINING'
end

-- 不删除 delta Hash。保留八个零字段，下一条事件可以直接 HINCRBY。
redis.call('SREM', KEYS[3], ARGV[1])
redis.call('SET', KEYS[4], '1')
return 'EMPTY'