-- Best combo: an example Cobalmoon script using the private store.
-- Permissions: hud.draw, combat.read, storage.
local chain, last = 0, -10
local best = 0
local allTime = cobalmoon.store.get("best") or 0

function on_hit(e)
  local now = os.clock()
  if now - last > 2 then chain = 0 end
  chain = chain + 1
  last = now
  if chain > best then best = chain end
  if chain > allTime then
    allTime = chain
    cobalmoon.store.set("best", allTime)
  end
end

function on_hud()
  return { "Best chain " .. best, "All time " .. math.floor(allTime) }
end