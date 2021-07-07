package io.github.monun.kommand.v1_17_R1

import com.destroystokyo.paper.profile.CraftPlayerProfile
import com.destroystokyo.paper.profile.PlayerProfile
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.*
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.github.monun.kommand.KommandArgument
import io.github.monun.kommand.KommandArgumentSupport
import io.github.monun.kommand.StringType
import io.github.monun.kommand.internal.AbstractKommandArgument
import io.github.monun.kommand.internal.ArgumentNodeImpl
import io.github.monun.kommand.v1_17_R1.internal.NMSKommandContext
import io.github.monun.kommand.wrapper.EntityAnchor
import io.papermc.paper.brigadier.PaperBrigadier
import net.kyori.adventure.text.Component
import net.md_5.bungee.api.ChatColor
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.arguments.*
import net.minecraft.commands.synchronization.SuggestionProviders
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.advancement.Advancement
import org.bukkit.craftbukkit.v1_17_R1.CraftParticle
import org.bukkit.craftbukkit.v1_17_R1.enchantments.CraftEnchantment
import org.bukkit.craftbukkit.v1_17_R1.potion.CraftPotionEffectType
import org.bukkit.craftbukkit.v1_17_R1.util.CraftNamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.Recipe
import org.bukkit.potion.PotionEffectType
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Team
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.CompletableFuture

open class NMSKommandArgument<T>(
    val type: ArgumentType<*>,
    private val provider: (CommandContext<CommandSourceStack>, name: String) -> T,
    private val defaultSuggestionProvider: SuggestionProvider<CommandSourceStack>? = null
) : AbstractKommandArgument<T>() {
    private companion object {
        private val originalMethod: Method = ArgumentType::class.java.declaredMethods.find { method ->
            val parameterTypes = method.parameterTypes

            parameterTypes.count() == 2
                    && parameterTypes[0] == CommandContext::class.java
                    && parameterTypes[1] == SuggestionsBuilder::class.java
        } ?: error("Not found listSuggestion")

        private val overrideSuggestions = hashMapOf<Class<*>, Boolean>()

        private fun checkOverrideSuggestions(type: Class<*>): Boolean = overrideSuggestions.computeIfAbsent(type) {
            originalMethod.declaringClass != type.getMethod(
                originalMethod.name,
                *originalMethod.parameterTypes
            ).declaringClass
        }
    }

    private val hasOverrideSuggestion: Boolean by lazy {
        checkOverrideSuggestions(type.javaClass)
    }

    @Suppress("UNCHECKED_CAST")
    fun from(context: CommandContext<CommandSourceStack>, name: String): T {
        return provider(context, name)
    }

    fun listSuggestions(
        node: ArgumentNodeImpl,
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder
    ): CompletableFuture<Suggestions> {
        this.suggestionProvider?.let {
            val suggestion = NMSKommandSuggestion(builder)
            it(suggestion, NMSKommandContext(node, context))
            if (!suggestion.suggestsDefault) return builder.buildFuture()
        }

        defaultSuggestionProvider?.let { return it.getSuggestions(context, builder) }
        if (hasOverrideSuggestion) return type.listSuggestions(context, builder)
        return builder.buildFuture()
    }
}

infix fun <T> ArgumentType<*>.provide(
    provider: (context: CommandContext<CommandSourceStack>, name: String) -> T
): NMSKommandArgument<T> {
    return NMSKommandArgument(this, provider)
}

infix fun <T> Pair<ArgumentType<*>, SuggestionProvider<CommandSourceStack>>.provide(
    provider: (context: CommandContext<CommandSourceStack>, name: String) -> T
): NMSKommandArgument<T> {
    return NMSKommandArgument(first, provider, second)
}

class NMSKommandArgumentSupport : KommandArgumentSupport {
    override fun bool(): KommandArgument<Boolean> {
        return BoolArgumentType.bool() provide BoolArgumentType::getBool
    }

    override fun int(minimum: Int, maximum: Int): KommandArgument<Int> {
        return IntegerArgumentType.integer(minimum, maximum) provide IntegerArgumentType::getInteger
    }

    override fun float(minimum: Float, maximum: Float): KommandArgument<Float> {
        return FloatArgumentType.floatArg(minimum, maximum) provide FloatArgumentType::getFloat
    }

    override fun double(minimum: Double, maximum: Double): KommandArgument<Double> {
        return DoubleArgumentType.doubleArg(minimum, maximum) provide DoubleArgumentType::getDouble
    }

    override fun long(minimum: Long, maximum: Long): KommandArgument<Long> {
        return LongArgumentType.longArg(minimum, maximum) provide LongArgumentType::getLong
    }

    override fun string(type: StringType): KommandArgument<String> {
        return when (type) {
            StringType.SINGLE_WORD -> StringArgumentType.word()
            StringType.QOUTABLE_PHRASE -> StringArgumentType.string()
            StringType.GREEDY_PHRASE -> StringArgumentType.greedyString()
        } provide StringArgumentType::getString
    }

    override fun angle(): KommandArgument<Float> {
        return AngleArgument.angle() provide AngleArgument::getAngle
    }

    override fun color(): KommandArgument<ChatColor> {
        return ColorArgument.color() provide { context, name ->
            ColorArgument.getColor(context, name)
                .let { ChatColor.of(it.getName()) ?: error("Not found color") }
        }
    }

    override fun component(): KommandArgument<Component> {
        return ComponentArgument.textComponent() provide { context, name ->
            val nmsComponent = ComponentArgument.getComponent(context, name)
            PaperBrigadier.componentFromMessage(nmsComponent)
        }
    }

    override fun compoundTag(): KommandArgument<JsonObject> {
        return CompoundTagArgument.compoundTag() provide { context, name ->
            val compoundTag = CompoundTagArgument.getCompoundTag(context, name)
            JsonParser().parse(compoundTag.toString()) as JsonObject
        }
    }

    override fun dimension(): KommandArgument<World> {
        return DimensionArgument.dimension() provide { context, name ->
            DimensionArgument.getDimension(context, name).world
        }
    }

    override fun entityAnchor(): KommandArgument<EntityAnchor> {
        return EntityAnchorArgument.anchor() provide { context, name ->
            when (EntityAnchorArgument.getAnchor(context, name) ?: error("Unknown entity anchor")) {
                EntityAnchorArgument.Anchor.FEET -> EntityAnchor.FEET
                EntityAnchorArgument.Anchor.EYES -> EntityAnchor.EYES
            }
        }
    }

    override fun entity(): KommandArgument<Entity> {
        return EntityArgument.entity() provide { context, name ->
            EntityArgument.getEntity(context, name).bukkitEntity
        }
    }

    override fun entities(): KommandArgument<Collection<Entity>> {
        return EntityArgument.entities() provide { context, name ->
            EntityArgument.getEntities(context, name).map { it.bukkitEntity }
        }
    }

    override fun player(): KommandArgument<Player> {
        return EntityArgument.player() provide { context, name ->
            EntityArgument.getPlayer(context, name).bukkitEntity
        }
    }

    override fun players(): KommandArgument<Collection<Player>> {
        return EntityArgument.players() provide { context, name ->
            EntityArgument.getPlayers(context, name).map { it.bukkitEntity }
        }
    }

    override fun summonableEntity(): KommandArgument<NamespacedKey> {
        return EntitySummonArgument.id() to SuggestionProviders.SUMMONABLE_ENTITIES provide { context, name ->
            CraftNamespacedKey.fromMinecraft(EntitySummonArgument.getSummonableEntity(context, name))
        }
    }

    override fun profile(): KommandArgument<Collection<PlayerProfile>> {
        return GameProfileArgument.gameProfile() provide { context, name ->
            val nms = GameProfileArgument.getGameProfiles(context, name)
            nms.map { CraftPlayerProfile.asBukkitMirror(it) }
        }
    }

    private val enchantmentMap = Enchantment.values().map { it as CraftEnchantment }.associateBy { it.handle }

    override fun enchantment(): KommandArgument<Enchantment> {
        return ItemEnchantmentArgument.enchantment() provide { context, name ->
            val nms = ItemEnchantmentArgument.getEnchantment(context, name)

            enchantmentMap[nms] ?: error("Not found enchantment ${nms.getFullname(0)}")
        }
    }

    override fun message(): KommandArgument<Component> {
        return MessageArgument.message() provide { context, name ->
            PaperBrigadier.componentFromMessage(MessageArgument.getMessage(context, name))
        }
    }

    private val mobEffectMap = PotionEffectType.values().map { it as CraftPotionEffectType }.associateBy { it.handle }

    override fun mobEffect(): KommandArgument<PotionEffectType> {
        return MobEffectArgument.effect() provide { context, name ->
            val nms = MobEffectArgument.getEffect(context, name)
            mobEffectMap[nms] ?: error("Not found mob effect ${nms.displayName}")
        }
    }

    override fun objective(): KommandArgument<Objective> {
        return ObjectiveArgument.objective() provide { context, name ->
            val nms = ObjectiveArgument.getObjective(context, name)
            Bukkit.getScoreboardManager().mainScoreboard.getObjective(nms.name) ?: error("Objective error!")
        }
    }

    override fun objectiveCriteria(): KommandArgument<String> {
        return ObjectiveCriteriaArgument.criteria() provide { context, name ->
            ObjectiveCriteriaArgument.getCriteria(context, name).name
        }
    }

    override fun particle(): KommandArgument<Particle> {
        return ParticleArgument.particle() provide { context, name ->
            CraftParticle.toBukkit(ParticleArgument.getParticle(context, name))
        }
    }

    override fun intRange(): KommandArgument<IntRange> {
        return RangeArgument.intRange() provide { context, name ->
            val nms = RangeArgument.Ints.getRange(context, name)
            val min = nms.min ?: Int.MIN_VALUE
            val max = nms.max ?: Int.MAX_VALUE
            min..max
        }
    }

    //float
    override fun doubleRange(): KommandArgument<ClosedRange<Double>> {
        return RangeArgument.floatRange() provide { context, name ->
            val nms = RangeArgument.Floats.getRange(context, name)
            val min = nms.min ?: Double.MIN_VALUE
            val max = nms.max ?: Double.MAX_VALUE
            min.rangeTo(max)
        }
    }

    override fun advancement(): KommandArgument<Advancement> {
        return ResourceLocationArgument.id() provide { context, name ->
            val nms = ResourceLocationArgument.getAdvancement(context, name)
            nms.bukkit
        }
    }

    override fun recipe(): KommandArgument<Recipe> {
        return ResourceLocationArgument.id() to SuggestionProviders.ALL_RECIPES provide { context, name ->
            val nms = ResourceLocationArgument.getRecipe(context, name)
            nms.toBukkitRecipe()
        }
    }

    private val displaySlots = DisplaySlot.values().toList()

    override fun displaySlot(): KommandArgument<DisplaySlot> {
        return ScoreboardSlotArgument.displaySlot() provide { context, name ->
            displaySlots[ScoreboardSlotArgument.getDisplaySlot(context, name)]
        }
    }

    override fun score(): KommandArgument<String> {
        return ScoreHolderArgument.scoreHolder() provide { context, name ->
            ScoreHolderArgument.getName(context, name)
        }
    }

    override fun scores(): KommandArgument<Collection<String>> {
        return ScoreHolderArgument.scoreHolders() provide { context, name ->
            ScoreHolderArgument.getNames(context, name)
        }
    }

    override fun slot(): KommandArgument<Int> {
        return SlotArgument.slot() provide { context, name ->
            SlotArgument.getSlot(context, name)
        }
    }

    override fun team(): KommandArgument<Team> {
        return TeamArgument.team() provide { context, name ->
            val nms = TeamArgument.getTeam(context, name)
            Bukkit.getScoreboardManager().mainScoreboard.getTeam(nms.name) ?: error("Team error!")
        }
    }

    override fun time(): KommandArgument<Int> {
        val argument = TimeArgument.time()
        return argument provide { context, name ->
            val time = context.getArgument(name, String::class.java)
            argument.parse(StringReader(time))
        }
    }

    fun uuid(): KommandArgument<UUID> {
        return UuidArgument.uuid() provide UuidArgument::getUuid
    }
}