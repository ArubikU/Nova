package xyz.xenondevs.nova

import de.studiocode.invui.resourcepack.ForceResourcePack
import net.md_5.bungee.api.chat.ComponentBuilder
import org.bstats.bukkit.Metrics
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.java.JavaPlugin
import xyz.xenondevs.nova.api.event.NovaLoadDataEvent
import xyz.xenondevs.nova.command.CommandManager
import xyz.xenondevs.nova.data.config.DEFAULT_CONFIG
import xyz.xenondevs.nova.data.config.NovaConfig
import xyz.xenondevs.nova.data.config.PermanentStorage
import xyz.xenondevs.nova.data.database.DatabaseManager
import xyz.xenondevs.nova.data.recipe.RecipeManager
import xyz.xenondevs.nova.data.recipe.RecipeRegistry
import xyz.xenondevs.nova.i18n.LocaleManager
import xyz.xenondevs.nova.initialize.Initializer
import xyz.xenondevs.nova.integration.customitems.CustomItemServiceManager
import xyz.xenondevs.nova.item.ItemManager
import xyz.xenondevs.nova.network.PacketListener
import xyz.xenondevs.nova.player.ability.AbilityManager
import xyz.xenondevs.nova.player.advancement.AdvancementManager
import xyz.xenondevs.nova.player.attachment.AttachmentManager
import xyz.xenondevs.nova.player.equipment.ArmorEquipListener
import xyz.xenondevs.nova.tileentity.ChunkLoadManager
import xyz.xenondevs.nova.tileentity.TileEntityManager
import xyz.xenondevs.nova.tileentity.network.NetworkManager
import xyz.xenondevs.nova.tileentity.vanilla.VanillaTileEntityManager
import xyz.xenondevs.nova.ui.setGlobalIngredients
import xyz.xenondevs.nova.util.AsyncExecutor
import xyz.xenondevs.nova.util.callEvent
import xyz.xenondevs.nova.util.data.Version
import xyz.xenondevs.nova.util.runAsyncTask
import xyz.xenondevs.nova.world.ChunkReloadWatcher
import xyz.xenondevs.nova.world.armorstand.FakeArmorStandManager
import xyz.xenondevs.nova.world.loot.LootGeneration
import xyz.xenondevs.particle.utils.ReflectionUtils
import java.util.concurrent.CountDownLatch
import java.util.logging.Logger

lateinit var NOVA: Nova
lateinit var LOGGER: Logger
lateinit var PLUGIN_MANAGER: PluginManager
var IS_VERSION_CHANGE: Boolean = false

class Nova : JavaPlugin() {

    val version = Version(description.version.removeSuffix("-SNAPSHOT"))
    val devBuild = description.version.contains("SNAPSHOT")
    val disableHandlers = ArrayList<() -> Unit>()
    val pluginFile
        get() = file
    var isUninstalled = false
    
    override fun onEnable() {
        NOVA = this
        ReflectionUtils.setPlugin(this)
        LOGGER = logger
        PLUGIN_MANAGER = server.pluginManager
        
        IS_VERSION_CHANGE = PermanentStorage.retrieve("last_version") { "0.1" } != description.version
        PermanentStorage.store("last_version", description.version)
        
        setGlobalIngredients()
        Metrics(this, 11927)
        NovaConfig.init()
        Initializer.init()
    }
    
    override fun onDisable() {
        disableHandlers.forEach {
            runCatching(it).onFailure(Throwable::printStackTrace)
        }
        DatabaseManager.disconnect()
        AsyncExecutor.shutdown()
    }
    
}