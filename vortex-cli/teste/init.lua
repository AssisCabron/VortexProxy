Game.Events:OnStart(function()
    print("Experience started")
    Game.World:Fill("glass", -3, 65, -3, 3, 65, 3)
    Game.World:SetBlock("stone", 0, 66, 0)
    Game.Chat:ClearAll()
    Game.Chat:Broadcast("Servidor carregado!")
end)


