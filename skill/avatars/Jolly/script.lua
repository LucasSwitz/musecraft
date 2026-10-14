-- Jolly: show only the plush mesh; its parts follow the vanilla animations.
vanilla_model.PLAYER:setVisible(false)
vanilla_model.ARMOR:setVisible(false)
vanilla_model.HELMET_ITEM:setVisible(true)    -- pumpkins and mob heads, drawn at HelmetItemPivot

-- Armor: shells fitted to him, shown while a piece is worn and skinned with
-- that material's vanilla armor texture (leather in its dye color).
local m = models.model
local ARMOR = {
  {slot = 6, layer = 1, parts = {m.Body.Face.helmet}},
  {slot = 5, layer = 1, parts = {m.Body.chestplate, m.RightArm.shoulder, m.LeftArm.shoulder}},
  {slot = 4, layer = 2, parts = {m.Body.belt, m.RightLeg.legging, m.LeftLeg.legging}},
  {slot = 3, layer = 1, parts = {m.RightLeg.boot, m.LeftLeg.boot}},
}
local MATERIAL = {leather = "leather", chainmail = "chainmail", iron = "iron", golden = "gold",
                  diamond = "diamond", netherite = "netherite", turtle = "turtle"}
local LEATHER = vectors.hexToRGB("A06540")
local function dye(item)      -- a dyed leather piece's color, if any
  local tag = item:getTag() or {}
  local dyed = tag["minecraft:dyed_color"]
  if type(dyed) == "table" then dyed = dyed.rgb end
  if type(dyed) ~= "number" and type(tag.display) == "table" then dyed = tag.display.color end
  return type(dyed) == "number" and dyed or nil
end
local headItem = m.Body.Face.HelmetItemPivot
local HEAD_BLOCK, HEAD_SKULL, SKULL_DROP = 1.55, 1.632, 1.552
m.Body.LeftElytraPivot:setScale(0.65):setRot(14, 0, 0)    -- tilted in, so the folded wings lie along his back
m.Body.RightElytraPivot:setScale(0.65):setRot(14, 0, 0)
local worn = {}
for _, piece in ipairs(ARMOR) do
  for _, part in ipairs(piece.parts) do part:setVisible(false) end
end
local headId
events.TICK:register(function()
  if not player:isLoaded() then return end
  local id = player:getItem(6).id
  if id ~= headId then        -- mob heads are smaller than blocks and stand on the pivot
    headId = id
    local skull = id:match("_head$") or id:match("_skull$")
    headItem:setScale(skull and HEAD_SKULL or HEAD_BLOCK):setPos(0, skull and -SKULL_DROP or 0, 0)
    local worn_item = id ~= "minecraft:air" and not MATERIAL[id:match("^minecraft:(%a+)_") or ""]
    m.Body.Face.face:setVisible(not worn_item)     -- a pumpkin or mob head covers the face
  end
  for i, piece in ipairs(ARMOR) do
    local item = player:getItem(piece.slot)
    local color = dye(item)
    local key = item.id .. tostring(color)
    if worn[i] ~= key then
      worn[i] = key
      local material = MATERIAL[item.id:match("^minecraft:(%a+)_") or ""]
      for _, part in ipairs(piece.parts) do
        part:setVisible(material ~= nil)
        if material then
          part:setPrimaryTexture("RESOURCE", "minecraft:textures/models/armor/" .. material .. "_layer_" .. piece.layer .. ".png")
          part:setColor(material ~= "leather" and vec(1, 1, 1) or color and vectors.intToRGB(color) or LEATHER)
        end
      end
    end
  end
end)

-- He has no neck: the hood stays with the body and the face slides around it
-- toward where the player looks. Only turning, not nodding, so the face stays
-- on the hood's surface.
local face = models.model.Body.Face

-- Animations Musecraft plays with mc.animate (listed by mc.animations). A ping
-- starts each one, so every viewer sees it. The wave lifts the right arm out
-- beside the head, a little forward, and waggles it.
local WAVE_TICKS = 40
local rightArm = models.model.RightArm
local waveStart
function pings.wave() waveStart = world.getTime() end
function musecraft_animations() return "wave" end
function musecraft_animate(name)
  if name ~= "wave" then return false end
  pings.wave()
  return true
end

local function wavePose(t)      -- arm rotation at wave progress t (0..1)
  local lift = math.min(1, t * 5, (1 - t) * 5)       -- up over the first fifth, down over the last
  return lift * 30, lift * (115 + 22 * math.sin(t * math.pi * 8))
end

function events.render(delta, context)
  face:setRot(0, math.clamp(vanilla_model.HEAD:getOriginRot().y, -70, 70), 0)
  local t = waveStart and (world.getTime() + delta - waveStart) / WAVE_TICKS
  if t and t < 1 then
    local rx, rz = wavePose(t)
    rightArm:setRot(rx, 0, rz)
  elseif waveStart then
    waveStart = nil
    rightArm:setRot(0, 0, 0)
  end
end

-- Soft studio fill: tilt every normal toward the sky, so he keeps his shape
-- but no side drops into Minecraft's deep entity shade. A few hundred vertices
-- per tick keeps this inside the default script budget.
local LIFT = 0.9
local up = vec(0, LIFT, 0)
local queue = {}
for _, part in ipairs({m.Body.blob, m.Body.Face.face, m.RightArm.arm, m.LeftArm.arm, m.RightLeg.leg, m.LeftLeg.leg,
                       m.Body.Face.helmet, m.Body.chestplate, m.Body.belt, m.RightArm.shoulder, m.LeftArm.shoulder,
                       m.RightLeg.legging, m.LeftLeg.legging, m.RightLeg.boot, m.LeftLeg.boot}) do
  for _, verts in pairs(part:getAllVertices()) do queue[#queue + 1] = verts end
end
local list, index = 1, 1

-- A raised arm would carry its sky-tilted normals sideways and go dark, so
-- while it waves its normals are relit toward the sky in its rotated frame.
local armVerts = {}
for _, verts in pairs(m.RightArm.arm:getAllVertices()) do
  for _, v in ipairs(verts) do armVerts[#armVerts + 1] = {v, v:getNormal():copy()} end
end
local armLit = false
events.TICK:register(function()
  local t = waveStart and (world.getTime() - waveStart) / WAVE_TICKS
  if not (t and t < 1) and not armLit then return end
  local rx, rz = 0, 0
  if t and t < 1 then rx, rz = wavePose(t) end
  local sky = matrices.rotation3(rx, 0, rz):invert() * up
  for _, a in ipairs(armVerts) do a[1]:setNormal((a[2] + sky):normalize()) end
  armLit = rx ~= 0 or rz ~= 0
end)

events.TICK:register(function()
  local budget = 300
  while list <= #queue and budget > 0 do
    local v = queue[list][index]
    if v then
      v:setNormal((v:getNormal() + up):normalize())
      index, budget = index + 1, budget - 1
    else
      list, index = list + 1, 1
    end
  end
end)
