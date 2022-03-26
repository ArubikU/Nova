package xyz.xenondevs.nova.material

import de.studiocode.invui.item.builder.ItemBuilder
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.nova.data.resources.Resources
import xyz.xenondevs.nova.data.serialization.cbf.element.CompoundElement
import xyz.xenondevs.nova.item.NovaItem
import xyz.xenondevs.nova.tileentity.TileEntity
import xyz.xenondevs.nova.world.armorstand.FakeArmorStand
import java.util.*
import java.util.concurrent.CompletableFuture

typealias ItemBuilderModifierFun = (ItemBuilder, TileEntity?) -> ItemBuilder
typealias TileEntityConstructor = ((UUID, CompoundElement, TileEntityNovaMaterial, UUID, FakeArmorStand) -> TileEntity)
typealias PlaceCheckFun = ((Player, ItemStack, Location) -> CompletableFuture<Boolean>)

class TileEntityNovaMaterial internal constructor(
    id: String,
    localizedName: String,
    novaItem: NovaItem? = null,
    private val itemBuilderModifiers: List<ItemBuilderModifierFun>? = null,
    val hitboxType: Material? = null,
    val tileEntityConstructor: TileEntityConstructor? = null,
    val placeCheck: PlaceCheckFun? = null,
    val isInteractable: Boolean = true,
    val isDirectional: Boolean = true
) : ItemNovaMaterial(id, localizedName, novaItem) {
    
    val block: ModelData by lazy { Resources.getModelData(id).second!! }
    
    /**
     * Creates an [ItemBuilder][ItemBuilder] for this [ItemNovaMaterial].
     *
     * The [TileEntity] provided must be of the same type as the [TileEntity]
     * returned by the [tileEntityConstructor] function.
     */
    fun createItemBuilder(tileEntity: TileEntity): ItemBuilder {
        return modifyItemBuilder(createBasicItemBuilder(), tileEntity)
    }
    
    /**
     * Creates an [ItemStack] for this [ItemNovaMaterial].
     */
    fun createItemStack(amount: Int = 1, tileEntity: TileEntity): ItemStack =
        createItemBuilder(tileEntity).setAmount(amount).get()
    
    private fun modifyItemBuilder(itemBuilder: ItemBuilder, tileEntity: TileEntity): ItemBuilder {
        var builder = modifyItemBuilder(itemBuilder)
        itemBuilderModifiers?.forEach { builder = it.invoke(builder, tileEntity) }
        return builder
    }
    
}