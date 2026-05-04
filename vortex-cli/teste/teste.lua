-- Player.lua
local Player = {}
Player.__index = Player

-- Construtor
function Player.new(name)
    local self = setmetatable({}, Player)
    
    self.name = name or "Unknown"
    self.health = 100
    self.maxHealth = 100
    
    return self
end

function Player:TakeDamage(amount)
    self.health = math.max(self.health - amount, 0)
    
    print(self.name .. " tomou " .. amount .. " de dano!")
    
    if self.health <= 0 then
        self:Die()
    end
end

-- Método de cura
function Player:Heal(amount)
    self.health = math.min(self.health + amount, self.maxHealth)
    
    print(self.name .. " curou " .. amount .. " de vida!")
end

-- Método ao morrer
function Player:Die()
    print(self.name .. " morreu 💀")
end

return Player




