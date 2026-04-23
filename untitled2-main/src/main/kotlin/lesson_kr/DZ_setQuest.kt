package lesson182

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.scene.*
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.modules.ui2.*

enum class ItemType {
    WEAPON,
    ARMOR,
    POTION,
    POWER
}

enum class ItemEffect {
    HEAL,
    DAMAGE,
    BUFF,
    NONE
}

data class Item(
    val id: String,
    val name: String,
    val type: ItemType,
    val maxStack: Int,
    val power: Int,
    val effect: ItemEffect = ItemEffect.NONE,
    val effectValue: Int = 0
)

data class ItemStack(
    val item: Item,
    val count: Int
)

class GameState {
    val playerId = mutableStateOf("Player")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)
    val attack = mutableStateOf(10)

    val potionTicksLeft = mutableStateOf(0)
    val regenTicksLeft = mutableStateOf(0)

    val dummyHp = mutableStateOf(50)

    val hotbar = mutableStateOf(
        List<ItemStack?>(9) { null }
    )
    val selectedSlot = mutableStateOf(0)
    val eventLog = mutableStateOf<List<String>>(emptyList())
}

val HEALING_POTION = Item(
    "potion_heal",
    "Healing Potion",
    ItemType.POTION,
    12,
    0,
    ItemEffect.HEAL,
    20
)

val POISON_POTION = Item(
    "potion_poison",
    "Poison Potion",
    ItemType.POTION,
    12,
    0,
    ItemEffect.DAMAGE,
    2
)

val SWORD = Item(
    "sword",
    "Sword",
    ItemType.WEAPON,
    1,
    10,
    ItemEffect.NONE,
    0
)

val ARMOR = Item(
    "armor",
    "Armor",
    ItemType.ARMOR,
    1,
    5,
    ItemEffect.BUFF,
    0
)

// система событий создание интерфейса
sealed interface GameEvent {
    val playerId: String
}

data class ItemAdded(
    override val playerId: String,
    val itemId: String,
    val countAdded: Int,
    val leftover: Int
) : GameEvent

data class ItemUsed(
    override val playerId: String,
    val itemId: String
) : GameEvent

data class DamageDealt(
    override val playerId: String,
    val targetId: String,
    val amount: Int
) : GameEvent

data class EffectApplied(
    override val playerId: String,
    val effectId: String,
    val ticks: Int
) : GameEvent

data class QuestStepCompleted(
    override val playerId: String,
    val questId: String,
    val stepIndex: Int
) : GameEvent

data class PlayerProgressSaved(
    override val playerId: String,
    val questId: String,
    val step: Int
) : GameEvent

data class QuestCompleted(
    override val playerId: String,
    val questId: String
) : GameEvent

typealias Listener = (GameEvent) -> Unit

class EventBus {
    private val listeners = mutableListOf<Listener>()

    fun subscribe(listener: Listener) {
        listeners.add(listener)
    }

    fun publish(event: GameEvent) {
        for (listener in listeners) {
            listener(event)
        }
    }
}

data class PlayerSaveData(
    val playerId: String,
    val hp: Int,
    val gold: Int,
    val attack: Int,
    val hotbar: List<ItemStack?>,
    val questProgress: Map<String, Int>,
    val effects: Map<String, Int>
)

class SaveSystem {
    fun saveGame(game: GameState, quest: QuestSystem): PlayerSaveData {
        return PlayerSaveData(
            playerId = game.playerId.value,
            hp = game.hp.value,
            gold = game.gold.value,
            attack = game.attack.value,
            hotbar = game.hotbar.value,
            questProgress = quest.progressByPlayer.value,
            effects = mapOf(
                "poison" to game.potionTicksLeft.value,
                "regen" to game.regenTicksLeft.value
            )
        )
    }

    fun loadGame(data: PlayerSaveData, game: GameState, quest: QuestSystem) {
        game.playerId.value = data.playerId
        game.hp.value = data.hp
        game.gold.value = data.gold
        game.attack.value = data.attack
        game.hotbar.value = data.hotbar
        quest.progressByPlayer.value = data.questProgress
        game.potionTicksLeft.value = data.effects["poison"] ?: 0
        game.regenTicksLeft.value = data.effects["regen"] ?: 0
    }
}

class QuestSystem(
    private val bus: EventBus
) {
    val questId = "g_training"
    val progressByPlayer = mutableStateOf<Map<String, Int>>(emptyMap())
    val completedQuestsByPlayer = mutableStateOf<Map<String, Set<String>>>(emptyMap())

    init {
        bus.subscribe { event ->
            handleEvent(event)
        }
    }

    private fun getStep(playerId: String): Int {
        return progressByPlayer.value[playerId] ?: 0
    }

    private fun setStep(playerId: String, step: Int) {
        setQuestState(playerId, questId, step)
    }

    private fun setQuestState(
        playerId: String,
        questId: String,
        newStep: Int
    ) {
        // Сохраняем старое состояние
        val oldStep = progressByPlayer.value[playerId] ?: 0

        // Сравниваем старое с новым, и если они равны - return
        if (oldStep == newStep) return

        // Устанавливаем в свойства игрока questState - новое состояние
        val newMap = progressByPlayer.value.toMutableMap()
        newMap[playerId] = newStep
        progressByPlayer.value = newMap.toMap()

        // Публикуем событие что состояние квеста изменено
        bus.publish(QuestStepCompleted(playerId, questId, newStep - 1))

        // Публикуем событие, что прогресс игрока сохранен
        bus.publish(PlayerProgressSaved(playerId, questId, newStep))
    }

    private fun isQuestCompleted(playerId: String): Boolean {
        return completedQuestsByPlayer.value[playerId]?.contains(questId) ?: false
    }

    private fun completeQuest(playerId: String) {
        val completed = completedQuestsByPlayer.value.toMutableMap()
        val playerCompleted = completed[playerId]?.toMutableSet() ?: mutableSetOf()
        playerCompleted.add(questId)
        completed[playerId] = playerCompleted
        completedQuestsByPlayer.value = completed

        bus.publish(QuestCompleted(playerId, questId))
    }

    private fun handleEvent(event: GameEvent) {
        val player = event.playerId
        if (isQuestCompleted(player)) return

        val step = getStep(player)
        if (step >= 2) return

        when (event) {
            is ItemAdded -> {
                if (step == 0 && event.itemId == SWORD.id) {
                    setStep(player, 1)
                }
            }
            is DamageDealt -> {
                if (step == 1 && event.targetId == "dummy" && event.amount >= 10) {
                    setStep(player, 2)
                    completeQuest(player)
                }
            }
            else -> {}
        }
    }
}

fun putIntoSlot(
    slots: List<ItemStack?>,
    slotIndex: Int,
    item: Item,
    addCount: Int
): Pair<List<ItemStack?>, Int> {
    val newSlots = slots.toMutableList()
    val current = newSlots[slotIndex]

    if (current == null) {
        val count = minOf(addCount, item.maxStack)
        newSlots[slotIndex] = ItemStack(item, count)
        val leftOver = addCount - count
        return Pair(newSlots, leftOver)
    }

    if (current.item.id == item.id && item.maxStack > 1) {
        val freeSpace = item.maxStack - current.count
        val toAdd = minOf(addCount, freeSpace)
        newSlots[slotIndex] = ItemStack(item, current.count + toAdd)
        val leftOver = addCount - toAdd
        return Pair(newSlots, leftOver)
    }
    return Pair(newSlots, addCount)
}

fun useSelected(
    game: GameState,
    bus: EventBus,
    slotIndex: Int
): Pair<List<ItemStack?>, ItemStack?> {
    val slots = game.hotbar.value
    val newSlots = slots.toMutableList()
    val current = newSlots[slotIndex] ?: return Pair(newSlots, null)


    when (current.item.effect) {
        ItemEffect.HEAL -> {
            game.hp.value = (game.hp.value + current.item.effectValue).coerceAtMost(100)
            bus.publish(EffectApplied(game.playerId.value, "heal", 1))
        }
        ItemEffect.DAMAGE -> {
            game.potionTicksLeft.value += current.item.effectValue
            bus.publish(EffectApplied(game.playerId.value, "poison", current.item.effectValue))
        }
        ItemEffect.BUFF -> {
            game.attack.value += current.item.power
            bus.publish(EffectApplied(game.playerId.value, "attack_buff", 10))
        }
        ItemEffect.NONE -> {
            
            if (current.item.type == ItemType.POTION) {
                game.regenTicksLeft.value += 5
                bus.publish(EffectApplied(game.playerId.value, "regen", 5))
                game.hp.value = (game.hp.value + 20).coerceAtMost(100)
            }
        }
    }

    val newCount = current.count - 1
    if (newCount <= 0) {
        newSlots[slotIndex] = null
    } else {
        newSlots[slotIndex] = ItemStack(current.item, newCount)
    }

    bus.publish(ItemUsed(game.playerId.value, current.item.id))
    return Pair(newSlots, current)
}

fun pushLog(game: GameState, text: String) {
    val old = game.eventLog.value
    val updated = old + text
    game.eventLog.value = updated.takeLast(20)
}

fun main() = KoolApplication {
    val game = GameState()
    val bus = EventBus()
    val quest = QuestSystem(bus)
    val saveSystem = SaveSystem()

    bus.subscribe { event ->
        val line = when (event) {
            is ItemAdded -> "ItemAdded: ${event.itemId} +${event.countAdded} осталось: ${event.leftover}"
            is ItemUsed -> "ItemUsed: ${event.itemId}"
            is DamageDealt -> "DamageDealt: ${event.amount} - ${event.targetId}"
            is EffectApplied -> "EffectApplied: ${event.effectId} +${event.ticks}"
            is QuestStepCompleted -> "QuestStepCompleted: ${event.questId} шаг: ${event.stepIndex + 1}"
            is QuestCompleted -> "QuestCompleted: ${event.questId}"
            is PlayerProgressSaved -> "PlayerProgressSaved: ${event.questId} шаг: ${event.step}"
            else -> ""
        }
        pushLog(game, "[${event.playerId}] $line")
    }

    addScene {
        defaultOrbitCamera()

        addColorMesh {
            generate { cube { colored() } }

            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0.8f)
                roughness(0.3f)
            }

            onUpdate {
                transform.rotate(45f.deg * Time.deltaT, Vec3f.Z_AXIS)
            }
        }

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }

        var potionTimeSec = 0f
        var regenTimeSec = 0f

        onUpdate {
            if (game.potionTicksLeft.value > 0) {
                potionTimeSec += Time.deltaT
                if (potionTimeSec >= 1f) {
                    potionTimeSec = 0f
                    game.potionTicksLeft.value -= 1
                    game.hp.value = (game.hp.value - 2).coerceAtLeast(0)
                }
            } else {
                potionTimeSec = 0f
            }

            if (game.regenTicksLeft.value > 0) {
                regenTimeSec += Time.deltaT
                if (regenTimeSec >= 1f) {
                    regenTimeSec = 0f
                    game.regenTicksLeft.value -= 1
                    game.hp.value = (game.hp.value + 1).coerceAtMost(100)
                }
            } else {
                regenTimeSec = 0f
            }
        }
    }

    addScene {
        setupUiScene(ClearColorLoad)
        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))

            Column {
                Text("Игрок: ${game.playerId.use()}") {}
                Text("HP: ${game.hp.use()}") {
                    modifier.margin(bottom = sizes.gap)
                }
                Text("Атака: ${game.attack.use()}") {
                    modifier.margin(bottom = sizes.gap)
                }
                Text("Отравление: ${game.potionTicksLeft.use()}   | regen: ${game.regenTicksLeft.use()}") {}
                Text("Hp манекена: ${game.dummyHp.use()}") {
                    modifier.margin(bottom = sizes.gap)
                }

                val progress = quest.progressByPlayer.use()[game.playerId.use()]
                val isCompleted = quest.completedQuestsByPlayer.use()[game.playerId.use()]?.contains(quest.questId) ?: false

                val questText = when {
                    isCompleted -> " Квест выполнен"
                    progress == 0 -> " Квест Найти меч"
                    progress == 1 -> " Квест Ударить манекен на 10 урона"
                    
                }

                Text(questText) {
                    modifier.margin(bottom = sizes.gap)
                }
            }

            Row {
                modifier.margin(top = 6.dp)
                val slots = game.hotbar.use()
                val select = game.selectedSlot.use()

                for (i in 0 until 9) {
                    val isSelected = (i == select)
                    Box {
                        modifier
                            .size(44.dp, 44.dp)
                            .margin(end = 5.dp)
                            .background(
                                RoundRectBackground(
                                    if (isSelected) Color(0.2f, 0.6f, 1f, 0.8f) else Color(0f, 0f, 0f, 0.35f),
                                    8.dp
                                )
                            )
                            .onClick {
                                game.selectedSlot.value = i
                            }

                        val stack = slots[i]
                        if (stack != null) {
                            Column {
                                modifier.padding(6.dp)
                                Text("${stack.item.name}") {
                                    modifier.font(sizes.smallText)
                                }
                                Text("x${stack.count}") {
                                    modifier.font(sizes.smallText)
                                }
                            }
                        }
                    }
                }
            }

            Row {
                modifier.margin(top = 6.dp)

                Button("Получить зелье (лечение)") {
                    modifier
                        .margin(end = 8.dp)
                        .onClick {
                            val idx = game.selectedSlot.value
                            val pid = game.playerId.value
                            val (updated, used) = putIntoSlot(game.hotbar.value, idx, HEALING_POTION, 6)
                            game.hotbar.value = updated
                            bus.publish(ItemAdded(pid, HEALING_POTION.id, 6, used))
                        }
                }

                Button("Получить зелье (яд)") {
                    modifier
                        .margin(end = 8.dp)
                        .onClick {
                            val idx = game.selectedSlot.value
                            val pid = game.playerId.value
                            val (updated, used) = putIntoSlot(game.hotbar.value, idx, POISON_POTION, 6)
                            game.hotbar.value = updated
                            bus.publish(ItemAdded(pid, POISON_POTION.id, 6, used))
                        }
                }

                Button("Получить меч") {
                    modifier
                        .margin(end = 8.dp)
                        .onClick {
                            val idx = game.selectedSlot.value
                            val pid = game.playerId.value
                            val (updated, used) = putIntoSlot(game.hotbar.value, idx, SWORD, 1)
                            game.hotbar.value = updated
                            bus.publish(ItemAdded(pid, SWORD.id, 1, used))
                        }
                }
            }

            Row {
                modifier.margin(top = 6.dp)

                Button("Использовать выбранное") {
                    modifier
                        .margin(end = 8.dp)
                        .onClick {
                            val idx = game.selectedSlot.value
                            val (updatedSlots, used) = useSelected(game, bus, idx)
                            game.hotbar.value = updatedSlots
                        }
                }

                Button("Наложить яд (эффект)") {
                    modifier
                        .margin(end = 8.dp)
                        .onClick {
                            val pid = game.playerId.value
                            game.potionTicksLeft.value += 5
                            bus.publish(EffectApplied(pid, "potion", 5))
                        }
                }

                Button("Атаковать") {
                    modifier
                        .margin(end = 8.dp)
                        .onClick {
                            val idx = game.selectedSlot.value
                            val pid = game.playerId.value
                            val stack = game.hotbar.value[idx]

                            val dmg = if (stack != null && stack.item.type == ItemType.WEAPON) 10 else 3
                            game.dummyHp.value = (game.dummyHp.value - dmg).coerceAtLeast(0)

                            bus.publish(DamageDealt(pid, "dummy", dmg))
                        }
                }
            }

            Row {
                modifier.margin(top = 6.dp)

                Button("Сменить персонажа") {
                    modifier
                        .margin(end = 8.dp)
                        .onClick {
                            game.playerId.value = if (game.playerId.value == "Player") "Oleg" else "Player"
                        }
                }

                Button("Сохранить игру") {
                    modifier
                        .margin(end = 8.dp)
                        .onClick {
                            val savedData = saveSystem.saveGame(game, quest)
                            pushLog(game, "[System] Игра сохранена для ${savedData.playerId}")
                        }
                }

                Button("Загрузить игру") {
                    modifier.onClick {
                        // Здесь должна быть загрузка из файла
                        pushLog(game, "[System] Загрузка игры...")
                    }
                }
            }

            Text("Лог событий:") {
                modifier.margin(top = sizes.gap)
            }

            val logLines = game.eventLog.use()
            for (line in logLines.takeLast(5)) {
                Text(line) { modifier.font(sizes.smallText) }
            }
        }
    }
}
