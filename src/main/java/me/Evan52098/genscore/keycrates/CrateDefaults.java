package me.Evan52098.genscore.keycrates;

/** The crates.yml written on first start (same crates and odds as HXSMP's HxCrates). */
final class CrateDefaults {

    private CrateDefaults() {
    }

    static final String CRATES_YML = """
            # GensCore key crates. Settings (broadcasts, playtime keys, vote keys, welcome spin) are under keycrates: in config.yml.
            #
            # Each crate:
            #   name, key (key item name), key-material (default TRIPWIRE_HOOK), key-model / icon-model (resource-pack item
            #   models), icon, menu-slot (slot in /crates), hologram (lines above crate blocks), hologram-height,
            #   locations ("world,x,y,z"; crate blocks can also be placed with /crate set <crate>),
            #   no-duplicates (a player never wins the same reward twice from this crate), enabled (false = closed: its keys
            #   turn into shards if the crate is listed under keycrates.retired-keys).
            # Each reward: weight (chance = weight / total of the crate), icon, amount (icon size), name, lore, glow, model,
            #   and what it gives - any mix of:
            #     give:     items in /give syntax, e.g. 'minecraft:diamond_sword[enchantments={sharpness:2}]' or 'minecraft:obsidian 32'
            #     money:    dollars (GensCore economy)
            #     shards:   shards
            #     keys:     other crates' keys, e.g. { amethyst: 1 }
            #     commands: console commands ({player} = name, {uuid} = uuid)
            # Reward and crate ids are stored with every open: do not rename an id while players may have unopened wins.
            # After editing: /crate reload

            crates:

              vote:
                name: '&a&lVOTE CRATE'
                key: '&aVote Key'
                icon: LIME_SHULKER_BOX
                menu-slot: 12
                hologram: ['&a&lVOTE CRATE', '&eR-click &7open &8| &eL-click &7preview']
                locations: []
                rewards:
                  diamond_helmet:     { weight: 7,  icon: DIAMOND_HELMET,     name: '&bDiamond Helmet &7(Prot II)',         glow: true, give: ['minecraft:diamond_helmet[enchantments={protection:2,unbreaking:1}]'] }
                  diamond_chestplate: { weight: 6,  icon: DIAMOND_CHESTPLATE, name: '&bDiamond Chestplate &7(Prot II)',     glow: true, give: ['minecraft:diamond_chestplate[enchantments={protection:2,unbreaking:1}]'] }
                  diamond_leggings:   { weight: 6,  icon: DIAMOND_LEGGINGS,   name: '&bDiamond Leggings &7(Blast Prot II)', glow: true, give: ['minecraft:diamond_leggings[enchantments={blast_protection:2,unbreaking:1}]'] }
                  diamond_boots:      { weight: 7,  icon: DIAMOND_BOOTS,      name: '&bDiamond Boots &7(Prot II, FF II)',   glow: true, give: ['minecraft:diamond_boots[enchantments={protection:2,feather_falling:2}]'] }
                  diamond_sword:      { weight: 7,  icon: DIAMOND_SWORD,      name: '&bDiamond Sword &7(Sharp II)',         glow: true, give: ['minecraft:diamond_sword[enchantments={sharpness:2,unbreaking:1}]'] }
                  diamond_pickaxe:    { weight: 6,  icon: DIAMOND_PICKAXE,    name: '&bDiamond Pickaxe &7(Eff III)',        glow: true, give: ['minecraft:diamond_pickaxe[enchantments={efficiency:3,unbreaking:1}]'] }
                  emerald_blocks_4:   { weight: 10, icon: EMERALD_BLOCK,      name: '&a4x Emerald Block',      amount: 4,  give: ['minecraft:emerald_block 4'] }
                  emerald_blocks_16:  { weight: 3,  icon: EMERALD_BLOCK,      name: '&a16x Emerald Block',     amount: 16, give: ['minecraft:emerald_block 16'] }
                  crystals:           { weight: 9,  icon: END_CRYSTAL,        name: '&d8x End Crystal',        amount: 8,  give: ['minecraft:end_crystal 8'] }
                  obsidian:           { weight: 9,  icon: OBSIDIAN,           name: '&532x Obsidian',          amount: 32, give: ['minecraft:obsidian 32'] }
                  anchor_kit:         { weight: 6,  icon: RESPAWN_ANCHOR,     name: '&52x Respawn Anchor + 16x Glowstone', amount: 2, give: ['minecraft:respawn_anchor 2', 'minecraft:glowstone 16'] }
                  gapples:            { weight: 8,  icon: GOLDEN_APPLE,       name: '&64x Golden Apple',       amount: 4,  give: ['minecraft:golden_apple 4'] }
                  totem:              { weight: 2,  icon: TOTEM_OF_UNDYING,   name: '&e1x Totem of Undying',   give: ['minecraft:totem_of_undying 1'] }
                  xp:                 { weight: 8,  icon: EXPERIENCE_BOTTLE,  name: '&a32x Bottle o'' Enchanting', amount: 32, give: ['minecraft:experience_bottle 32'] }
                  money_small:        { weight: 8,  icon: GOLD_NUGGET,        name: '&a$2,500',                money: 2500 }
                  money_big:          { weight: 2,  icon: GOLD_BLOCK,         name: '&a$10,000',               money: 10000 }
                  amethyst_key:       { weight: 2,  icon: AMETHYST_SHARD,     name: '&d1x Amethyst Key',       keys: { amethyst: 1 } }

              # One key per hour of playtime (keycrates.playtime). Spawners through SmartSpawner's console command.
              amethyst:
                name: '&d&lAMETHYST CRATE'
                key: '&dAmethyst Key'
                icon: PURPLE_SHULKER_BOX
                menu-slot: 14
                hologram: ['&d&lAMETHYST CRATE', '&eR-click &7open &8| &eL-click &7preview']
                locations: []
                rewards:
                  pig:              { weight: 16,  icon: PIG_SPAWN_EGG,              name: '&fPig Spawner',              commands: ['ss give {player} pig 1'] }
                  cow:              { weight: 16,  icon: COW_SPAWN_EGG,              name: '&fCow Spawner',              commands: ['ss give {player} cow 1'] }
                  chicken:          { weight: 14,  icon: CHICKEN_SPAWN_EGG,          name: '&fChicken Spawner',          commands: ['ss give {player} chicken 1'] }
                  sheep:            { weight: 10,  icon: SHEEP_SPAWN_EGG,            name: '&fSheep Spawner',            commands: ['ss give {player} sheep 1'] }
                  zombie:           { weight: 12,  icon: ZOMBIE_SPAWN_EGG,           name: '&2Zombie Spawner',           commands: ['ss give {player} zombie 1'] }
                  spider:           { weight: 9,   icon: SPIDER_SPAWN_EGG,           name: '&8Spider Spawner',           commands: ['ss give {player} spider 1'] }
                  skeleton:         { weight: 6,   icon: SKELETON_SPAWN_EGG,         name: '&7Skeleton Spawner',         commands: ['ss give {player} skeleton 1'] }
                  creeper:          { weight: 5,   icon: CREEPER_SPAWN_EGG,          name: '&aCreeper Spawner',          commands: ['ss give {player} creeper 1'] }
                  cave_spider:      { weight: 4,   icon: CAVE_SPIDER_SPAWN_EGG,      name: '&3Cave Spider Spawner',      commands: ['ss give {player} cave_spider 1'] }
                  magma_cube:       { weight: 3,   icon: MAGMA_CUBE_SPAWN_EGG,       name: '&cMagma Cube Spawner',       commands: ['ss give {player} magma_cube 1'] }
                  zombified_piglin: { weight: 2,   icon: ZOMBIFIED_PIGLIN_SPAWN_EGG, name: '&6Zombified Piglin Spawner', commands: ['ss give {player} zombified_piglin 1'] }
                  enderman:         { weight: 1.5, icon: ENDERMAN_SPAWN_EGG,         name: '&5Enderman Spawner',         commands: ['ss give {player} enderman 1'] }
                  blaze:            { weight: 1,   icon: BLAZE_SPAWN_EGG,            name: '&6&lBlaze Spawner',          commands: ['ss give {player} blaze 1'] }
                  iron_golem:       { weight: 0.5, icon: IRON_GOLEM_SPAWN_EGG,       name: '&f&lIron Golem Spawner',     commands: ['ss give {player} iron_golem 1'] }

              # Store crate. Items use the HXSMP resource pack's models.
              cosmetic:
                name: '&e&lCOSMETIC CRATE'
                key: '&eCosmetic Key'
                icon: ORANGE_SHULKER_BOX
                menu-slot: 15
                hologram: ['&e&lCOSMETIC CRATE', '&eR-click &7open &8| &eL-click &7preview']
                locations: []
                rewards:
                  pumpkin_plush:  { weight: 18, icon: PAPER, model: 'hxsmp:pumpkin_plush',  name: '&6Pumpkin Plush',
                                    give: ['minecraft:paper[item_model="hxsmp:pumpkin_plush",max_stack_size=1,equippable={slot:"head"},custom_name={text:"Pumpkin Plush",color:"gold",italic:false},lore=[{text:"Wear it, hold it or place it!",color:"gray",italic:false}]]'] }
                  ghost_plush:    { weight: 18, icon: PAPER, model: 'hxsmp:ghost_plush',    name: '&fGhost Plush',
                                    give: ['minecraft:paper[item_model="hxsmp:ghost_plush",max_stack_size=1,equippable={slot:"head"},custom_name={text:"Ghost Plush",color:"white",italic:false},lore=[{text:"Wear it, hold it or place it!",color:"gray",italic:false}]]'] }
                  cat_plush:      { weight: 16, icon: PAPER, model: 'hxsmp:cat_plush',      name: '&8Black Cat Plush',
                                    give: ['minecraft:paper[item_model="hxsmp:cat_plush",max_stack_size=1,equippable={slot:"head"},custom_name={text:"Black Cat Plush",color:"dark_gray",italic:false},lore=[{text:"Wear it, hold it or place it!",color:"gray",italic:false}]]'] }
                  bat_plush:      { weight: 16, icon: PAPER, model: 'hxsmp:bat_plush',      name: '&5Bat Plush',
                                    give: ['minecraft:paper[item_model="hxsmp:bat_plush",max_stack_size=1,equippable={slot:"head"},custom_name={text:"Bat Plush",color:"dark_purple",italic:false},lore=[{text:"Wear it, hold it or place it!",color:"gray",italic:false}]]'] }
                  verity_plush:   { weight: 12, icon: PAPER, model: 'hxsmp:verity_plush',   name: '&e&lVerity Plush', glow: true,
                                    give: ['minecraft:paper[item_model="hxsmp:verity_plush",max_stack_size=1,equippable={slot:"head"},custom_name={text:"Verity Plush",color:"yellow",bold:true,italic:false},lore=[{text:"Wear it, hold it or place it!",color:"gray",italic:false}]]'] }
                  triple_t_plush: { weight: 8,  icon: PAPER, model: 'hxsmp:triple_t_plush', name: '&6&lTriple T Plush', glow: true,
                                    give: ['minecraft:paper[item_model="hxsmp:triple_t_plush",max_stack_size=1,equippable={slot:"head"},custom_name={text:"Triple T Plush",color:"gold",bold:true,italic:false},lore=[{text:"Tung Tung Tung Sahur",color:"gray",italic:false}]]'] }

              # OP powers (store keys only). Closed until the super powers module gives its grant command:
              # set each reward's commands to it (for example '<powers grant command> {uuid} soul_siphon') and enabled: true.
              op:
                enabled: false
                name: '&4&lOP POWERS CRATE'
                key: '&4&lOP Powers Key'
                key-model: 'hxsmp:key_op'
                icon-model: 'hxsmp:crate_op'
                icon: RED_SHULKER_BOX
                menu-slot: 11
                no-duplicates: true
                hologram-height: 2.1
                hologram: ['&4&lOP POWERS CRATE', '&7Powers you can''t get anywhere else', '&eR-click &7open &8| &eL-click &7preview']
                locations: []
                rewards:
                  soul_siphon:   { weight: 20, icon: PAPER, model: 'hxsmp:power_soul_siphon',   name: '&c&lSoul Siphon',   glow: true, lore: ['&7Heal for a quarter of the', '&7damage you deal to players.'], commands: [] }
                  berserker:     { weight: 18, icon: PAPER, model: 'hxsmp:power_berserker',     name: '&6&lBerserker',     glow: true, lore: ['&7The lower your health,', '&7the harder you hit: up to +60%.'], commands: [] }
                  dragon_breath: { weight: 15, icon: PAPER, model: 'hxsmp:power_dragon_breath', name: '&e&lDragon''s Breath', glow: true, lore: ['&7Breathe a cone of fire', '&7for 3 seconds.'], commands: [] }
                  angel_wings:   { weight: 12, icon: PAPER, model: 'hxsmp:power_angel_wings',   name: '&f&lAngel Wings',   glow: true, lore: ['&7Fly freely for 15 seconds.'], commands: [] }
                  reapers_mark:  { weight: 11, icon: PAPER, model: 'hxsmp:power_reapers_mark',  name: '&c&lReaper''s Mark', glow: true, lore: ['&7Mark a player: they glow and take', '&7+40% damage from you for 10 seconds.'], commands: [] }
                  thors_wrath:   { weight: 8,  icon: PAPER, model: 'hxsmp:power_thors_wrath',   name: '&b&lThor''s Wrath', glow: true, lore: ['&7Lightning strikes every enemy', '&7within 12 blocks.'], commands: [] }
                  divine_aegis:  { weight: 6,  icon: PAPER, model: 'hxsmp:power_divine_aegis',  name: '&e&lDivine Aegis',  glow: true, lore: ['&7Take no damage at all', '&7for 5 seconds.'], commands: [] }
                  black_hole:    { weight: 5,  icon: PAPER, model: 'hxsmp:power_black_hole',    name: '&5&lBlack Hole',    glow: true, lore: ['&7A singularity drags enemies', '&7in and crushes them for 4 seconds.'], commands: [] }
                  immortal:      { weight: 3,  icon: PAPER, model: 'hxsmp:power_immortal',      name: '&6&lImmortal',      glow: true, lore: ['&7A killing blow leaves you alive', '&7instead, once every 5 minutes.'], commands: [] }
                  time_stop:     { weight: 2,  icon: PAPER, model: 'hxsmp:power_time_stop',     name: '&3&lTime Stop',     glow: true, lore: ['&7Freezes every enemy within', '&715 blocks for 3 seconds.'], commands: [] }
            """;
}
