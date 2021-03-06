package lingerloot

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import lingerloot.ruleengine.*
import lingerloot.volatility.EntityItemExploding
import lingerloot.volatility.DespawnDispatcher
import net.minecraft.entity.Entity
import net.minecraft.entity.item.EntityItem
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.common.util.FakePlayer
import net.minecraftforge.event.AttachCapabilitiesEvent
import net.minecraftforge.event.entity.EntityJoinWorldEvent
import net.minecraftforge.event.entity.item.ItemExpireEvent
import net.minecraftforge.event.entity.item.ItemTossEvent
import net.minecraftforge.event.entity.living.LivingDropsEvent
import net.minecraftforge.event.world.BlockEvent.HarvestDropsEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.SidedProxy
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent
import net.minecraftforge.fml.common.event.FMLServerStartingEvent
import net.minecraftforge.fml.common.eventhandler.EventPriority
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.common.registry.EntityRegistry
import org.apache.logging.log4j.Logger
import java.io.File
import java.util.*

val MINECRAFT_LIFESPAN = EntityItem(null).lifespan // must match minecraft's default

val CREATIVE_GIVE_DESPAWN_TICK = {val e = EntityItem(null); e.setAgeToCreativeDespawnTime(); e.extractAge() + 1}()

val INFINITE_PICKUP_DELAY = {val e = EntityItem(null); e.setInfinitePickupDelay(); e.extractPickupDelay()}()
val DEFAULT_PICKUP_DELAY = {val e = EntityItem(null); e.setDefaultPickupDelay(); e.extractPickupDelay()}()
val DROP_PICKUP_DELAY = 40

val jitteringItems = Collections.newSetFromMap(WeakHashMap<EntityItem, Boolean>())

val JITTER_TIME = 300

const val MODID = "lingeringloot"

lateinit var logger: Logger
lateinit var rulesFile: File

@SidedProxy(clientSide = "lingerloot.ClientProxy", serverSide = "lingerloot.ServerProxy") var proxy: CommonProxy? = null

@Mod(modid = MODID, version = "4.4", acceptableRemoteVersions="*")
class LingeringLoot {
    @Mod.EventHandler
    fun preInit (event: FMLPreInitializationEvent) {
        logger = event.modLog

        rulesFile = event.modConfigurationDirectory.resolve("lingeringloot.rules")
        LingeringLootConfig(event.modConfigurationDirectory)
        MinecraftForge.EVENT_BUS.register(EventHandler)

        EntityRegistry.registerModEntity(ResourceLocation(MODID, "EntityItemExploding"), EntityItemExploding::class.java, "Exploding Item",
                0, this, 64, 15, true)

        registerCapabilities()
        initMessageContexts()

        proxy?.preInit(event)
    }

    @Mod.EventHandler
    fun postInit (event: FMLPostInitializationEvent) {
        LingerRulesEngine.loadRulesFile(rulesFile, logger)
    }

    @Mod.EventHandler
    fun start(e: FMLServerStartingEvent) {
        LingerRulesEngine.registerReloadCommand(e, "llreload")
    }
}


val prescreen = Object2IntOpenHashMap<EntityItem>()

object EventHandler {
    private val jitterSluice by lazy { JitterNotificationQueue() }

    fun applyRules(item: EntityItem, causeMask: Int) {
        if (item !is EntityItemExploding && item.extractPickupDelay() != INFINITE_PICKUP_DELAY &&
                !item.item.isEmpty && item.extractAge() == 1) // ignore cosmetic fake item, empty item, or unexpected age
        {
            LingerRulesEngine.act(EntityItemCTX(item, causeMask))?.let { logger.error(it) }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun onPlayerTossItem(event: ItemTossEvent) {
        if (! event.entityItem.entityWorld.isRemote)
            prescreen.putIfAbsent(event.entityItem, CausePredicates.PLAYERTOSS.mask)
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun onLivingDropsEvent(event: LivingDropsEvent) {
        if (event.entity.entityWorld.isRemote) return

        var target = CausePredicates.MOBDROP.mask
        if (event.entityLiving is EntityPlayer)
            target += CausePredicates.PLAYERDROP.mask
        if (event.source.immediateSource is EntityPlayer || event.source.trueSource is EntityPlayer)
            target += CausePredicates.PLAYERKILL.mask

        for (drop in event.drops) prescreen.putIfAbsent(drop, target)
    }

    private var playerHarvested = mutableSetOf<ItemStack>()

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun onHarvestDrops(event: HarvestDropsEvent) {
        if (event.harvester?.entityWorld?.isRemote == false)
            if (event.harvester != null && event.harvester !is FakePlayer)
                playerHarvested = event.drops.toMutableSet()
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun onEntitySpawn(event: EntityJoinWorldEvent) {
        if (event.entity.entityWorld.isRemote) return

        val entity = event.entity
        if (entity is EntityItem) {
            val target = if (playerHarvested.remove(entity.item)) {
                CausePredicates.PLAYERMINE
            } else {
                CausePredicates.OTHER
            }

            prescreen.putIfAbsent(entity, target.mask)
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun onItemDespawn(event: ItemExpireEvent) {
        if (!event.entity.entityWorld.isRemote)
            DespawnDispatcher.dispatch(event)
    }

    @SubscribeEvent
    fun onCapabilityAttachEntity(e: AttachCapabilitiesEvent<Entity>) {
        if (e.`object` is EntityItem) {
            e.addCapability(ResourceLocation(MODID, "touched"), TouchedByLingeringLewd())
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase == TickEvent.Phase.START) {
            val dump = prescreen.entries.map{Pair(it.key, it.value)}.toTypedArray()
            prescreen.clear()
            dump.forEach {
                val causemaskCreative = if (detectCreativeGiveSecondTick(it.first))
                    CausePredicates.CREATIVEGIVE.mask
                else
                    it.second

                applyRules(it.first, causemaskCreative)
                jitterSluice.prepareToDie(it.first)
            }
            jitterSluice.tick()
        }
    }

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase == TickEvent.Phase.START) {jitteringItems.filterInPlace{it?.ifAlive() != null}}
    }
}