package xyz.xenondevs.nova.tileentity.impl

import de.studiocode.invui.gui.GUI
import de.studiocode.invui.gui.SlotElement.VISlotElement
import de.studiocode.invui.gui.builder.GUIBuilder
import de.studiocode.invui.gui.builder.GUIType
import de.studiocode.invui.virtualinventory.VirtualInventory
import de.studiocode.invui.virtualinventory.event.ItemUpdateEvent
import de.studiocode.invui.window.impl.single.SimpleWindow
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.nova.config.NovaConfig
import xyz.xenondevs.nova.material.NovaMaterial
import xyz.xenondevs.nova.network.energy.EnergyConnectionType
import xyz.xenondevs.nova.network.item.inventory.NetworkedInventory
import xyz.xenondevs.nova.tileentity.EnergyItemTileEntity
import xyz.xenondevs.nova.tileentity.SELF_UPDATE_REASON
import xyz.xenondevs.nova.ui.config.OpenSideConfigItem
import xyz.xenondevs.nova.ui.config.SideConfigGUI
import xyz.xenondevs.nova.ui.item.StorageUnitDisplay
import xyz.xenondevs.nova.util.runTaskLater
import java.util.*
import kotlin.math.min

private val MAX_ITEMS = NovaConfig.getInt("item_storage_unit.max_items")!!

class StorageUnit(
    ownerUUID: UUID?,
    material: NovaMaterial,
    armorStand: ArmorStand
) : EnergyItemTileEntity(ownerUUID, material, armorStand) {
    
    override val defaultEnergyConfig by lazy { createEnergySideConfig(EnergyConnectionType.NONE) }
    
    private val inventory = StorageUnitInventory(retrieveOrNull("type"), retrieveOrNull("amount") ?: 0)
    private val inputInventory = VirtualInventory(null, 1).apply { setItemUpdateHandler(::handleInputInventoryUpdate) }
    private val outputInventory = VirtualInventory(null, 1).apply { setItemUpdateHandler(::handleOutputInventoryUpdate) }
    private val gui by lazy { ItemStorageGUI() }
    
    init {
        setDefaultInventory(inventory)
    }
    
    fun handleInputInventoryUpdate(event: ItemUpdateEvent) {
        if (event.isAdd && inventory.type != null && !inventory.type!!.isSimilar(event.newItemStack))
            event.isCancelled = true
    }
    
    fun handleOutputInventoryUpdate(event: ItemUpdateEvent) {
        if (event.updateReason == SELF_UPDATE_REASON)
            return
        
        if (event.isAdd) {
            event.isCancelled = true
        } else if (event.isRemove && inventory.type != null) {
            inventory.amount -= event.removedAmount
            if (inventory.amount == 0) inventory.type = null
            
            runTaskLater(1, gui::updateWindows)
        }
    }
    
    fun updateOutputSlot() {
        if (inventory.type == null)
            outputInventory.removeItem(SELF_UPDATE_REASON, 0)
        else
            outputInventory.setItemStack(SELF_UPDATE_REASON, 0, inventory.type!!.apply { amount = min(type.maxStackSize, inventory.amount) })
    }
    
    override fun handleInitialized(first: Boolean) {
        super.handleInitialized(first)
        gui.updateWindows()
    }
    
    override fun handleTick() {
        val item = inputInventory.getItemStack(0)
        if (item != null) {
            val remaining = inventory.addItem(item)
            if (remaining == null)
                inputInventory.removeItem(null, 0)
            else
                inputInventory.setItemStack(null, 0, remaining)
        }
    }
    
    override fun handleRightClick(event: PlayerInteractEvent) {
        event.isCancelled = true
        gui.openWindow(event.player)
    }
    
    override fun saveData() {
        super.saveData()
        storeData("type", inventory.type)
        storeData("amount", inventory.amount)
    }
    
    private inner class ItemStorageGUI {
        
        private val sideConfigGUI = SideConfigGUI(
            this@StorageUnit,
            listOf(EnergyConnectionType.NONE, EnergyConnectionType.CONSUME),
            listOf(inventory to "Inventory"),
            ::openWindow
        )
        
        val storageUnitDisplay = StorageUnitDisplay(inventory)
        
        private val gui: GUI = GUIBuilder(GUIType.NORMAL, 9, 3)
            .setStructure("" +
                "1 - - - - - - - 2" +
                "| # i # c # o s |" +
                "3 - - - - - - - 4")
            .addIngredient('c', storageUnitDisplay)
            .addIngredient('i', VISlotElement(inputInventory, 0))
            .addIngredient('o', VISlotElement(outputInventory, 0))
            .addIngredient('s', OpenSideConfigItem(sideConfigGUI))
            .build()
        
        fun openWindow(player: Player) =
            SimpleWindow(player, "Storage Unit", gui).show()
        
        fun updateWindows() {
            storageUnitDisplay.notifyWindows()
            updateOutputSlot()
        }
    }
    
    
    @Suppress("LiftReturnOrAssignment")
    inner class StorageUnitInventory(var type: ItemStack? = null, var amount: Int = 0) : NetworkedInventory {
        
        override val size: Int
            get() = 1
        
        override val items: Array<ItemStack?>
            get() {
                type ?: return emptyArray()
                return arrayOf((type!!.clone().also { it.amount = min(type!!.maxStackSize, amount) }))
            }
        
        override fun addItem(item: ItemStack): ItemStack? {
            val remaining: ItemStack?
            
            if (type == null) { // Storage unit is empty
                type = item
                amount = item.amount
                remaining = null
            } else if (type!!.isSimilar(item)) { // The item is the same as the one stored in the unit
                val leeway = MAX_ITEMS - amount
                if (leeway >= item.amount) { // The whole stack fits into the storage unit
                    amount += item.amount
                    remaining = null
                } else remaining = item.clone().also { amount -= leeway }  // Not all items fit so a few will remain
            } else remaining = item // The item isn't the same as the one stored in the unit
            
            gui.updateWindows()
            return remaining
        }
        
        override fun setItem(slot: Int, item: ItemStack?) {
            amount -= min(type!!.maxStackSize, amount) - (item?.amount ?: 0)
            if (item != null || amount == 0)
                type = item
            
            if (amount == 0) type = null
            gui.updateWindows()
        }
    }
    
}