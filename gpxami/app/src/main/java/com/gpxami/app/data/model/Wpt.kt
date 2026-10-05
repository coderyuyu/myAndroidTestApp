package com.gpxami.app.data.model

/**
 * Role of the waypoint along the route.
 */
enum class WptRole {
    START,
    END,
    REGULAR
}

/**
 * Enhanced Waypoint entity representing Start, End, or regular intermediate waypoints.
 * Stores resolved Level 1 and Level 2 administrative division names and provides
 * formatted persistent billboard labels.
 */
data class Wpt(
    val lat: Double,
    val lon: Double,
    val elevation: Double? = null,
    val name: String? = null,
    val desc: String? = null,
    val role: WptRole = WptRole.REGULAR,
    val adminLevel1: String? = null,
    val adminLevel2: String? = null
) {
    val isStart: Boolean get() = role == WptRole.START
    val isEnd: Boolean get() = role == WptRole.END

    /**
     * Formats the display label for map markers.
     * Displays only the WPT label itself without "起點: " or "迄點: " prefix,
     * falling back to Traditional Chinese "起點" or "迄點" if name is not set.
     */
    fun formatDisplayLabel(config: AdminDivisionConfig? = null): String {
        val fallback = when (role) {
            WptRole.START -> "起點"
            WptRole.END -> "迄點"
            WptRole.REGULAR -> "航點"
        }

        val cleanName = name?.trim()
        if (cleanName.isNullOrBlank()) {
            return fallback
        }

        // Strip any preexisting prefix such as "起點:", "起點：", "迄點:", "迄點：", "終點:", "終點："
        var stripped: String = cleanName
        val prefixesToStrip = listOf("起點:", "起點：", "迄點:", "迄點：", "終點:", "終點：")
        for (p in prefixesToStrip) {
            if (stripped.startsWith(p, ignoreCase = true)) {
                stripped = stripped.substring(p.length).trim()
            }
        }

        val label = if (stripped.isNotBlank()) stripped else fallback
        return toTraditionalChinese(label)
    }

    /**
     * Formats the administrative division label according to the specified [AdminDivisionConfig].
     */
    fun formatAdminLabel(config: AdminDivisionConfig): String? {
        val raw = when (config) {
            AdminDivisionConfig.LEVEL_1_ONLY -> {
                adminLevel1?.takeIf { it.isNotBlank() }
            }
            AdminDivisionConfig.LEVEL_1_AND_2 -> {
                when {
                    !adminLevel1.isNullOrBlank() && !adminLevel2.isNullOrBlank() -> {
                        if (adminLevel1.equals(adminLevel2, ignoreCase = true)) {
                            adminLevel1
                        } else {
                            "$adminLevel1 · $adminLevel2"
                        }
                    }
                    !adminLevel1.isNullOrBlank() -> adminLevel1
                    !adminLevel2.isNullOrBlank() -> adminLevel2
                    else -> null
                }
            }
        }
        return raw?.let { toTraditionalChinese(it) }
    }
}

/**
 * Utility function to normalize text into Traditional Chinese (繁體中文).
 */
fun toTraditionalChinese(text: String): String {
    return text
        .replace("台湾", "臺灣")
        .replace("台灣", "臺灣")
        .replace("台北", "臺北")
        .replace("台中", "臺中")
        .replace("台南", "臺南")
        .replace("台东", "臺東")
        .replace("县", "縣")
        .replace("区", "區")
        .replace("湾", "灣")
        .replace("义", "義")
        .replace("兰", "蘭")
        .replace("园", "園")
        .replace("连", "連")
        .replace("莲", "蓮")
        .replace("门", "門")
        .replace("乡", "鄉")
        .replace("镇", "鎮")
        .replace("岛", "島")
        .replace("国", "國")
        .replace("国际", "國際")
        .replace("起点", "起點")
        .replace("终点", "迄點")
        .replace("航点", "航點")
        .replace("桥", "橋")
        .replace("峡", "峽")
        .replace("头", "頭")
        .replace("关", "關")
        .replace("岭", "嶺")
        .replace("龙", "龍")
        .replace("云", "雲")
        .replace("庄", "莊")
        .replace("东", "東")
}

/**
 * Extension to convert [GpxWaypoint] to [Wpt].
 */
fun GpxWaypoint.toWpt(
    role: WptRole = WptRole.REGULAR,
    adminLevel1: String? = null,
    adminLevel2: String? = null
): Wpt = Wpt(
    lat = lat,
    lon = lon,
    elevation = elevation,
    name = name,
    desc = desc,
    role = role,
    adminLevel1 = adminLevel1,
    adminLevel2 = adminLevel2
)
