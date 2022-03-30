package xyz.xenondevs.nova.world.block

import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.*
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectInstance
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffectType
import xyz.xenondevs.nova.NOVA
import xyz.xenondevs.nova.data.world.WorldDataManager
import xyz.xenondevs.nova.data.world.block.state.NovaBlockState
import xyz.xenondevs.nova.initialize.Initializable
import xyz.xenondevs.nova.material.BlockNovaMaterial
import xyz.xenondevs.nova.material.CoreBlockOverlay
import xyz.xenondevs.nova.network.PacketListener
import xyz.xenondevs.nova.network.event.serverbound.PlayerActionPacketEvent
import xyz.xenondevs.nova.tileentity.requiresLight
import xyz.xenondevs.nova.util.*
import xyz.xenondevs.nova.util.item.ToolCategory
import xyz.xenondevs.nova.util.item.ToolUtils
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.armorstand.FakeArmorStand
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import net.minecraft.world.entity.EquipmentSlot as MojangSlot

internal object BlockBreakManager : Initializable(), Listener {
    
    override val inMainThread = true
    override val dependsOn = setOf(PacketListener)
    
    private val breakers = ConcurrentHashMap<Player, Breaker>()
    
    override fun init() {
        Bukkit.getPluginManager().registerEvents(this, NOVA)
        runTaskTimer(0, 1, ::handleTick)
    }
    
    private fun handleTick() {
        breakers.removeIf { (_, breaker) ->
            breaker.handleTick()
            return@removeIf breaker.isDone()
        }
    }
    
    private fun handleDestroyStart(player: Player, pos: BlockPos): Boolean {
        val blockState = WorldDataManager.getBlockState(pos)
        if (blockState is NovaBlockState) {
            val material = blockState.material
            if (material.hardness >= 0) {
                breakers[player] = Breaker(player, pos.location.block, material)
            }
        }
        
        return false
    }
    
    private fun handleDestroyStop(player: Player): Boolean {
        val breaker = breakers.remove(player)
        if (breaker != null) {
            breaker.stop()
            return true
        }
        return false
    }
    
    @EventHandler
    private fun handlePlayerAction(event: PlayerActionPacketEvent) {
        val player = event.player
        val pos = event.pos
        val blockPos = BlockPos(event.player.world, pos.x, pos.y, pos.z)
        
        event.isCancelled = when (event.action) {
            START_DESTROY_BLOCK -> handleDestroyStart(player, blockPos)
            STOP_DESTROY_BLOCK, ABORT_DESTROY_BLOCK -> handleDestroyStop(player)
            else -> false
        }
    }
    
}

private val MINING_FATIGUE = MobEffectInstance(MobEffect.byId(4), Integer.MAX_VALUE, 255, false, false, false)

private class Breaker(val player: Player, val block: Block, val material: BlockNovaMaterial) {
    
    private val tool: ItemStack = player.inventory.itemInMainHand
    private val toolCategory: ToolCategory? = ToolCategory.of(tool.type)
    private val correctCategory: Boolean = material.toolCategory == null || material.toolCategory == toolCategory
    private val correctLevel: Boolean = material.toolLevel == null || tool.type in material.toolLevel.materialsWithHigherTier
    private val drops: Boolean = !material.requiresToolForDrops || (correctCategory && correctLevel)
    
    private val breakMethod: BreakMethod? = if (material.showBreakAnimation)
        if (block.type == Material.BARRIER) ArmorStandBreakMethod(block)
        else PacketBreakMethod(block)
    else null
    
    private var progress = 0.0
    
    fun handleTick() {
        check(!isDone()) { "Breaker is done" }
        
        progress += ToolUtils.calculateDamage(player, tool, toolCategory, material.hardness, correctCategory, correctLevel)
        
        if (isDone()) {
            // Stop break animation and mining fatigue effect
            stop()
            // Drop items
            if (drops) block.location.dropItems(block.getAllDrops(tool))
            // If the block has a hardness of 0, the effects will be played clientside
            block.remove(block.type.hardness != 0f)
        } else {
            val percentage = progress / 1
            if (breakMethod != null) breakMethod.breakStage = (percentage * 10).toInt()
            
            val effect = player.getPotionEffect(PotionEffectType.SLOW_DIGGING)
            val packet = if (effect != null) {
                // The player might actually have mining fatigue.
                // In this case, it is important to copy the hasIcon value to prevent it from disappearing.
                val effectInstance = MobEffectInstance(
                    MobEffect.byId(4),
                    Int.MAX_VALUE, 255,
                    effect.isAmbient, effect.hasParticles(), effect.hasIcon()
                )
                ClientboundUpdateMobEffectPacket(player.entityId, effectInstance)
            } else {
                // The player does not have mining fatigue, we can use the default effect instance
                ClientboundUpdateMobEffectPacket(player.entityId, MINING_FATIGUE)
            }
            
            player.send(packet)
        }
    }
    
    fun stop() {
        breakMethod?.stop()
        
        val effect = player.getPotionEffect(PotionEffectType.SLOW_DIGGING)
        val packet = if (effect != null) {
            // If the player actually has mining fatigue, send the correct effect again
            val effectInstance = MobEffectInstance(
                MobEffect.byId(4),
                effect.duration, effect.amplifier,
                effect.isAmbient, effect.hasParticles(), effect.hasIcon()
            )
            ClientboundUpdateMobEffectPacket(player.entityId, effectInstance)
        } else {
            // Remove the effect
            ClientboundRemoveMobEffectPacket(player.entityId, MobEffect.byId(4))
        }
        
        player.send(packet)
    }
    
    fun isDone() = progress >= 1
    
}

private abstract class BreakMethod(protected val block: Block) {
    
    abstract var breakStage: Int
    
    abstract fun stop()
    
}

private class PacketBreakMethod(block: Block) : BreakMethod(block) {
    
    private val fakeEntityId = Random.nextInt()
    
    override var breakStage: Int = -1
        set(stage) {
            if (field == stage) return
            
            field = stage
            block.setBreakState(fakeEntityId, stage)
        }
    
    override fun stop() {
        block.setBreakState(fakeEntityId, -1)
    }
    
}

private class ArmorStandBreakMethod(block: Block) : BreakMethod(block) {
    
    private val armorStand = FakeArmorStand(block.location.center(), true) {
        it.isInvisible = true
        it.isMarker = true
        it.setSharedFlagOnFire(block.type.requiresLight)
    }
    
    override var breakStage: Int = -1
        set(stage) {
            if (field == stage) return
            
            field = stage
            if (stage in 0..9) {
                armorStand.setEquipment(MojangSlot.HEAD, CoreBlockOverlay.BREAK_STAGE_OVERLAY.item.createItemStack(stage))
                armorStand.updateEquipment()
            } else {
                armorStand.setEquipment(MojangSlot.HEAD, null)
                armorStand.updateEquipment()
            }
        }
    
    override fun stop() {
        armorStand.remove()
    }
    
}