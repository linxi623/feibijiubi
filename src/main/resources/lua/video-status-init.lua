-- KEYS[1] current stats hash
-- KEYS[2] dirty set
-- KEYS[3] hot videos zset
-- KEYS[4] rebuild gate
-- ARGV[1] vid
-- ARGV[2] hot score
-- ARGV[3..10] current values
-- ARGV[11] has pending events
-- ARGV[12] unique rebuild owner token
--
-- T02 约定：
-- * 计数是 0..Long.MAX_VALUE 的十进制字符串，绝不 tonumber/tostring 往返，
--   否则 > 2^53 的值会在初始化时被 double 悄悄改写。
-- * 每个计数必须是 canonical 十进制（'0' 或 [1-9][0-9]*），不接受 '+'、前导 0、
--   负号、小数、科学计数法；写入 Redis 的就是校验通过的 canonical 字符串。
-- * hot score 必须是有限 double（拒绝 NaN / ±inf），否则 ZADD 会抛错或写出坏值。
-- * 所有校验都在第一条写命令之前完成；校验失败不会留下半个 Hash。
--   若校验全部通过后 Redis 自身失败（noeviction 下 OOM、实例 SHUTDOWN 等），
--   可能只写入部分字段，此时门闩仍在、current Hash 不完整，下一次 rebuild 会因
--   EXISTS KEYS[1] 走 ALREADY_INITIALIZED 分支并抛出重试，由运维/恢复流程处理；
--   本脚本不承诺通用事务回滚，部署必须使用 noeviction。

local LONG_MAX = '9223372036854775807'

local function redisType(key)
    local result = redis.call('TYPE', key)
    if type(result) == 'table' then
        return result['ok']
    end
    return result
end

-- canonical 非负十进制：'0' 或 [1-9][0-9]*；其余一律返回 nil。
local function canonicalUnsigned(text)
    if type(text) ~= 'string' then
        return nil
    end
    if text == '0' then
        return '0'
    end
    return text:match('^[1-9]%d*$')
end

local function compareDigits(left, right)
    if #left ~= #right then
        return #left < #right and -1 or 1
    end
    if left == right then
        return 0
    end
    return left < right and -1 or 1
end

local function isFinite(value)
    return value == value and value ~= math.huge and value ~= -math.huge
end

-- Redis 重启、门闩丢失或被新重建者接管后，旧快照不得发布。
if redis.call('GET', KEYS[4]) ~= ARGV[12] then
    return 'STALE_REBUILD'
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

local vid = canonicalUnsigned(ARGV[1])
if vid == nil or vid == '0' then
    return 'INVALID_ARGUMENT'
end

-- hot score 允许浮点近似，但必须有限；NaN / ±inf 会让 ZADD 抛错或写入坏值。
local hotScore = tonumber(ARGV[2])
if hotScore == nil or not isFinite(hotScore) then
    return 'INVALID_ARGUMENT'
end

local values = {}
for i = 3, 10 do
    local digits = canonicalUnsigned(ARGV[i])
    if digits == nil then
        return 'INVALID_ARGUMENT'
    end
    if compareDigits(digits, LONG_MAX) == 1 then
        return 'COUNTER_OUT_OF_RANGE'
    end
    values[i] = digits
end

local hasPending = ARGV[11]
if hasPending ~= 'true' and hasPending ~= 'false' then
    return 'INVALID_ARGUMENT'
end

-- 所有校验完成后再写入；写入的是校验通过的 canonical 字符串。
redis.call('HSET', KEYS[1],
    'vid', vid,
    'playTimes', values[3],
    'likeTimes', values[4],
    'unlikeTimes', values[5],
    'commentTimes', values[6],
    'coinTimes', values[7],
    'shareTimes', values[8],
    'collectTimes', values[9],
    'danmuTimes', values[10])

if hasPending == 'true' then
    redis.call('SADD', KEYS[2], vid)
else
    redis.call('SREM', KEYS[2], vid)
end

redis.call('ZADD', KEYS[3], hotScore, vid)
return 'INITIALIZED'
