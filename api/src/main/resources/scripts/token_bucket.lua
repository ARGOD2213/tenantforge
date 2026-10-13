-- Per-tenant token bucket, atomic inside Redis.
-- KEYS[1] = tf:{tenantId}:ratelimit
-- ARGV[1] = refill rate (tokens per second), ARGV[2] = burst (bucket size), ARGV[3] = cost of this request
-- Returns {allowed (1/0), retry_after_ms, tokens_left}
-- Uses Redis' own clock so every API instance agrees on time.

local key = KEYS[1]
local rate = tonumber(ARGV[1])
local burst = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local state = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil or ts == nil then
  tokens = burst
  ts = now
end

tokens = math.min(burst, tokens + math.max(0, now - ts) * rate / 1000)

local allowed = 0
local retry_ms = 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
else
  retry_ms = math.ceil((cost - tokens) * 1000 / rate)
end

redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', tostring(now))
redis.call('PEXPIRE', key, math.ceil(burst * 1000 / rate) + 1000)

return {allowed, retry_ms, math.floor(tokens)}
