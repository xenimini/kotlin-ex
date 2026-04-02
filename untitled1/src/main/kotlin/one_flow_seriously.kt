import de.fabmax.kool.KoolApplication
// запускает кул приложение
import  de.fabmax.kool.addScene
// создать добавить сцену - ui игровой мир

import de.fabmax.kool.math.Vec3f  // 3d -  вектор x y z
import de.fabmax.kool.math.deg // deg - превращение числа в градусф
import de.fabmax.kool.scene.* // сцена, камера, источники света и тд

import de.fabmax.kool.modules.ksl.KslPbrShader
// PBR Shader - готовый материал
import de.fabmax.kool.util.Color // цветовая палитра с прозрачностью
import de.fabmax.kool.util.Time // deltaT - сколько прощшло времени между двумя кадрами

import de.fabmax.kool.pipeline.ClearColorLoad // режим говорящий нашей команды, не очищать экран от олиментов
// Ui

import de.fabmax.kool.modules.ui2.*
// это все компоненты интерфейса, вроде текста кнопок, колонок и строк
import de.fabmax.kool.modules.ui2.UiModifier
// позволяет использовать padding, margin, alig

class GameState {
    val playerId = mutableStateOf("Player")

    // создает состояние за которым умеет наблюдать и меняться UI
    // если состояние игрока его hp изменилось -> перерисует интерфейс для игрока
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)
    val potionTickLeft = mutableStateOf(0)
    // тики - условные изменения времени в игровом мире
    // у нас на примере будет 1 тик = 1 сек

}
fun main() = KoolApplication{
    // KoolApplication - запуск движка kool
    val game = GameState()
    addScene {
        // добавление сцены игровой
        defaultOrbitCamera()
        // готовая камера - легко перемещается мышью по умолчанию

        // добавление объекта - кубик
        addColorMesh { // добавление объекта с текстурой
            generate { // генерация вершин фигуры
                cube{ // пресет генерации куба
                    colored() // автомотически создаст разные увета разным гранм фигуры
                }
            }
            shader = KslPbrShader{ // назначение материала объекта
                color { vertexColor() }
                // берем цыета для объекта из его плоскостей
                metallic(0f) // металлизация объекта
                roughness(0.25f) // Шероховатость - 0f - глянцеый 1f - матовый

            }

            onUpdate{
                // метод который выполняется каждый кадр игры
                transform.rotate(45f.deg * Time.deltaT, Vec3f.X_AXIS) // вращение
                // rotate(углы, ось)
                // Time.deltaT - простая вормула подсчета того сколько проршло секунд

            }
        }

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f)) // установить на сцее
            setColor(Color.WHITE, 5f)
            // setColor -  цвет света и насколько ярко
        }
        var potionTimerSec = 0f

        onUpdate{
            if (game.potionTickLeft.value > 0){
                potionTimerSec += Time.deltaT

                if (potionTimerSec >= 1f){
                    potionTimerSec = 0f
                    game.potionTickLeft.value = game.potionTickLeft.value - 1

                    game.hp.value = (game.hp.value - 2).coerceAtLeast(0)

                }
            }else{
                potionTimerSec =0f
            }
        }
    }
    addScene {
        setupUiScene(ClearColorLoad)

        addPanelSurface {
            modifier
                .size(300.dp, 210.dp)
                .align(AlignmentX.Start, AlignmentY.Top)
                .padding(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.5f), 14.dp))

            Column{
                // use() - прочитать состояние, подписаться на нкго и ревгировать на его изменения
                Text("Игрок: ${game.playerId.use()}"){}
                Text("HP: ${game.hp.use()}"){}
                Text("монета: ${game.gold.use()}"){}
                Text("действия действия: ${game.potionTickLeft.use()}"){}

            }

            Row{
                modifier.padding(12.dp)

                Button(" урон hp-10"){
                    modifier
                        .padding(end = 8.dp)
                        .onClick{
                            game.hp.value = (game.hp.value - 10).coerceAtLeast(0)
                        }
                }
                Button(" gold +5"){
                    modifier
                        .padding(end = 8.dp)
                        .onClick{
                            game.gold.value = (game.gold.value + 10)
                        }
                }


                Button(" эффект +5"){
                    modifier
                        .padding(end = 8.dp)
                        .onClick{
                            game.potionTickLeft.value = (game.potionTickLeft.value + 10)
                        }
                }
            }
        }
    }

// Поток событий (фактов)
private val _events = mutableSharedFlow<GameEvent>(extraBufferCapacity = 64) 1 Usage
val events: SharedFlow<GameEvent> = _events.asSharedFlow()

// Поток команд (запросов)
private val _commands = mutableSharedFlow<GameCommand>(extraBufferCapacity = 64) 2 Usages
val commands: SharedFlow<GameCommand> = _commands.asSharedFlow()

fun trySend(cmd: GameCommand): Boolean {
    return _commands.tryEmit(value = cmd)
    // tryEmit(...) - отправить команду в поток быстрым, без suspend
}

// Состояние игроков
private val _players = mutableStateFlow(
    = mapOf(
"Oleg" to initialPlayerState(playerId = "Oleg"),
"Stas" to initialPlayerState(playerId = "Stas")
)

val players: StateFlow<Map<String, PlayerState>> = _players.asStateFlow()

fun getPlayer(playerId: String): PlayerState {
    return _players.value[playerId] ?: initialPlayerState(playerId)
}

private fun updatePlayer(playerId: String, change: (PlayerState) -> PlayerState) {
    val oldMap = _players.value
    val oldPlayer = oldMap[playerId] ?: return

    val newPlayer = change(oldPlayer)

    val newMap = oldMap.toMutableMap()
    newMap[playerId] = newPlayer
    _players.value = newMap.toMap()
    }
    fun start(kotlinx.coroutines.CotoutinesScope){
        scope.launch {
            commands.collect{
                cmd ->
                processCommand(cmd)
            }
        }
    }

    privat fun nearestObject(player: PlayerState): WorldObjectDef? {
        val candidates = worldObjects.filter {obj->
            distance2d (ax=player.posX, az= player.posZ, bx=obj.X, bz=obj.Z p) <= obj.interactRadius
        }
    }

    privat suspend fun refreshPlayerArea(playerId: String){
        val player = getPlayer(playerId)
        val nearest = nearestObject(player)

        val oldAreaId = player.currentAreaId
        val newAreaId = nearest?
    }
    if(oldAreaId == newAreaId){
        val newHint =
            when(newAreaId){
                "alchemist" -> "Поговори с алхимиком"
                "herb_source" -> "Собери траву"
                else -> "подойди к какой-то локации"
            }
        updatePlayer(playerId) {p -> p.copy(hintText = newHint)}
        return
    }

    if (oldAreaId != null){
        _events.emit( value = LeftArea(playerId, oldAreaId))
    }

    if (newAreaId != null){
        _events.emit( value = EnteredArea(playerId, newAreaId))
    }

    val newHint =
        when(newAreaId){
            "alchemist" -> "Поговори с алхимиком"
            "herb_source" -> "Собери траву"
            else -> "подойди к какой-то локации"
        }

        updatePlayer(playerId) { p ->
            p.copy(
                currentAreaId = newAreaId,
                hintText = newHint
           )
        }
    }
private void playerMove() {
    when(Cmd){
        is CmdMovePlayer -> {
            updatePlayer(Cmd.playerId) { p ->
                p.copy(
                    posX = p.posX + Cmd.dx,
                    posZ = p.posZ + Cmd.dz
                )
            }

            refreshPlayerArea(Cmd.playerId)
        }
    }
}

}


