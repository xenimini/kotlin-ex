package lessonex


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

/* =============== ОТВЕТЫ НА ВОПРОСЫ =============
* 1.1 - A ОЧЕНЬ ЛЮБЛЮ ПРОСТО
* 1.2 - a, d
* 1.3 - b
*
*
*
* ////////////////////// ЭКЗАМЕН //////////////////////////// */

enum class QuestStatus{
    NONE,
    LOCKED,
    ACTIVE,
    COMPLETED
}

enum class QuestMarker{
    NONE,
    NEW,
    LOCKED,
    COMPLETED,
    PINNED
}

enum class QuestBranch{
    NONE,
    HELP,
    THREAT

}


data class QuestStateOnServer(
    val questId: String,
    val title: String,
    val status: QuestStatus,
    val step: Int,
    val branch: QuestBranch,
    val processCurrent: Int,
    val processTarget: Int,
    val isNew: Boolean,
    val isPinned: Boolean,
    val unlockRequiredQuestId: String?

)

data class QuestJournalEntry(
    val questId: String,
    val title: String,
    val status: QuestStatus,
    val objectiveText: String,
    val progressText: String,
    val progressBar: String,
    val marker: QuestMarker,
    val markerHint : String,
    val branchText: String,
    val lockedReason: String
)

sealed interface GameEvent{
    // каждый раз перезаписывается
    val playerId: String
}

data class QuestBranchChosen(
    override val playerId: String,
    val questId: String,
    val branch: QuestBranch
): GameEvent

data class ItemCollected(
    override val playerId: String,
    val itemId: String,
    val amountAdded: Int
): GameEvent

data class GoldTurnedId(
    override val playerId: String,
    val questId: String,
    val amount: Int
):GameEvent

data class QuestCompleted(
    override val playerId: String,
    val questId: String
):GameEvent

data class QuestUnlocked(
    override val playerId: String,
    val questId: String
): GameEvent

data class QuestJournalUpdated(
    override val playerId: String
):GameEvent



sealed interface GameCommand{
    val playerId:String
}

data class CmdOpenQuest(
    override val playerId: String,
    val questId: String
): GameCommand


data class CmdPainQuest(
    override val playerId: String,
    val questId: String
): GameCommand

data class CmdChooseBranch(
    override val playerId: String,
    val questId: String,
    val branch: QuestBranch
): GameCommand



data class CmdCollectItem(
    override val playerId: String,
    val itemId: String,
    val count: Int
): GameCommand

data class  CmdGiveGoldDebug(
    override val playerId: String,
    val amount: Int
):GameCommand

data class  CmdTurnInGold(
    override val playerId: String,
    val amount: Int
):GameCommand

data class  CmdFinishQuest(
    override val playerId: String,
    val questId: String
):GameCommand

data class PlayerData(
    val playerId: String,
    val gold: Int,
    val inventory: Map<String,Int>
)

class QuestSystem {
    fun objectiveFor(q: QuestStateOnServer): String{
        if (q.status == QuestStatus.LOCKED) {
            return "КВЕСТ ПОКА НЕ ДОСТУПЕН"
        }

        if (q.questId == "q_alchemist") {
            return when (q.step) {
                0 -> "ПОГОВОРИ С АЛКИМИКОМ"
                1 -> {
                    when (q.branch) {
                        QuestBranch.NONE -> "выбери путь квеста"
                        QuestBranch.HELP -> "${q.processCurrent} / ${q.processTarget}"
                        QuestBranch.THREAT -> "${q.processCurrent} / ${q.processTarget}"
                    }
                }

                2 -> "Вернись и сделай квест алхимика"
                else -> "квест завершен"
            }
        }
        if (q.questId == "q_guard"){
            return when (q.step){
                0 -> " Поговори со стражником"
                1 -> "заплати ему монетками ${q.processCurrent} / ${q.processTarget}"
                2 -> "заверши квест у стражника"
                else -> "квест завершен"
            }
        }
        return "неизвестный квест"

    }
    fun branchTextFor(branch: QuestBranch):String{
        return when(branch){
            QuestBranch.NONE -> "Путь не выбран"
            QuestBranch.HELP -> "Путь Help"
            QuestBranch.THREAT -> "путь Theat"
        }
    }

    fun lockedReasonFor(q: QuestStateOnServer): String{
        if(q.status != QuestStatus.LOCKED) return ""

        return if(q.unlockRequiredQuestId == null){
            "Причина блокировки неизвестна"
        }else{
            " нужно завершить ${q.unlockRequiredQuestId}"
        }
    }
    fun markerFor(q:QuestStateOnServer):QuestMarker{
        return when {
            q.status == QuestStatus.LOCKED -> QuestMarker.LOCKED
            q.status == QuestStatus.COMPLETED -> QuestMarker.COMPLETED
            q.isPinned -> QuestMarker.PINNED
            q.isNew -> QuestMarker.NEW
            else -> QuestMarker.NONE
        }
    }
    fun progressBarText(current: Int, target: Int, blocks:Int = 10): String{
        if(target <= 0) return  ""

        val ratio = current.toFloat() / target.toFloat()
        val filled = (ratio * blocks).toInt().coerceIn(0,blocks)
        val empty = blocks - filled

        return "#".repeat(filled)+ "-".repeat(empty)
    }

    fun toJournalEntry(q: QuestStateOnServer): QuestJournalEntry{
        val progressText = if(q.processTarget > 0) "${q.processCurrent} / ${q.processTarget}" else ""

        val progressBar = if(q.processTarget> 0) progressBarText(q.processCurrent, q.processTarget) else ""

        return QuestJournalEntry(
            q.questId,
            q.title,
            q.status,
            objectiveFor(q),
            progressText,
            progressBar,
            markerFor(q),
            "",
            branchTextFor(q.branch),
            lockedReasonFor(q)
        )
    }
    fun updateAlchemist(q: QuestStateOnServer, event: GameEvent): QuestStateOnServer {
        var updated = q

        when (event) {
            is QuestBranchChosen -> {
                if (event.questId == q.questId && q.step == 0) {
                    updated = q.copy(
                        step = 1,
                        branch = event.branch,
                        processCurrent = 0,
                        processTarget = when (event.branch) {
                            QuestBranch.HELP -> 3
                            QuestBranch.THREAT -> 10
                            else -> 0
                        }
                    )
                }
            }

            is ItemCollected -> {
                if (updated.step == 1) {
                    when (updated.branch) {
                        QuestBranch.HELP -> {
                            if (event.itemId == "herb") {
                                updated = updated.copy(
                                    processCurrent = updated.processCurrent + event.amountAdded
                                )
                            }
                        }
                        QuestBranch.THREAT -> {
                            if (event.itemId == "gold") {
                                updated = updated.copy(
                                    processCurrent = updated.processCurrent + event.amountAdded
                                )
                            }
                        }
                        else -> {}
                    }

                    if (updated.processCurrent >= updated.processTarget) {
                        updated = updated.copy(step = 2)
                    }
                }
            }

            is QuestCompleted -> {
                if (event.questId == q.questId && updated.step == 2) {
                    updated = updated.copy(
                        status = QuestStatus.COMPLETED,
                        step = 3
                    )
                }
            }

            else -> {}
        }

        return updated
    }

    fun updateGuard(q: QuestStateOnServer, event: GameEvent): QuestStateOnServer {
        var updated = q

        when (event) {
            is ItemCollected -> {
                if (updated.step == 1 && event.itemId == "gold") {
                    updated = updated.copy(
                        processCurrent = updated.processCurrent + event.amountAdded
                    )

                    if (updated.processCurrent >= updated.processTarget) {
                        updated = updated.copy(step = 2)
                    }
                }
            }

            is QuestCompleted -> {
                if (event.questId == q.questId && updated.step == 2) {
                    updated = updated.copy(
                        status = QuestStatus.COMPLETED,
                        step = 3
                    )
                }
            }

            else -> {} // игнорируем
        }

        return updated
    }

    fun applyEvent(
        quests: List<QuestStateOnServer>,
        event: GameEvent
    ): List<QuestStateOnServer>{
        val copy = quests.toMutableList()

        for (i in copy.indices){
            val q = copy[i]

            if (q.status == QuestStatus.COMPLETED) continue
            if (q.status == QuestStatus.LOCKED) continue

            if (q.questId=="q_alchemist"){
                copy[i] = updateAlchemist(q, event)
            }
            if (q.questId=="q_quard") {
                copy[i] = updateGuard(q, event)
            }
        }
        return copy.toList()
    }
}

