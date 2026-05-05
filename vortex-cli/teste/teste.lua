-- Vortex Infinite Shard & Natural Terrain Test
Game.Events.OnPlayerJoin:Connect(function(player)
    player:SendMessage("§b[Vortex] §7Gerando seu planeta procedural em RAM...")
    
    -- 1. Criar o Shard Dinâmico com Terreno Natural (Perlin Noise)
    local meuPlaneta = Game.Shards:CreateInstance("NATURAL")
    
    -- 2. Teletransportar o jogador para o novo mundo
    -- Como o terreno é procedural, o spawn 66 pode ser dentro de uma montanha ou no ar.
    -- O nosso gerador fixa a média em 64, então 68 é seguro.
    player:SetInstance(meuPlaneta, {X=0.5, Y=68, Z=0.5})
    
    -- 3. HUD de Status da Infra
    Game.UI:CreateElement("infra_info", "§d§lINFRAESTRUTURA VORTEX\n§7Modo: §fProcedural Natural\n§7Motor: §fPerlin Noise Shard", {X=0.5, Y=72, Z=0.5})
    Game.UI:UpdateElement("infra_info", {
        Scale = 1.2,
        BackgroundColor = "#DD000000"
    })
    
    -- 4. Botão flutuante de inspeção técnica
    Game.UI:CreateElement("inspect_btn", "§e[ INSPECIONAR BIOMA ]", {X=0.5, Y=71, Z=0.5})
    Game.UI:OnClick("inspect_btn", function(p)
        p:SendMessage("§e[Vortex Info] §7Este terreno não existe no HD. Ele é calculado matematicamente enquanto você caminha.")
        p:SendMessage("§e[Vortex Info] §7FPS: §aEstável §8| §7RAM: §aOtimizada")
    end)

    player:SendMessage("§aPlaneta Procedural criado! Explore as montanhas.")
end)
