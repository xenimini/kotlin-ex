package lesson22

/* event bus
*  в flow - ксть два главных варианта использования
*  sharedFlow - рассыл.щик собвтий аналог event bus
*  это горячий поток, который рассылает собвтия, даже в момент, еогда их никто не слушает (всегда существует)
* Stateflow - горячий аоток, он хранит в себе одно текущее значение и раздает его всем подписчикам
* как одно известное состояние
*
* сериалтзация -
* будем хранить игформацию не вручную каждую строку
* а объект целтком
* потомучто это : быстро, легко читается, корректируется и бпстро записывается и загружается надежно
* @Serialization - указатель пометка "это класс можно сохранить перезагрузить в файл
* Json.encodeToString (что кодирует) - преобразует объкт в -> строку
* Json.decodeFromString -> из строуки в -> объект
* */

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


//- Flow -

import kotlinx.coroutines.launch // - запускает корутину
import kotlinx.coroutines.flow.MutableSharedFlow // - радиостануия событий
import kotlinx.coroutines.flow.SharedFlow // - только чтение для подписчиков
import kotlinx.coroutines.flow.MutableStateFlow // - табло состояния
import kotlinx.coroutines.flow.StateFlow   // - только чтения стостояния
import kotlinx.coroutines.flow.asSharedFlow  // отдать наружу только SharedFlow
import kotlinx.coroutines.flow.asStateFlow  // отдать наружу только StateFlow
import kotlinx.coroutines.flow.collect  // - слушать поток


import kotlinx.serialization.Serializable // - можно сохранить загрузить
import kotlinx.serialization.encodeToString //
import kotlinx.serialization.decodeFromString  //
import kotlinx.serialization.json.Json  // формат json

import java.io.File

sealed interface  GameEvent{
    val playerId: String
}

data class DamageDealt(
    override val playerId: String,
    val targetId: String,
    val amount: Int
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val questId: String,
    val newState: String
): GameEvent

data class PlayerProgressSaved(
    override val playerId: String,
    val reason: String
): GameEvent

data class ItemAdded(
    override val playerId: String,
    val itemId: String,
    val count: Int
): GameEvent

@Serializable
data class PlayerSave(
    val playerId: String,
    val hp: Int,
    val gold: Int,
    val questState: Map<String,String>
)


class ServerWorld(
    initialPlayer: String
){
    //MutableSharedFlow - мы кладем внутрь него событие
    // replay = 0 - означает: "непересылать старые собвтия, новым подписчикам"
    private val _events = MutableSharedFlow<GameEvent>(replay = 0) // изменяемый

    val events: SharedFlow<GameEvent> = _events.asSharedFlow()
    // asSharedFlow() - создает и передает вариант, тольео для стения без возможности изменить

    private val _playerState = MutableStateFlow(
        PlayerSave(
            initialPlayer,
            100,
            0,
            mapOf("q_training" to "START")

        )
    )
    val playerState: SharedFlow<PlayerSave> = _playerState.asStateFlow()
    // команды клиента - которые отправляют клиент на сервер с инфой о том что на нем произошло у клиента
    //
    fun dealDamage(playerId: String, targetId: String, amount: Int){
        //  в каком состоянии был игрок
        val old = _playerState.value
        val newHp = (old.hp - amount).coerceAtMost(0) // чтоб не уходило в минус

        _playerState.value = old.copy(hp = newHp)
    }

    fun setQuestState(playerId: String, questId: String, newState: String){
        val old = _playerState.value

        val newQuestState = old.questState +(questId to newState)
        _playerState.value = old.copy(questState = newQuestState)
    }

    suspend fun emitEvent(event: GameEvent){
        _events.emit(event)
        // emit - чункция отправляет всем подписчикам
        // emit - может подождатьрассвлки, если подписчики медленные
    }
}

// система охранения

class SaveSystem{
    private val json = Json{
        prettyPrint = true // делает json читаемым
        encodeDefaults = true // записывает значение по умолчанию
    }

    private fun saveFile(playerId: String): File{
        val dir = File("saves")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$playerId.json")
    }
    fun save(player: PlayerSave){
        // подготваливаем текст в json
        val text = json.encodeToString(player)

        saveFile(player.playerId).writeText(text)
    }

    fun load(playerId: String): PlayerSave?{
        val file = saveFile(playerId)
        if(!file.exists()) return null

        val text = file.readText()

        return try{
            json.decodeFromString<PlayerSave>(text)
        }catch (e: Exception){
            null
        }
    }
}

class UiState{
    val activePlayerId = mutableStateOf("Oleg")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)
    val questState = mutableStateOf("START")

    val logLines = mutableStateOf<List<String>>(emptyList())
}

fun uiLog(ui: UiState, text: String){
    ui.logLines.value = (ui.logLines.value + text).takeLast(20)
}

fun main() = KoolApplication {
    val ui = UiState()
    val server = ServerWorld(initialPlayer = ui.activePlayerId.value)
    val saver = SaveSystem()


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

        coroutineScope.launch {
            server.events.collect{ event ->
                when (event){
                    is DamageDealt -> uiLog(ui, "[${event.playerId}] нанес урон ${event.targetId}")
                    is QuestStateChanged -> uiLog(ui, "[${event.playerId}] пришед в состоянии ${event.newState}")
                    is PlayerProgressSaved -> uiLog(ui, "[${event.playerId} сохранить по причине ${event.reason}")
                    is ItemAdded -> uiLog(ui, "[${event.playerId}] получил предмет ${event.itemId} x${event.count}")
                }
            }
        }

        coroutineScope.launch {
            server.playerState.collect{ state ->
                ui.hp.value = state.hp
                ui.gold.value = state.gold
                ui.questState.value = state.questState["q_training"]?: "START"

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
                .padding(12.dp)

            Column {
                Text("Игрок: ${ui.activePlayerId.use()}") {}
                Text("HP: ${ui.hp.use()}  Gold: ${ui.gold.use()}") {
                    modifier.margin(bottom = sizes.gap)
                }

                Text("QuestState(q_training: ${ui.questState.use()}") {
                    modifier.margin(bottom = sizes.gap)
                }
            }
            Row {
                Button("урон -10"){
                    modifier.margin(end = 8.dp).onClick{
                        server.dealDamage(ui.activePlayerId.value, targetId = "dummy", amount = 10)

                        this@addScene.coroutineScope.launch {
                            server.emitEvent(DamageDealt(ui.activePlayerId.value, "dummy", 10))
                        }
                    }
                }
                Button("Load JSON"){
                    modifier.margin(end = 8.dp).onClick{
                        val pid = ui.activePlayerId.value
                        val loaded = saver.load(pid)
                        if(loaded!= null){
                            uiLog(ui,"Загрузить сохранения игрока $pid")
                        }else{
                            uiLog(ui,"не удалось загрузить сохраненя для $pid")
                        }
                    }
                }
                Button("+ предмет") {
                    modifier.onClick {
                        this@addScene.coroutineScope.launch {
                            server.emitEvent(ItemAdded(
                                playerId = ui.activePlayerId.value,
                                itemId = "health_potion",
                                count = 1
                            ))
                            uiLog(ui, "Отправлено событие ItemAdded")
                        }
                    }
                }
            }

            Text("Log: "){}
            for (line in ui.logLines.use()){
                Text(line) { modifier.font(sizes.smallText) }

            }
        }
    }
}













