package lesson24

import de.fabmax.kool.KoolApplication // запуск кул приложения
import de.fabmax.kool.addScene // сцекна
import de.fabmax.kool.math.Vec3f // 3d
import de.fabmax.kool.math.deg // градусы
import de.fabmax.kool.scene.* // камера свет мэш - плоскость
import de.fabmax.kool.modules.ksl.KslPbrShader // материал
import de.fabmax.kool.util.Color // цвет
import de.fabmax.kool.util.Time //
import de.fabmax.kool.pipeline.ClearColorLoad //
import de.fabmax.kool.modules.ui2.* //
import de.fabmax.kool.modules.ui2.UiModifier.* //
import de.fabmax.kool.util.Log

import kotlinx.coroutines.launch // - запускает корутину
import kotlinx.coroutines.flow.MutableSharedFlow // - радиостануия событий
import kotlinx.coroutines.flow.SharedFlow // - только чтение для подписчиков
import kotlinx.coroutines.flow.MutableStateFlow // - табло состояния
import kotlinx.coroutines.flow.StateFlow   // - только чтения стостояния
import kotlinx.coroutines.flow.asSharedFlow  // отдать наружу только SharedFlow
import kotlinx.coroutines.flow.asStateFlow  // отдать наружу только StateFlow
import kotlinx.coroutines.flow.collect  // - слушать поток
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.flatMapLatest

import kotlinx.coroutines.launch // launch {} -  запускаем корутину
import kotlinx.coroutines.Job //
import kotlinx.coroutines.delay //
import kotlinx.coroutines.isActive //

import kotlinx.serialization.Serializable // - можно сохранить загрузить
import kotlinx.serialization.encodeToString //
import kotlinx.serialization.decodeFromString  //
import kotlinx.serialization.json.Json  // формат json

import java.io.File

/*
* когда в игре становится много событий, появляется проблема
* 1. если все системы слушают все обытия, то код быстро превратиться в кашу
* 2. бцдет сложно понять кто на что реагирует из событий
* 3ю такую структуру трудно дебажить ( например: "почему не изменилось состояние ")
* 4. в любой сетевой игре с несколькими игроками, события должны строго разделяться для каждого игрока
*
* чтоюы избежать тактх проблем, будем использовать flow операторы (ыильтр)
* filters{...} - пропускает только нужные нам события
* map{...} - превращает собвтие в другое значение ( превратить собвтие в строку лога)
* onEach{...} - лоп побочный эффект
* launchId(scope) - запуск слушателя событий (collect) на фоне ы нужнос пространстве корутин
* flatMapLatest{...} - переключатель активного игрока -> лог ui начинает слушать события игрока
* */

// серверное состояние игрока
@Serializable
data class PlayerSave(
    val playerId: String,
    val hp: Int,
    val gold: Int,
    val dummyHp: Int,
    val poisonTicksLeft: Int,
    val questState: String,
    val attackCooldownMsLeft: Long,
    val inventory: List<LootItem> = emptyList(), // Добавляем инвентарь
    val stunTicksLeft: Int = 0 // Эффект "Стан": сколько тиков осталось (тик = 1 сек)
)

// Класс для предметов лута
@Serializable
data class LootItem(
    val id: String,
    val name: String,
    val value: Int,
    val type: LootType
)

enum class LootType {
    WEAPON, ARMOR, POTION, GOLD, QUEST_ITEM
}

// игровые события
sealed interface GameEvent{
    val playerId: String
}

data class AttackPressed(
    override val playerId: String,
    val targetId: String
): GameEvent

data class DamageDealt(
    override val playerId: String,
    val targetId: String,
    val amount: Int
): GameEvent

data class PoisonApplied(
    override val playerId: String,
    val ticks: Int,
    val damagePerTick:Int,
    val intervalMs: Long

): GameEvent

// Новое событие для стана
data class StunApplied(
    override val playerId: String,
    val ticks: Int
): GameEvent

data class TalkedToNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class ChoiceSelected(
    override val playerId: String,
    val npcId: String,
    val choiceId: String
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val questId: String,
    val newState: String
): GameEvent

data class SaveRequested(
    override val playerId: String
): GameEvent

data class ServerMessage(
    override val playerId: String,
    val text: String
): GameEvent

// Новые события для лута
data class LootPickedUp(
    override val playerId: String,
    val item: LootItem,
    val source: String // откуда подобрали (сундук, монстр, и т.д.)
): GameEvent

data class LootNotification(
    override val playerId: String,
    val message: String,
    val item: LootItem
): GameEvent

// Событие добавления предмета (для itemAdded)
data class ItemAdded(
    override val playerId: String,
    val itemId: String,
    val count: Int
): GameEvent

// Событие прогресса игрока (сохранение/загрузка)
data class PlayerProgressSaved(
    override val playerId: String,
    val reason: String // причина: "manual_save" или "loaded_from_file"
): GameEvent

class GameServer{
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    /*
    * _event - будем обозначать внутренний поток событий его можно отправлятьв события
    * через emit
    * все внутрение изменяемые "штуки" юудем обозначать черех _
    * увеличели буфкр для того чтоб если наши собвтия приходят слишком быстро tryEmit - бцдкт чаще и успешно их обробатовать
    *
    * event - это наружний не изменяемый read-only поток (можно только слушать)
    * */

    val events: SharedFlow<GameEvent> = _events.asSharedFlow()
    //asSharedFlow() - создает вид только для чтения

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to PlayerSave("Oleg", 100, 50, 50, 0, "START", 0L,
                listOf(LootItem("sword_1", "Меч новичка", 10, LootType.WEAPON)), stunTicksLeft = 0),
            "Stas" to PlayerSave("Stas", 100, 30, 50, 0, "START", 0L,
                listOf(LootItem("potion_1", "Зелье здоровья", 5, LootType.POTION)), stunTicksLeft = 0),
        )
    )

    val players: SharedFlow<Map<String, PlayerSave>> = _players.asStateFlow()

    fun getPlayer(playerId: String): PlayerSave{
        return _players.value[playerId]
            ?: PlayerSave(playerId,100,0,50,0,"START", 0L, stunTicksLeft = 0)
    }

    fun updatePlayer(playerId: String, changed: (PlayerSave) -> PlayerSave){
        val oldMap = _players.value
        val old = oldMap[playerId]?: return

        val updated = changed(old)

        val newMap = oldMap.toMutableMap()
        newMap[playerId] = updated

        _players.value = newMap.toMap()
    }

    fun tryPublish(event: GameEvent):Boolean{
        // tryEmit - попытка отправить собвтия сразу
        // это не suspend фунция, ее можновызывать сразу в onClick
        return _events.tryEmit(event)
    }

    suspend fun publish(event: GameEvent){
        // emit - отправление собвтий в поток
        // emit - это уже suspend функция, поэтому вызывается только внутри корутины (launch)
        _events.emit(event)
    }

    // Метод для добавления предмета в инвентарь игрока
    fun addLootToInventory(playerId: String, item: LootItem) {
        updatePlayer(playerId) { player ->
            player.copy(inventory = player.inventory + item)
        }
    }

    // Метод для применения загруженного состояния игрока (из файла)
    fun applyLoaded(playerSave: PlayerSave) {
        val playerId = playerSave.playerId
        updatePlayer(playerId) { _ -> playerSave } // заменяем полностью
        // Публикуем событие о загрузке
        tryPublish(PlayerProgressSaved(playerId, "loaded_from_file"))
    }

    // Метод для сохранения состояния игрока в файл
    fun saveToFile(playerId: String, file: File) {
        val player = getPlayer(playerId)
        val json = Json.encodeToString(player)
        file.writeText(json)
        tryPublish(PlayerProgressSaved(playerId, "manual_save"))
    }

    // Метод для загрузки состояния игрока из файла
    fun loadFromFile(playerId: String, file: File): Boolean {
        return try {
            val json = file.readText()
            val loaded = Json.decodeFromString<PlayerSave>(json)
            // Проверяем, что загруженный playerId совпадает с текущим (или можно разрешить смену)
            if (loaded.playerId == playerId) {
                applyLoaded(loaded)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}

class CooldownSystem(
    private val server: GameServer,
    private val scope: kotlinx.coroutines.CoroutineScope
){
    private val cooldownJobs = mutableMapOf<String,Job>()

    fun canAttack(playerId: String): Boolean{
        return server.getPlayer(playerId).attackCooldownMsLeft <= 0L
    }

    fun startCooldown(playerId: String, totalMs: Long){
        cooldownJobs[playerId]?.cancel()

        server.updatePlayer(playerId){player->
            player.copy(attackCooldownMsLeft = totalMs)
        }
        val job = scope.launch {
            val step = 100L
            while (isActive && server.getPlayer(playerId).attackCooldownMsLeft> 0L){
                delay(step)
                server.updatePlayer(playerId){player ->
                    player.copy(attackCooldownMsLeft = (player.attackCooldownMsLeft - step). coerceAtLeast(0))
                }
            }
        }
        cooldownJobs[playerId] = job
    }
}

// Система лута для обработки подбора предметов
class LootSystem(
    private val server: GameServer,
    private val scope: kotlinx.coroutines.CoroutineScope
) {
    private val lootItems = mapOf(
        "chest_1" to listOf(
            LootItem("gold_100", "Золото", 100, LootType.GOLD),
            LootItem("sword_2", "Стальной меч", 50, LootType.WEAPON),
            LootItem("potion_2", "Большое зелье здоровья", 20, LootType.POTION)
        ),
        "monster_goblin" to listOf(
            LootItem("gold_10", "Золото", 10, LootType.GOLD),
            LootItem("goblin_ear", "Ухо гоблина", 5, LootType.QUEST_ITEM)
        ),
        "monster_dragon" to listOf(
            LootItem("gold_500", "Золото", 500, LootType.GOLD),
            LootItem("dragon_scale", "Чешуя дракона", 200, LootType.QUEST_ITEM),
            LootItem("dragon_sword", "Меч дракона", 300, LootType.WEAPON)
        )
    )

    init {
        // Слушаем события и обрабатываем подбор лута
        server.events
            .filter { it is LootPickedUp }
            .onEach { event ->
                event as LootPickedUp
                handleLootPickup(event)
            }
            .launchIn(scope)
    }

    private suspend fun handleLootPickup(event: LootPickedUp) {
        // Добавляем предмет в инвентарь
        server.addLootToInventory(event.playerId, event.item)

        // Создаем уведомление о подборе
        val notification = LootNotification(
            playerId = event.playerId,
            message = "Подобран предмет: ${event.item.name} (${event.item.value} ед.) из ${event.source}",
            item = event.item
        )

        // Отправляем уведомление
        server.publish(notification)

        // Отправляем событие ItemAdded
        server.publish(ItemAdded(event.playerId, event.item.id, 1))
    }

    // Метод для получения лута из источника
    fun getLootFromSource(sourceId: String): List<LootItem> {
        return lootItems[sourceId] ?: emptyList()
    }

    // Симулируем открытие сундука
    fun openChest(playerId: String, chestId: String) {
        val loot = getLootFromSource(chestId)
        loot.forEach { item ->
            server.tryPublish(LootPickedUp(playerId, item, "сундук"))
        }
    }

    // Симулируем убийство монстра
    fun killMonster(playerId: String, monsterId: String) {
        val loot = getLootFromSource(monsterId)
        loot.forEach { item ->
            server.tryPublish(LootPickedUp(playerId, item, monsterId))
        }
    }
}

// Система для обработки стана (уменьшение тиков со временем)
class StunSystem(
    private val server: GameServer,
    private val scope: kotlinx.coroutines.CoroutineScope
) {
    private val stunJobs = mutableMapOf<String, Job>()

    init {
        // Слушаем событие StunApplied и запускаем таймер уменьшения
        server.events
            .filter { it is StunApplied }
            .onEach { event ->
                event as StunApplied
                applyStun(event.playerId, event.ticks)
            }
            .launchIn(scope)
    }

    // Система для обработки урона
    class DamageSystem(
        private val server: GameServer,
        private val scope: kotlinx.coroutines.CoroutineScope
    ) {
        private val damageJobs = mutableMapOf<String, Job>()

        init {
            // Слушаем событие DamageDealt и применяем урон
            server.events
                .filter { it is DamageDealt }
                .onEach { event ->
                    event as DamageDealt
                    applyDamage(event)
                }
                .launchIn(scope)

            // Слушаем событие PoisonApplied для периодического урона
            server.events
                .filter { it is PoisonApplied }
                .onEach { event ->
                    event as PoisonApplied
                    applyPoison(event)
                }
                .launchIn(scope)
        }

        private fun applyDamage(event: DamageDealt) {
            val targetId = event.targetId
            val attackerId = event.playerId

            // Определяем, кто получает урон (в зависимости от цели)
            when {
                targetId == "Dummy" -> {
                    // Урон по манекену
                    server.updatePlayer(attackerId) { player ->
                        val newDummyHp = (player.dummyHp - event.amount).coerceAtLeast(0)
                        player.copy(dummyHp = newDummyHp)
                    }

                    // Отправляем сообщение о состоянии манекена
                    val dummyHp = server.getPlayer(attackerId).dummyHp
                    server.tryPublish(ServerMessage(attackerId, "Манекен получил ${event.amount} урона. Осталось HP: $dummyHp"))
                }
                targetId.startsWith("monster_") -> {
                    // Урон по монстру (можно расширить позже)
                    server.tryPublish(ServerMessage(attackerId, "Вы нанесли ${event.amount} урона монстру $targetId"))
                }
                targetId.startsWith("player_") -> {
                    // Урон по игроку (PvP)
                    val targetPlayerId = targetId.removePrefix("player_")
                    server.updatePlayer(targetPlayerId) { player ->
                        val newHp = (player.hp - event.amount).coerceAtLeast(0)
                        player.copy(hp = newHp)
                    }
                    server.tryPublish(ServerMessage(attackerId, "Вы нанесли ${event.amount} урона игроку $targetPlayerId"))
                }
            }
        }

        private suspend fun applyPoison(event: PoisonApplied) {
            val playerId = event.playerId
            val ticks = event.ticks
            val damagePerTick = event.damagePerTick
            val intervalMs = event.intervalMs

            // Отменяем предыдущий яд, если был
            damageJobs[playerId]?.cancel()

            // Запускаем новый яд
            val job = scope.launch {
                for (i in 1..ticks) {
                    delay(intervalMs)

                    // Проверяем, активен ли игрок и жив ли
                    if (!isActive) break

                    // Обновляем состояние игрока (наносим урон)
                    server.updatePlayer(playerId) { player ->
                        val newHp = (player.hp - damagePerTick).coerceAtLeast(0)
                        player.copy(hp = newHp)
                    }

                    // Отправляем событие об уроне от яда
                    server.publish(DamageDealt(playerId, playerId, damagePerTick))

                    // Если игрок умер, останавливаем яд
                    if (server.getPlayer(playerId).hp <= 0) {
                        server.publish(ServerMessage(playerId, "Вы погибли от яда!"))
                        break
                    }
                }
            }
            damageJobs[playerId] = job
        }

        // Метод для прямой атаки (можно вызывать из UI)
        fun attack(attackerId: String, targetId: String, damage: Int) {
            server.tryPublish(DamageDealt(attackerId, targetId, damage))
        }

        // Метод для лечения
        fun heal(playerId: String, amount: Int) {
            server.updatePlayer(playerId) { player ->
                val newHp = (player.hp + amount).coerceAtMost(100) // максимум 100 HP
                player.copy(hp = newHp)
            }
            server.tryPublish(ServerMessage(playerId, "Вы получили $amount лечения"))
        }
    }

    private fun applyStun(playerId: String, ticks: Int) {
        // Увеличиваем счетчик стана
        server.updatePlayer(playerId) { player ->
            player.copy(stunTicksLeft = player.stunTicksLeft + ticks)
        }

        // Запускаем или перезапускаем задачу уменьшения стана
        stunJobs[playerId]?.cancel()
        val job = scope.launch {
            while (isActive) {
                delay(1000) // каждую секунду уменьшаем на 1 тик
                server.updatePlayer(playerId) { player ->
                    val newTicks = (player.stunTicksLeft - 1).coerceAtLeast(0)
                    player.copy(stunTicksLeft = newTicks)
                }
                // Если стан закончился, выходим
                if (server.getPlayer(playerId).stunTicksLeft <= 0) break
            }
        }
        stunJobs[playerId] = job
    }
}

class GameUi(
    private val server: GameServer,
    private val scope: kotlinx.coroutines.CoroutineScope
) : UiContent {

    private var activePlayer: String = "Oleg"
    private val logMessages = mutableListOf<String>()
    private val lootNotifications = mutableListOf<LootNotification>()

    // Лимит для хранения сообщений
    private val maxLogSize = 100
    private val maxNotificationsSize = 20

    init {
        // Подписываемся на все события для логирования
        server.events
            .onEach { event ->
                val logMessage = when (event) {
                    is AttackPressed -> "⚔️ [${event.playerId}] атакует ${event.targetId}"
                    is DamageDealt -> "💥 [${event.playerId}] наносит ${event.amount} урона ${event.targetId}"
                    is PoisonApplied -> "☠️ [${event.playerId}] применен яд: ${event.ticks} тиков"
                    is StunApplied -> "💫 [${event.playerId}] оглушение на ${event.ticks} сек"
                    is TalkedToNpc -> "💬 [${event.playerId}] говорит с ${event.npcId}"
                    is ChoiceSelected -> "✅ [${event.playerId}] выбрал ${event.choiceId} у ${event.npcId}"
                    is QuestStateChanged -> "📜 [${event.playerId}] квест ${event.questId} -> ${event.newState}"
                    is SaveRequested -> "💾 [${event.playerId}] запросил сохранение"
                    is ServerMessage -> "📢 [${event.playerId}] сообщение: ${event.text}"
                    is LootPickedUp -> "🎁 [${event.playerId}] подобрал лут: ${event.item.name}"
                    is LootNotification -> "" // Не логируем уведомления в основной лог, они пойдут в отдельный список
                    is ItemAdded -> "📦 [${event.playerId}] добавлен предмет ${event.itemId} x${event.count}"
                    is PlayerProgressSaved -> "🔄 [${event.playerId}] прогресс сохранен: ${event.reason}"
                }
                if (logMessage.isNotEmpty()) {
                    addLogMessage(logMessage)
                }
            }
            .launchIn(scope)

        // Отдельная подписка на уведомления о луте
        server.events
            .filter { it is LootNotification }
            .onEach { event ->
                event as LootNotification
                addLootNotification(event)
            }
            .launchIn(scope)
    }

    private fun addLogMessage(message: String) {
        logMessages.add("${System.currentTimeMillis()}: $message")
        if (logMessages.size > maxLogSize) {
            logMessages.removeAt(0)
        }
    }

    private fun addLootNotification(notification: LootNotification) {
        lootNotifications.add(notification)
        if (lootNotifications.size > maxNotificationsSize) {
            lootNotifications.removeAt(0)
        }
    }

    private fun clearLogs() {
        logMessages.clear()
        lootNotifications.clear()
    }

    override fun UiScope.render() {
        Column(modifier = Modifier.fillMaxWidth().margin(start = 8.dp, end = 8.dp)) {
            // Заголовок
            Text("Game Server UI") {
                modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .textStyle(TextStyle.titleLarge)
            }

            // Выбор игрока
            Row(modifier = Modifier.fillMaxWidth().gap(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { activePlayer = "Oleg" }
                ) {
                    Text("Oleg")
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { activePlayer = "Stas" }
                ) {
                    Text("Stas")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Информация об игроке
            val player = server.getPlayer(activePlayer)
            Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Игрок: ${player.playerId}")
                    Text("HP: ${player.hp}")
                    Text("Золото: ${player.gold}")
                    Text("Инвентарь: ${player.inventory.size} предметов")
                    Text("Стан: ${player.stunTicksLeft} сек") // Показываем стан

                    if (player.inventory.isNotEmpty()) {
                        Text("Предметы:")
                        player.inventory.take(3).forEach { item ->
                            Text("  • ${item.name} (${item.value})", color = Color.GREEN)
                        }
                        if (player.inventory.size > 3) {
                            Text("  ... и еще ${player.inventory.size - 3}")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Кнопки действий
            Row(modifier = Modifier.fillMaxWidth().gap(4.dp)) {
                Button(modifier = Modifier.weight(1f), onClick = {
                    // Проверяем стан перед атакой
                    if (player.stunTicksLeft > 0) {
                        addLogMessage("⛔ [$activePlayer] Нельзя атаковать: оглушение (осталось ${player.stunTicksLeft} сек)")
                    } else {
                        server.tryPublish(AttackPressed(activePlayer, "Dummy"))
                    }
                }) {
                    Text("Атаковать")
                }
                Button(modifier = Modifier.weight(1f), onClick = {
                    server.tryPublish(PoisonApplied(activePlayer, 5, 2, 1000))
                }) {
                    Text("Яд")
                }
                // Кнопка Stun +3
                Button(modifier = Modifier.weight(1f), onClick = {
                    server.tryPublish(StunApplied(activePlayer, 3))
                }) {
                    Text("Stun +3")
                }
            }
            Row(modifier = Modifier.fillMaxWidth().gap(4.dp).margin(top = 4.dp)) {
                Button(modifier = Modifier.weight(1f), onClick = {
                    // Атака по манекену с фиксированным уроном 15
                    server.tryPublish(DamageDealt(activePlayer, "Dummy", 15))
                }) {
                    Text("Урон 15 по манекену")
                }
                Button(modifier = Modifier.weight(1f), onClick = {
                    // Лечение на 10 HP
                    server.updatePlayer(activePlayer) { player ->
                        player.copy(hp = (player.hp + 10).coerceAtMost(100))
                    }
                    server.tryPublish(ServerMessage(activePlayer, "Вылечился на 10 HP"))
                }) {
                    Text("Лечение +10")
                }
            }

            Row(modifier = Modifier.fillMaxWidth().gap(4.dp).margin(top = 4.dp)) {
                Button(modifier = Modifier.weight(1f), onClick = {
                    server.tryPublish(TalkedToNpc(activePlayer, "OldMan"))
                }) {
                    Text("Говорить с NPC")
                }
                Button(modifier = Modifier.weight(1f), onClick = {
                    server.tryPublish(SaveRequested(activePlayer))
                }) {
                    Text("Сохранить")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Кнопки для лута
            Text("Loot System", modifier = Modifier.padding(vertical = 4.dp))
            Row(modifier = Modifier.fillMaxWidth().gap(4.dp)) {
                Button(modifier = Modifier.weight(1f), onClick = {
                    // Открываем сундук
                    val lootSystem = LootSystem(server, scope)
                    lootSystem.openChest(activePlayer, "chest_1")
                }) {
                    Text("Открыть сундук")
                }
                Button(modifier = Modifier.weight(1f), onClick = {
                    // Убиваем гоблина
                    val lootSystem = LootSystem(server, scope)
                    lootSystem.killMonster(activePlayer, "monster_goblin")
                }) {
                    Text("Убить гоблина")
                }
            }

            Row(modifier = Modifier.fillMaxWidth().gap(4.dp).margin(top = 4.dp)) {
                Button(modifier = Modifier.weight(1f), onClick = {
                    // Убиваем дракона
                    val lootSystem = LootSystem(server, scope)
                    lootSystem.killMonster(activePlayer, "monster_dragon")
                }) {
                    Text("Убить дракона")
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { clearLogs() },
                    colors = ButtonColors(Color.RED, Color.WHITE)
                ) {
                    Text("Очистить логи")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Кнопки для сохранения/загрузки из файла
            Text("File I/O", modifier = Modifier.padding(vertical = 4.dp))
            Row(modifier = Modifier.fillMaxWidth().gap(4.dp)) {
                Button(modifier = Modifier.weight(1f), onClick = {
                    // Сохраняем в файл
                    val file = File("player_${activePlayer}.json")
                    server.saveToFile(activePlayer, file)
                    addLogMessage("💾 [$activePlayer] сохранен в файл ${file.name}")
                }) {
                    Text("Save to file")
                }
                Button(modifier = Modifier.weight(1f), onClick = {
                    // Загружаем из файла
                    val file = File("player_${activePlayer}.json")
                    if (file.exists()) {
                        val success = server.loadFromFile(activePlayer, file)
                        if (success) {
                            addLogMessage("📂 [$activePlayer] загружен из файла ${file.name}")
                        } else {
                            addLogMessage("❌ [$activePlayer] ошибка загрузки из файла")
                        }
                    } else {
                        addLogMessage("❌ [$activePlayer] файл ${file.name} не найден")
                    }
                }) {
                    Text("Load from file")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Loot Journal - уведомления о подборе предметов
            if (lootNotifications.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().margin(vertical = 4.dp),
                    colors = SurfaceColors.primaryContainer()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("Loot Journal", modifier = Modifier.weight(1f),
                                textStyle = TextStyle.titleMedium)
                            Text("(${lootNotifications.size})", color = Color.GRAY)
                        }

                        lootNotifications.reversed().take(5).forEach { notification ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                when (notification.item.type) {
                                    LootType.GOLD -> Text("💰 ", color = Color.GOLD)
                                    LootType.WEAPON -> Text("⚔️ ", color = Color.RED)
                                    LootType.ARMOR -> Text("🛡️ ", color = Color.CYAN)
                                    LootType.POTION -> Text("🧪 ", color = Color.GREEN)
                                    LootType.QUEST_ITEM -> Text("📜 ", color = Color.MAGENTA)
                                }
                                Text(notification.message, fontSize = 12.dp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Лог событий с заголовком и кнопкой очистки
            Surface(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("Event Log", modifier = Modifier.weight(1f),
                            textStyle = TextStyle.titleMedium)
                        Text("(${logMessages.size})", color = Color.GRAY)
                    }

                    ScrollPanel(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Column {
                            logMessages.reversed().forEach { message ->
                                Text(message, fontSize = 12.dp, modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// Основной класс приложения
class FlowGameApp : KoolApplication {
    override suspend fun setup() {
        addScene {
            val scope = this@addScene.scope
            val server = GameServer()
            val cooldownSystem = CooldownSystem(server, scope)
            val damageSystem = DamageSystem(server, scope) // Добавляем систему урона
            val lootSystem = LootSystem(server, scope)
            val stunSystem = StunSystem(server, scope) // Добавляем систему стана

            // Настройка сцены
            val camera = Camera().apply {
                setPerspective(60.deg, 0.1f, 100f)
            }

            // Свет
            +Light.dirLight(Vec3f(1f, -1f, -1f)) {
                setupShadow(1024, 1024)
            }

            // Пол
            +Mesh(geometry = Plane(10f, 10f, 10, 10)) {
                shader = KslPbrShader()
                rotateX(90.deg)
            }

            // UI
            +SceneUi(
                UiScope.UiConfiguration(
                    clearColor = ClearColorLoad(null)
                )
            ) {
                uiContent = GameUi(server, scope)
            }

            // Подписка на события для логирования в консоль
            server.events
                .onEach { event ->
                    Log.debug("GameEvent: $event")
                }
                .launchIn(scope)
        }
    }
}

fun main() {
    KoolApplication.run(::FlowGameApp)
}