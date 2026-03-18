// допуск к экзамену
// 1.1 Я и не скачивала Доту. Мы- единство, мы- Олег
// 1.2 AsSharedFlow для read-only режима
// 1.3 до 18.340


enum class QuestStatus {
    NONE,
    LOCKED,
    ACTIVE,
    COMPLETED
}

enum class QuestMarker {
    NONE,
    NEW,
    LOCKED,
    COMPLETED,
    PINNED
}

enum class QuestBranch {
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
    val markerHint: String,
    val branchText: String,
    val lockedReason: String
)

sealed interface GameEvent {
    // каждый раз перезаписывается
    val playerId: String
}

data class QuestBranchChosen(
    override val playerId: String,
    val questId: String,
    val branch: QuestBranch
) : GameEvent

data class ItemCollected(
    override val playerId: String,
    val itemId: String,
    val amountAdded: Int
) : GameEvent

data class GoldTurnedId(
    override val playerId: String,
    val questId: String,
    val amount: Int
) : GameEvent

data class QuestCompleted(
    override val playerId: String,
    val questId: String
) : GameEvent

data class QuestUnlocked(
    override val playerId: String,
    val questId: String
) : GameEvent

data class QuestJournalUpdated(
    override val playerId: String
) : GameEvent

sealed interface GameCommand {
    val playerId: String
}

data class CmdOpenQuest(
    override val playerId: String,
    val questId: String
) : GameCommand

data class CmdPainQuest(
    override val playerId: String,
    val questId: String
) : GameCommand

data class CmdChooseBranch(
    override val playerId: String,
    val questId: String,
    val branch: QuestBranch
) : GameCommand

data class CmdCollectItem(
    override val playerId: String,
    val itemId: String,
    val count: Int
) : GameCommand

data class CmdGiveGoldDebug(
    override val playerId: String,
    val amount: Int
) : GameCommand

data class CmdTurnInGold(
    override val playerId: String,
    val amount: Int
) : GameCommand

data class CmdFinishQuest(
    override val playerId: String,
    val questId: String
) : GameCommand

data class PlayerData(
    val playerId: String,
    val gold: Int,
    val inventory: Map<String, Int>
)

class QuestSystem {
    fun objectiveFor(q: QuestStateOnServer): String {
        if (q.status == QuestStatus.LOCKED) {
            return "КВЕСТ ПОКА НЕ ДОСТУПЕН"
        }

        if (q.questId == "q_alchemist") {
            return when (q.step) {
                0 -> "ПОГОВОРИ С АЛКИМИКОМ"
                1 -> {
                    when (q.branch) {
                        QuestBranch.NONE -> "ВЫБЕРИ ПУТЬ КВЕСТА"
                        QuestBranch.HELP -> "СОБЕРИ 3 HERB: ${q.processCurrent} / ${q.processTarget}"
                        QuestBranch.THREAT -> "СОБЕРИ 10 GOLD: ${q.processCurrent} / ${q.processTarget}"
                    }
                }
                2 -> "ВЕРНИСЬ К АЛКИМИКУ"
                3 -> "КВЕСТ АЛХИМИКА ЗАВЕРШЕН"
                else -> "КВЕСТ ЗАВЕРШЕН"
            }
        }

        if (q.questId == "q_guard") {
            return when (q.step) {
                0 -> "ПОГОВОРИ СО СТРАЖНИКОМ"
                1 -> "ЗАПЛАТИ ЕМУ МОНЕТКАМИ ${q.processCurrent} / ${q.processTarget}"
                2 -> "ЗАВЕРШИ КВЕСТ У СТРАЖНИКА"
                else -> "КВЕСТ ЗАВЕРШЕН"
            }
        }

        // Новый квест q_alchemist - LOCKED, требует q_guard
        if (q.questId == "q_alchemist") {
            return when (q.step) {
                0 -> "ПОГОВОРИ С АЛХИМИКОМ"
                1 -> {
                    when (q.branch) {
                        QuestBranch.NONE -> "ВЫБЕРИ ПУТЬ КВЕСТА"
                        QuestBranch.HELP -> "СОБЕРИ 3 HERB: ${q.processCurrent} / ${q.processTarget}"
                        QuestBranch.THREAT -> "СОБЕРИ 10 GOLD: ${q.processCurrent} / ${q.processTarget}"
                    }
                }
                2 -> "ВЕРНИСЬ К АЛХИМИКУ"
                3 -> "КВЕСТ АЛХИМИКА ЗАВЕРШЕН"
                else -> "КВЕСТ ЗАВЕРШЕН"
            }
        }

        return "НЕИЗВЕСТНЫЙ КВЕСТ"
    }

    fun branchTextFor(branch: QuestBranch): String {
        return when (branch) {
            QuestBranch.NONE -> "ПУТЬ НЕ ВЫБРАН"
            QuestBranch.HELP -> "ПУТЬ HELP"
            QuestBranch.THREAT -> "ПУТЬ THREAT"
        }
    }

    fun lockedReasonFor(q: QuestStateOnServer): String {
        if (q.status != QuestStatus.LOCKED) return ""

        return if (q.unlockRequiredQuestId == null) {
            "ПРИЧИНА БЛОКИРОВКИ НЕИЗВЕСТНА"
        } else {
            "НУЖНО ЗАВЕРШИТЬ ${q.unlockRequiredQuestId}"
        }
    }

    fun markerFor(q: QuestStateOnServer): QuestMarker {
        return when {
            q.status == QuestStatus.LOCKED -> QuestMarker.LOCKED
            q.status == QuestStatus.COMPLETED -> QuestMarker.COMPLETED
            q.isPinned -> QuestMarker.PINNED
            q.isNew -> QuestMarker.NEW
            else -> QuestMarker.NONE
        }
    }

    fun progressBarText(current: Int, target: Int, blocks: Int = 10): String {
        if (target <= 0) return ""

        val ratio = current.toFloat() / target.toFloat()
        val filled = (ratio * blocks).toInt().coerceIn(0, blocks)
        val empty = blocks - filled

        return "#".repeat(filled) + "-".repeat(empty)
    }

    fun toJournalEntry(q: QuestStateOnServer): QuestJournalEntry {
        val progressText = if (q.processTarget > 0) "${q.processCurrent} / ${q.processTarget}" else ""

        val progressBar = if (q.processTarget > 0) progressBarText(q.processCurrent, q.processTarget) else ""

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

    // Новый метод updateAlchemist для третьего квеста
    // step 0: выбрать ветку, step 1: help -> собрать 3 Herb или THREAT -> собрать 10 Gold, step 2: вернуться и завершить, step 3: COMPLETED
    fun updateAlchemist(q: QuestStateOnServer, event: GameEvent): QuestStateOnServer {
        var updated = q

        when (event) {
            is QuestBranchChosen -> {
                // step 0: выбираем ветку
                if (event.questId == q.questId && q.step == 0) {
                    updated = q.copy(
                        branch = event.branch,
                        step = 1,
                        processTarget = if (event.branch == QuestBranch.HELP) 3 else 10,
                        processCurrent = 0,
                        isNew = false
                    )
                }
            }

            is ItemCollected -> {
                // step 1: собираем ресурсы для HELP ветки
                if (q.step == 1 && q.branch == QuestBranch.HELP && event.itemId == "Herb") {
                    val newCurrent = (q.processCurrent + event.amountAdded).coerceAtMost(q.processTarget)
                    updated = q.copy(processCurrent = newCurrent)

                    // Если собрали достаточно, переходим на step 2 (вернуться к алхимику)
                    if (newCurrent >= q.processTarget) {
                        updated = updated.copy(step = 2)
                    }
                }
            }

            is GoldTurnedId -> {
                // step 1: собираем gold для THREAT ветки
                if (q.step == 1 && q.branch == QuestBranch.THREAT && event.questId == q.questId) {
                    val newCurrent = (q.processCurrent + event.amount).coerceAtMost(q.processTarget)
                    updated = q.copy(processCurrent = newCurrent)

                    // Если собрали достаточно, переходим на step 2 (вернуться к алхимику)
                    if (newCurrent >= q.processTarget) {
                        updated = updated.copy(step = 2)
                    }
                }
            }

            is QuestCompleted -> {
                // step 2: завершаем квест
                if (event.questId == q.questId && q.step == 2) {
                    updated = q.copy(
                        step = 3,
                        status = QuestStatus.COMPLETED,
                        isNew = true
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
            is GoldTurnedId -> {
                // отдаем золото стражнику на step 1
                if (q.step == 1 && event.questId == q.questId) {
                    val newCurrent = (q.processCurrent + event.amount).coerceAtMost(q.processTarget)
                    updated = q.copy(processCurrent = newCurrent)

                    // нужную сумму - переключаем step на 2 (вернуться и завершить)
                    if (newCurrent >= q.processTarget) {
                        updated = updated.copy(step = 2)
                    }
                }
            }

            is QuestCompleted -> {
                // завершаем квест
                if (event.questId == q.questId && q.step == 2) {
                    updated = q.copy(
                        step = 3,
                        status = QuestStatus.COMPLETED,
                        isNew = true
                    )
                }
            }

            else -> {}
        }

        return updated
    }

    fun applyEvent(
        quests: List<QuestStateOnServer>,
        event: GameEvent
    ): List<QuestStateOnServer> {
        val copy = quests.toMutableList()

        for (i in copy.indices) {
            val q = copy[i]

            if (q.status == QuestStatus.COMPLETED) continue
            if (q.status == QuestStatus.LOCKED) continue

            if (q.questId == "q_guard") {
                copy[i] = updateGuard(q, event)
            }

            // Добавляем обработку для нового квеста q_alchemist
            if (q.questId == "q_alchemist") {
                copy[i] = updateAlchemist(q, event)
            }
        }

        // Проверяем, не разблокировался ли q_alchemist после завершения q_guard
        if (event is QuestCompleted && event.questId == "q_guard") {
            val alchemistIndex = copy.indexOfFirst { it.questId == "q_alchemist" }
            if (alchemistIndex != -1 && copy[alchemistIndex].status == QuestStatus.LOCKED) {
                val alchemist = copy[alchemistIndex]
                if (alchemist.unlockRequiredQuestId == "q_guard") {
                    copy[alchemistIndex] = alchemist.copy(
                        status = QuestStatus.ACTIVE,
                        step = 0,
                        isNew = true
                    )
                }
            }
        }

        return copy.toList()
    }
}