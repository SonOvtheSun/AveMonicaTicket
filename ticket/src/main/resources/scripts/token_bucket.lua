local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local requested = 1

local redis_time = redis.call('TIME')
local now = tonumber(redis_time[1]) + tonumber(redis_time[2]) / 1000000

local token_info = redis.call('HMGET', key, 'tokens', 'last_time')
local current_tokens = tonumber(token_info[1])
local last_time = tonumber(token_info[2])

if not current_tokens then
    current_tokens = capacity
    last_time = now
end

local time_passed = math.max(0, now - last_time)
local new_tokens = time_passed * rate
current_tokens = math.min(capacity, current_tokens + new_tokens)

if current_tokens < requested then
    return 0
else
    redis.call('HMSET', key, 'tokens', current_tokens - requested, 'last_time', now)
    redis.call('EXPIRE', key, math.ceil(capacity / rate) + 10)
    return 1
end