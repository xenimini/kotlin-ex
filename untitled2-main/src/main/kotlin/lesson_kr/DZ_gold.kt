package gold_dz

import de.fabmax.kool.KoolApplication   // Запускает Kool-приложение
import de.fabmax.kool.addScene          // функция - добавить сцену (UI, игровой мир и тд)

import de.fabmax.kool.math.Vec3f        // 3D - вектор (x,y,z)
import de.fabmax.kool.math.deg          // deg - превращение числа в градусы
import de.fabmax.kool.scene.*           // Сцена, камера, источники света и тд

import de.fabmax.kool.modules.ksl.KslPbrShader  // готовый PBR Shader - материал
import de.fabmax.kool.util.Color        // Цветовая палитра
import de.fabmax.kool.util.Time         // Время deltaT - сколько прошло секунд между двумя кадрами

import de.fabmax.kool.pipeline.ClearColorLoad // Режим говорящий не очищать экран от элементов (нужен для UI)

import de.fabmax.kool.modules.ui2.*     // импорт всех компонентов интерфейса, вроде text, button, Row....
import de.fabmax.kool.modules.ui2.UiModifier  // Позволяет использовать padding, margin, align
import jdk.jfr.DataAmount

enum class ItemType{
    WEAPON,
    ARMOR,
    POTION,
    POWERT
}

data class Item(
    val id: String,
    val name: String,
    val type: ItemType,
    val maxStack: Int,
    val power: Int
)

data class ItemStack(
    val item: Item,
    val count: Int
)

class GameState(){
    val playerId = mutableStateOf("Player")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)
    val attack = mutableStateOf(10)

    val potionTicksLeft = mutableStateOf(0)
    val regenTicksLeft = mutableStateOf(0)

    val dummyHp = mutableStateOf(50)

    val hotbar = mutableStateOf(
        List<ItemStack?>(9) {null}
        // Создание списка предметов (максимум 9) по умолчанию ячейки пустые
    )
    val selectedSlot = mutableStateOf(0)
    val eventLog = mutableStateOf<List<String>>(emptyList())
}

val HEALING_POTION = Item(
    "potion_heal",
    "Healing Potion",
    ItemType.POTION,
    12,
    0
)

val SWORD = Item(
    "sword",
    "Sword",
    ItemType.WEAPON,
    1,
    10
)

//  система событий создание интерфейса
sealed interface GameEvent{
    val playerId: String
}

data class ItemAdded(
    override val playerId: String,
    val itemId: String,
    val countAdded: Int,
    val leftover: Int
): GameEvent

data class ItemUsed(
    override val playerId: String,
    val itemId: String
): GameEvent

data class DamageDealt(
    override val playerId: String,
    val targetId: String,
    val amount: Int
): GameEvent

data class EffectApplied(
    override val playerId: String,
    val effectId: String,
    val ticks: Int
): GameEvent

data class QuestStepCompleted(
    override val playerId: String,
    val questId: String,
    val stepIndex: Int
): GameEvent

// Новое событие для изменения золота
data class GoldChanged(
    override val playerId: String,
    val newGold: Int,
    val change: Int
): GameEvent

typealias Listener = (GameEvent) -> Unit
class EventBus{
    // typealias - переменная хранящая в себе тип данных
    private val listeners = mutableListOf<Listener>()

    fun subscribe(listener: Listener){
        listeners.add(listener)
    }

    fun publish(event: GameEvent){
        for(listener in listeners){
            listener(event)
        }
    }
}

class QuestSystem(
    private val bus: EventBus
){
    val questId = "g_training"

    val progressByPlayer = mutableStateOf<Map<String, Int>>(emptyMap())

    init {
        bus.subscribe { event ->
            handleEvent(event)
        }
    }

    private fun getStep(playerId: String): Int{
        return progressByPlayer.value[playerId] ?: 0
        //?: - если ключ не найден - вернуть 0
    }

    private fun setStep(playerId: String, step: Int){
        val newMap = progressByPlayer.value.toMutableMap()
        newMap[playerId] = step
        progressByPlayer.value = newMap.toMap()
    }

    private fun completeStep(playerId: String, stepIndex: Int){
        // Проверяем, что не пытаемся выполнить уже выполненный шаг
        val currentStep = getStep(playerId)
        if (stepIndex == currentStep) {
            setStep(playerId, stepIndex + 1)
            bus.publish(
                QuestStepCompleted(
                    playerId,
                    questId,
                    stepIndex
                )
            )
        }
    }

    private fun handleEvent(event: GameEvent) {
        val player = event.playerId
        val step = getStep(player)

        if (step >= 3) return

        when (event) {
            is ItemAdded -> {
                if (step == 0 && event.itemId == SWORD.id) {
                    completeStep(player, 0)
                }
            }

            is DamageDealt -> {
                if (step == 1 && event.targetId == "dummy" && event.amount >= 10) {
                    completeStep(player, 1)
                }
            }

            is GoldChanged -> {
                if (step == 2 && event.newGold >= 10) {
                    completeStep(player, 2)
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
): Pair<List<ItemStack?>, Int>{
    val newSlots = slots.toMutableList()
    val current = newSlots[slotIndex]

    if (current == null){
        val count = minOf(addCount, item.maxStack)
        newSlots[slotIndex] = ItemStack(item, count)
        val leftOver = addCount - count
        return Pair(newSlots, leftOver)
    }

    if(current.item.id == item.id && item.maxStack > 1){
        val freeSpace = item.maxStack - current.count
        val toAdd = minOf(addCount, freeSpace)
        newSlots[slotIndex] = ItemStack(item, current.count + toAdd)
        val leftOver = addCount - toAdd
        return Pair(newSlots, leftOver)
    }
    // Если в слоте другой предмет или максимальный стак заполнен - ничего не кладем
    return Pair(newSlots, addCount)
}

fun useSelected(
    slots: List<ItemStack?>,
    slotIndex: Int
): Pair<List<ItemStack?>, ItemStack?> {
    // Pair - возвращает пару значений (1 новые слоты 2 то что использовали)

    val newSlots = slots.toMutableList()
    val current = newSlots[slotIndex] ?: return Pair(newSlots, null)

    val newCount = current.count - 1

    if (newCount <= 0) {
        // Если предметы кончились в слоте после использования - очистить ячейку
        newSlots[slotIndex] = null
    } else {
        newSlots[slotIndex] = ItemStack(current.item, newCount)
    }
    return Pair(newSlots, current)
}

fun pushLog(game: GameState, text: String){
    val old = game.eventLog.value
    val updated = old + text
    game.eventLog.value = updated.takeLast(20)
    // takeLast -  отображает лог и сохраняет только 20 последних строк
}

fun main() = KoolApplication{
    val game = GameState()
    val bus = EventBus()
    val quest = QuestSystem(bus)

    bus.subscribe { event ->
        val line = when(event){
            is ItemAdded -> "ItemAdded: ${event.itemId} +${event.countAdded} осталось: ${event.leftover}"
            is ItemUsed -> "ItemUsed: ${event.itemId}"
            is DamageDealt -> "DamageDealt: ${event.amount} - ${event.targetId}"
            is EffectApplied -> "EffectApplied: ${event.effectId} +${event.ticks}"
            is QuestStepCompleted -> "QuestStepCompleted: ${event.questId} шаг: ${event.stepIndex+1}"
            is GoldChanged -> "GoldChanged: ${event.change} (всего: ${event.newGold})"
        }
        pushLog(game, "[${event.playerId}] $line")
    }

    addScene {
        defaultOrbitCamera()

        addColorMesh {
            generate { cube{colored()} }

            shader = KslPbrShader{
                color { vertexColor() }
                metallic(0.8f)
                roughness(0.3f)
            }

            onUpdate{
                transform.rotate(45f.deg * Time.deltaT, Vec3f.Z_AXIS)
            }
        }
        lighting.singleDirectionalLight {
            setup(Vec3f(-1f,-1f,-1f))
            setColor(Color.WHITE, 5f)
        }
        var potionTimeSec = 0f
        var regenTimeSec = 0f
        onUpdate {
            if (game.potionTicksLeft.value > 0) {  // Убрал .f
                potionTimeSec += Time.deltaT
                if (potionTimeSec >= 1f) {
                    potionTimeSec = 0f
                    game.potionTicksLeft.value -= 1
                    game.hp.value = (game.hp.value - 2).coerceAtLeast(0) // не убьет ниже нуля
                }
            } else {
                potionTimeSec = 0f
            }

            if (game.regenTicksLeft.value > 0) {  // Убрал .f
                regenTimeSec += Time.deltaT
                if (regenTimeSec >= 1f) {
                    regenTimeSec = 0f
                    game.regenTicksLeft.value -= 1
                    game.hp.value = (game.hp.value + 1).coerceAtMost(100) // не больше максимума
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
                .background(RoundRectBackground(Color(0f,0f,0f,0.6f), 14.dp))

            Column {
                Text("Игрок: ${game.playerId.use()}"){}
                Text("HP: ${game.hp.use()}"){
                    modifier.margin(bottom = sizes.gap)
                }
                Text("Отравление: ${game.potionTicksLeft.use()}   | regen: ${game.regenTicksLeft.use()}"){}
                Text("Hp манекена: ${game.dummyHp.use()}"){
                    modifier.margin(bottom = sizes.gap)
                }
                Text("Золото: ${game.gold.use()}"){  // Добавил отображение золота
                    modifier.margin(bottom = sizes.gap)
                }

                val progress = quest.progressByPlayer.use()[game.playerId.use()] ?: 0
                val questText = when(progress){
                    0 -> "Шаг 1: Найди палку (получи меч)"
                    1 -> "Шаг 2: убей врага!!!"
                    2 -> "Шаг 3: Накопи 10 монет"
                    else -> "Квест выполнен"
                }
                Text(questText){
                    modifier.margin(bottom = sizes.gap)
                }
            }

            Row{
                modifier.margin(top = 6.dp)
                val slots = game.hotbar.use()
                val select = game.selectedSlot.use()

                for (i in 0 until 9){
                    // отрисовка каждой ячейки инвентаря
                    val isSelected = (i == select)
                    Box{
                        modifier
                            .size(44.dp, 44.dp)
                            .margin(end = 5.dp)
                            .background(
                                RoundRectBackground(
                                    if (isSelected) Color(0.2f, 0.6f, 1f, 0.8f) else Color (0f, 0f, 0f, 0.35f),
                                    8.dp
                                )
                            )
                            .onClick{
                                game.selectedSlot.value = i
                            }

                        val stack = slots[i]
                        if (stack != null){
                            Column {
                                modifier.padding(6.dp)
                                Text("${stack.item.name}"){
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

                Button ("Получить зелье"){
                    modifier
                        .margin(end = 8.dp)
                        .onClick{
                            val idx = game.selectedSlot.value
                            val pid = game.playerId.value
                            val (updated, leftover) = putIntoSlot(game.hotbar.value, idx, HEALING_POTION, 6)
                            game.hotbar.value = updated
                            bus.publish(ItemAdded(pid, HEALING_POTION.id, 6, leftover))

                            // Компенсация золотом за не поместившиеся предметы
                            if (leftover > 0) {
                                game.gold.value += leftover * 2
                                bus.publish(GoldChanged(pid, game.gold.value, leftover * 2))
                            }
                        }
                }

                Button ("Получить меч"){
                    modifier
                        .margin(end = 8.dp)
                        .onClick{
                            val idx = game.selectedSlot.value
                            val pid = game.playerId.value
                            val (updated, leftover) = putIntoSlot(game.hotbar.value, idx, SWORD, 1)
                            game.hotbar.value = updated
                            bus.publish(ItemAdded(pid, SWORD.id, 1, leftover))
                        }
                }

                Button("Использовать"){
                    modifier.onClick{
                        val idx = game.selectedSlot.value
                        val pid = game.playerId.value
                        val (updateSlots, used) = useSelected(game.hotbar.value, idx)
                        game.hotbar.value = updateSlots

                        if(used != null){
                            bus.publish(ItemUsed(pid, used.item.id))

                            when(used.item.type){
                                ItemType.POTION -> {
                                    game.regenTicksLeft.value += 5
                                    game.hp.value = (game.hp.value + 20).coerceAtMost(100)
                                    bus.publish(EffectApplied(pid, "regen", 5))
                                }
                                ItemType.WEAPON -> {
                                    // Ничего не делаем при использовании оружия
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }

            Row{
                modifier.margin(top = 6.dp)

                Button("Наложить яд"){
                    modifier.onClick{
                        val pid = game.playerId.value
                        game.potionTicksLeft.value += 5
                        bus.publish(EffectApplied(pid, "poison", 5))
                    }
                }

                Button("Атаковать"){
                    modifier.onClick{
                        val idx = game.selectedSlot.value
                        val pid = game.playerId.value
                        val stack = game.hotbar.value[idx]

                        val dmg = if(stack != null && stack.item.type == ItemType.WEAPON) 10 else 3
                        game.dummyHp.value = (game.dummyHp.value - dmg).coerceAtLeast(0)

                        bus.publish(DamageDealt(pid, "dummy", dmg))
                    }
                }

                Button("Золото +5"){  // Новая кнопка для добавления золота
                    modifier.margin(start = 8.dp).onClick{
                        val pid = game.playerId.value
                        game.gold.value += 5
                        bus.publish(GoldChanged(pid, game.gold.value, 5))
                    }
                }

                Button("Сменить игрока"){
                    modifier.margin(start = 8.dp).onClick{
                        game.playerId.value = if (game.playerId.value == "Player") "Oleg" else "Player"
                    }
                }
            }

            Text("Лог событий: "){
                modifier.margin(top = sizes.gap)
            }

            val logLines = game.eventLog.use()
            for (line in logLines){
                Text(line){modifier.font(sizes.smallText)}
            }
        }
    }
}
