package lesson23

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

// импорты корутин
import kotlinx.coroutines.launch // launch {} -  запускаем корутину
import kotlinx.coroutines.Job //
import kotlinx.coroutines.delay //
import kotlinx.coroutines.isActive //
import lesson22.SaveSystem
import lesson22.ServerWorld
import lesson22.UiState

// крутина - легковесрвая задача, которая может выполняться основному потоку задач
/* launch - команла запуска корутины
*  delay - команда приостановки корутины бещ щаморокт основного потока
*  job+ cancel() -  контроллер управления корутинами в cancel - останавляет поток корутины и завершает ее
*
* Использование scene.coroutineScore
* у kool есть своя обдасть запуска корутин, она удобна тем сто, запускает коруины не глобально
* а только внутри сцены, значит когда сцена завершиться, то корутины внутри данного score тоже завершиться
* */

class GameState{
    val playerId = mutableStateOf("Oleg")
    val hp = mutableStateOf(100)
    val maxHp = 100
    val  attack = mutableStateOf(10)


    val poisonTicksLeft = mutableStateOf(0)
    val regenTicksLeft = mutableStateOf(0)

    val stunTicksLeft = mutableStateOf(0)

    val attackCooldownMsLeft = mutableStateOf(0L)
    val logLines = mutableStateOf<List<String>>(emptyList())

}

fun pushLog(game:GameState, test: String){
    game.logLines.value = (game.logLines.value + test).takeLast(20) // 20 последних элементов списка
}


class EffectManager(
    private val game: GameState,
    private val scope: kotlinx.coroutines.CoroutineScope // запуск корутин для определенной сцены
){
    // корутина за вызов яда
    private var poisonJob: Job? = null
    private var regenJob: Job? = null

    private var stunJob: Job? = null

    fun applyPoison(ticks: Int, damagePerTicks: Int, intervalMs: Long){
        poisonJob?.cancel()
        // если корутина по нанесению яда уже работает
        // то мы ее отменяем и накладываем яд по новому
        // cancel - остановление потока , здесь ? безопасный вызов, если poisonJpb == null cancel  не вызовится
        game.poisonTicksLeft.value += ticks

        pushLog(game, "яд пременен ${game.playerId.value} продолжительность $ticks тиков урон з тик $damagePerTicks")

        poisonJob = scope.launch {
            while (isActive && game.poisonTicksLeft.value > 0){
                delay(intervalMs)

                game.poisonTicksLeft.value -=1

                game.hp.value =(game.hp.value - damagePerTicks).coerceAtLeast(0) // здоровье не падало ниже нудя

                pushLog(game,"тик яда: - $damagePerTicks урона hp осталось - ${game.hp.value}")
            }

            pushLog(game,"эффект яд завепшен ")
        }

    }

    fun applyStun(ticks: Int, healPerTick: Int, intervalMs: Long){
        stunJob?.cancel()
        game.stunTicksLeft.value += ticks

        pushLog(game, "стан пременен на ${game.playerId.value} вы не сможете атокавать $ticks " )

        stunJob = scope.launch {
            while (isActive && game.stunTicksLeft.value > 0){
                delay(intervalMs)

                game.stunTicksLeft.value -= 1
                game.attack.value =  (game.attack.value +healPerTick).coerceAtLeast(game.maxHp)
            }
            pushLog(game, "эффект стана завершен")
        }
    }

    fun applyRegen(ticks: Int,healPerTick:Int, intervalMs: Long){
        regenJob?.cancel()
        game.regenTicksLeft.value += ticks

        regenJob = scope.launch {  // существует ли еще корутина
            while (isActive && game.regenTicksLeft.value > 0){
                delay(intervalMs)

                game.regenTicksLeft.value -= 1
                game.hp.value = (game.hp.value +healPerTick).coerceAtLeast(game.maxHp)
            }
            pushLog(game,"эффкт регена завершен")
        }
    }



    fun cancelPoison(){
        poisonJob?.cancel()
        poisonJob = null
        game.poisonTicksLeft.value = 0
        pushLog(game, "эффект ядв снят ")
    }

    fun cancelRegen(){
        poisonJob?.cancel()
        poisonJob = null
        game.poisonTicksLeft.value = 0
        pushLog(game, "эффект регена снят ")
    }

    fun cancelStun(){
        poisonJob?.cancel()
        poisonJob = null
        game.stunTicksLeft.value = 0
        pushLog(game, "эффект стана снят")
    }
}

class CooldownManager(
    private val game: GameState,
    // область опитания корутин
    private val scope: kotlinx.coroutines.CoroutineScope
){
    private var cooldownJob: Job? = null

    fun startAttackCooldown(totalMs: Long){
        cooldownJob?.cancel()

        game.attackCooldownMsLeft.value = totalMs
        pushLog(game, "кулдавн атаки:${totalMs}")

        cooldownJob = scope.launch {
            val step = 100L
            while (isActive && game.attackCooldownMsLeft.value> 0L ){
                delay(step)
                game.attackCooldownMsLeft.value = (game.attackCooldownMsLeft.value - step).coerceAtLeast(0L)
            }

            pushLog(game,"куллдавн закончился можн аттаковать ")
        }
    }

    fun canAttack():Boolean{
        return game.attackCooldownMsLeft.value<=0L
    }
}

fun main() = KoolApplication {
    val game = GameState()


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
        val effects = EffectManager(game, coroutineScope)
        val cooldowns = CooldownManager(game, coroutineScope)

        SharedActions.effects = effects
        SharedActions.cooldown = cooldowns
    }

    addScene {
        setupUiScene(ClearColorLoad)

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(12.dp)

            Column {
                Text("Игрок: ${game.playerId.use()}"){ }
                Text("HP: ${game.hp.use()} / ${game.maxHp}"){
                    modifier.margin(bottom = sizes.gap)
                }
                Text(" Тиеи от яда ${game.poisonTicksLeft.use()}") {  }
                Text(" Тики от регена ${game.poisonTicksLeft.use()}") {
                    modifier.margin(bottom = sizes.gap)
                }
                Text("Кулдан атаки :${game.attackCooldownMsLeft.use()}"){
                    modifier.margin(bottom = sizes.gap)
                }
                Text(" длительность стана :${game.stunTicksLeft.use()}"){
                    modifier.margin(bottom = sizes.gap)
                }
                Row{
                    Button("Яд +5"){
                        modifier.margin(end = 8.dp).onClick{
                            SharedActions.effects?.applyPoison(
                                5,
                                2,
                                1000L
                            )
                        }
                    }

                    Button("отмена яда") {
                        modifier.margin(end = 8.dp).onClick{
                            SharedActions.effects?.cancelPoison()
                        }
                    }
                    Button("реген"){
                        modifier.margin(end = 8.dp).onClick{
                            SharedActions.effects?.applyRegen(
                                5,
                                1,
                                100L
                            )
                        }
                    }
                }

                Row {
                    Button("стан +10"){
                        modifier.margin(end = 8.dp).onClick{
                            SharedActions.effects?.applyStun(
                                10,
                                5,
                                1000L
                            )
                        }
                    }

                }

                Row{
                    modifier.margin(top = sizes.smallGap)
                    Button("отмена рншнга") {
                        modifier.onClick{
                            SharedActions.effects?.cancelRegen()
                        }
                    }

                }
                Row{
                    modifier.margin(top =sizes.gap)

                    Button("Аттокаиаьб (coldown 1200ms"){
                        modifier.onClick{
                            val cd =SharedActions.cooldown
                            if (cd == null){
                                pushLog(game, "CooldownManager еще не готов")
                                return@onClick
                            }
                            if (game.stunTicksLeft.use()>0){
                                pushLog(game,"нельзя аттокавать ты в стане")
                                pushLog(game,"осталось ${game.stunTicksLeft.use()} тиков стана")
                                return@onClick
                            }

                            if (!cd.canAttack()){
                                pushLog(game,"аттокавать")
                                return@onClick
                            }
                            pushLog(game, "Атака слвершена")

                            cd.startAttackCooldown(1200L)
                        }
                    }
                }
                Text("лог") { modifier.margin(top = sizes.gap) }
                val lines = game.logLines.use()
                for (line in lines){
                    Text(line){modifier.font(sizes.smallText)}
                }
            }
        }
    }
}

object SharedActions{
    var effects: EffectManager?= null
    var cooldown: CooldownManager? = null
}








