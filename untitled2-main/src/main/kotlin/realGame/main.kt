package realGame

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

enum class QuestState{
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END

}

enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE
}

data class WorldObjectDef(
    val id:String,
    val type: WorldObjectType,
    val x: Float,
    val y: Float,
    val interactRadius: Float // радиус внктри которого млжно взаимодействовать
)

data class NpcMemory(
    val hasMet: Boolean,
    val timesTalked: Int,
    val receiveHerd:Boolean
)

data class PlayerState(
    val playerId: String,
    val posX: Float,
    val posY: Float,
    val questState: QuestState,
    val gold: Int,
    val inventory: Map<String, Int>,
    val alchemistMemory: NpcMemory,
    val currentAreaId: String?,
    val hintText: String

)

fun herbCount(player:PlayerState): Int{
    return player.inventory["herb"] ?: 0
}

// расчитывать координат
fun distance2d(ax:Float, az: Float, bx:Float, bz:Float):Float{
    val dx = ax - bx
    val dz = az - bz
    return kotlin.math.sqrt(dx* dx + dz * dz)
}

fun initialPlayerState(playerId: String): PlayerState{
    return if(playerId == "Stas"){
        PlayerState(
            "Stas",
            0f,
            0f,
            QuestState.START,
            0,
            emptyMap(),
            NpcMemory(
                true,
                2,
                false
            ),
            null,
            "Подойти к любой локации"

        )
    }else{
            PlayerState(
                "Oleg",
                0f,
                0f,
                QuestState.START,
                0,
                emptyMap(),
                NpcMemory(
                    false,
                    0,
                    false
                ),
                null,
                "Подойти к любой локации"
            )
        }
    }


data class DialogueOption(
    val npcName: String,
    val text: String
)

data class DialogueView(
    val npcName: String,
    val text: String,
    val option: List<DialogueOption>
)


fun buildAlchemistDialogue(player: PlayerState): DialogueView{
    val herb = herbCount(player)
    val memory = player.alchemistMemory

    return when(player.questState){
        QuestState.START -> {
            val greeting =
                if (!memory.hasMet) {
                    "О новый путник что ты тут забыд"
                } else {
                    "снова ты гле трава"
                }
            DialogueView(
                "Алхките",
                "$greeting \n Если зочешь мне помочь то давай травку",
                listOf(
                    DialogueOption(
                        "accept_help",
                        "ага"
                    ),
                    DialogueOption(
                        "threat",
                        "не",
                    )
                )
            )
        }
            QuestState.WAIT_HERB -> {
                if (herb < 3){
                    DialogueView(
                        "Алзмсмк",
                        "Мне мало еще",
                        emptyList()
                    )
                }else{
                    DialogueView(
                        "Алзмсмк",
                        "от маладеу самый раз у тебя как раз 3",
                        listOf(
                            DialogueOption(
                                "give_herb",
                                "Отдать траву"
                            )
                        )
                    )
                
            }
        }
        QuestState.GOOD_END ->{
            val text =
                if(memory.receiveHerd){
                    "Спасибо от души"
                }else{
                    "Ты завершил квест но память у алхемика не обглвлена"
                }
            DialogueView(
                "Алхемик",
                text,
                emptyList()
            )
        }
        
        QuestState.EVIL_END ->{
            
            DialogueView(
                "Алхемик",
                "фууу узоди",
                emptyList()
            )
        }
    }
}

sealed interface GameCommand{
    val playerId: String
}

data class CmdMovePlayer(
    override val playerId: String,
    val dx:Float,
    val dz:Float
):GameCommand

data class CmdInteract(
    override val playerId: String,
):GameCommand

data class CmdChooseDialogueOption(
    override val playerId: String,
    val optionId: String
):GameCommand

data class CmdSwitchActivePlayer(
    override val playerId: String,
    val newPlayerId:String
):GameCommand

data class CmdResetPlayer(
    override val playerId: String
):GameCommand

        
sealed interface GameEvent{
    val playerId:String
}       

data class EnteredArea(
    override val playerId: String,
):GameEvent

data class LeftArea(
    override val playerId: String,
    val areaId: String
):GameEvent

data class InteractedWithNpc(
    override val playerId: String,
    val npcId: String
):GameEvent

data class InteractedWithHeroSource(
    override val playerId: String,
    val sourceId: String
):GameEvent

data class InventoryChanged(
    override val playerId: String,
    val itemId: String,
    val newCount: Int
):GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val newState: QuestState
):GameEvent

data class NpcMemoryChanged(
    override val playerId: String,
    val memory: String
):GameEvent

data class ServerMessage(
    override val playerId: String,,
    val text:String
):GameEvent


        
        
        
        
        
        
        
        
        
        
        
        
        
