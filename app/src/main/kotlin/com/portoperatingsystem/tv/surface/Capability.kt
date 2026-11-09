package com.portoperatingsystem.tv.surface

import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The screen the surface renders to: 960 x 540 dp whatever the panel, so the density follows the panel width. */
internal object SurfaceDisplay {
    private const val DENSITY_DECIMALS = 100.0

    /** The Compose density that makes a [widthPx] wide panel [PortosTokens.Display.LAYOUT_WIDTH_DP] dp wide. */
    fun density(widthPx: Int): Float = widthPx.toFloat() / PortosTokens.Display.LAYOUT_WIDTH_DP

    /** The `display` object of the capability registration and of `get-surface-response`. */
    fun info(widthPx: Int, heightPx: Int): JsonObject {
        val density = density(widthPx)
        return buildJsonObject {
            put("widthPx", widthPx)
            put("heightPx", heightPx)
            put("widthDp", PortosTokens.Display.LAYOUT_WIDTH_DP)
            put("heightDp", (heightPx / density).roundToInt())
            put("density", (density * DENSITY_DECIMALS).roundToInt() / DENSITY_DECIMALS)
            put("safeAreaInsetDp", PortosTokens.Display.SAFE_AREA_INSET_DP)
            put("viewingDistance", "TEN_FOOT")
        }
    }
}

/**
 * The `reports.generativeUi` capability registration: what this app renders. The components list is the rendering
 * contract of `docs/design/generative-ui.md`, with a JSON Schema per component for the agent and a note on how it
 * renders; the plugin answers `get-components` from it.
 */
internal object Capability {
    const val CATALOG_VERSION = "1.0"
    const val BASIC_CATALOG = "https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json"
    const val TV_CATALOG = "https://portoperatingsystem.com/a2ui/catalogs/tv/v1/catalog.json"
    const val MAX_PAYLOAD_BYTES = 524_288

    /** The component names this app renders, in registration order. */
    val componentNames: List<String> get() = components.map {
        (it as JsonObject).getValue("name").jsonPrimitiveContent()
    }

    fun registration(display: JsonObject): JsonObject = buildJsonObject {
        put("catalogVersion", CATALOG_VERSION)
        put("catalogIds", JsonArray(listOf(JsonPrimitive(BASIC_CATALOG), JsonPrimitive(TV_CATALOG))))
        put("maxPayloadBytes", MAX_PAYLOAD_BYTES)
        put("display", display)
        put("components", JsonArray(components))
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent(): String = (this as JsonPrimitive).content

    /**
     * One registered component. `properties` is the JSON Schema (draft 2020-12) of the whole component object as the
     * TV accepts it, `id` and `component` included, so the plugin can validate a component list against it.
     */
    private fun component(name: String, rendering: String, properties: String, vararg required: String): JsonObject =
        buildJsonObject {
            put("name", name)
            put(
                "properties",
                buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put("id", Json.parseToJsonElement("""{"type":"string"}"""))
                            put("component", Json.parseToJsonElement("""{"const":"$name"}"""))
                            val own = properties.trim().removePrefix("{").removeSuffix("}")
                            Json.parseToJsonElement("{$own,$WEIGHT,$ACCESSIBILITY}").jsonObject
                                .forEach { (key, schema) -> put(key, schema) }
                        }
                    )
                    put("required", JsonArray((listOf("id", "component") + required).map(::JsonPrimitive)))
                    put("additionalProperties", false)
                }
            )
            put("rendering", rendering)
        }

    private const val WEIGHT =
        """"weight":{"type":"number","description":"Share of the free space in a Row or Column."}"""
    private const val ACCESSIBILITY =
        """"accessibility":{"type":"object","description":"Accepted and ignored: nothing on the TV is focused."}"""
    private const val STRING = """{"oneOf":[{"type":"string"},{"type":"object","required":["@path"]}]}"""
    private const val CHILDREN = """{"type":"array","items":{"type":"string"}}"""
    private const val JUSTIFY = """"justify":{"enum":["start","center","end","spaceBetween","spaceAround",
        "spaceEvenly","stretch"]},"align":{"enum":["start","center","end","stretch"]}"""

    private val components: List<JsonObject> = listOf(
        component(
            "Text",
            "Text in the Port OS type scale. Markdown headings set the level: # headline, ## page title, ### section " +
                "heading, #### to ##### subsection heading; variant body is the body size, caption the micro size. " +
                "Default text color.",
            """{"text":$STRING,"variant":{"enum":["body","caption"]}}""",
            "text"
        ),
        component(
            "Image",
            "Loaded from the URL by the TV; a fixed size per variant, in the card radius (avatars round). " +
                "Shows the surface color while loading and a muted placeholder if the load fails.",
            """{"url":$STRING,"description":$STRING,
                "fit":{"enum":["contain","cover","fill","none","scaleDown"]},
                "variant":{"enum":["icon","avatar","smallFeature","mediumFeature","largeFeature","header"]}}""",
            "url"
        ),
        component(
            "Icon",
            "A vector icon at the icon size in the default text color. Names are the A2UI basic-catalog names.",
            """{"name":{"type":"string"}}""",
            "name"
        ),
        component(
            "Row",
            "A horizontal group. A child's weight shares the free space, so two weighted children make left and " +
                "right columns. Gaps come from the spacing scale.",
            """{"children":$CHILDREN,$JUSTIFY}""",
            "children"
        ),
        component(
            "Column",
            "A vertical group. A child's weight shares the free height. Gaps come from the spacing scale.",
            """{"children":$CHILDREN,$JUSTIFY}""",
            "children"
        ),
        component(
            "List",
            "A row or column of children that does not scroll: nothing on the TV can be focused, so overflow is " +
                "clipped. Size lists to fit the screen.",
            """{"children":$CHILDREN,"direction":{"enum":["vertical","horizontal"]},
                "align":{"enum":["start","center","end","stretch"]}}""",
            "children"
        ),
        component(
            "Card",
            "A rounded surface with the card padding and a raised shadow around one child.",
            """{"child":{"type":"string"}}""",
            "child"
        ),
        component(
            "Divider",
            "A hairline in the border color.",
            """{"axis":{"enum":["horizontal","vertical"]}}"""
        ),
        component(
            "Timer",
            "Port OS component (catalog $TV_CATALOG). Ticks every second on the TV, so send it once. COUNTDOWN " +
                "counts down to endsAt and then shows doneText in the brand color; COUNT_UP counts up from " +
                "startedAt. Headline size; the label is above it.",
            """{"label":$STRING,"mode":{"enum":["COUNTDOWN","COUNT_UP"]},
                "endsAt":{"type":"string","format":"date-time"},
                "startedAt":{"type":"string","format":"date-time"},"doneText":$STRING}""",
            "mode"
        )
    )
}
