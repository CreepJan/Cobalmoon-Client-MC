-- Session timer: an example Cobalmoon script.
-- Permissions: hud.draw (on_hud), combat.read (on_hit).
local start = os.clock()
local hits = 0

function on_hit(e)
  hits = hits + 1
end

function on_hud()
  local s = math.floor(os.clock() - start)
  return { string.format("Session %d:%02d", math.floor(s / 60), s % 60), "Hits " .. hits }
end