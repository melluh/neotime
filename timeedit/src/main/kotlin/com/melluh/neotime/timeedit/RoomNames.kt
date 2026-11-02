package com.melluh.neotime.timeedit

/**
 * Translates TimeEdit's room names, which vary per source: "VR 275C (7p, Library)" on the schedule,
 * "VR_170" or "CR 3014 TNW students only!" in the room list. MazeMap's identifier is the shared key.
 */
object RoomNames {
    private val PARENTHESES = Regex("""\s*\(.*$""")
    private val SPACING = Regex("""[\s_]+""")

    /** A building code and room, ignoring what follows: "CR 3014" in "CR 3014 TNW students only!". */
    private val ROOM_CODE = Regex("""^([A-Z]+) ([A-Z]*\d+[A-Z]*)\b""")

    /** Where a building's code in TimeEdit isn't its code in MazeMap. Others only lose the space. */
    private val BUILDING_CODES = mapOf(
        "CR" to "CRC",
        "HR" to "HC",
        "HT" to "HCT",
        "NH" to "HCNH",
        "OH" to "HCOH",
    )

    /** Rooms whose MazeMap identifier can't be derived from their name, as linked by TimeEdit. */
    private val OVERRIDES = mapOf(
        "CR 2G" to "CRC2173",
        "CR 2H" to "CRC2175",
        "CR 2K" to "CRC2183",
        "CR 2L" to "CRC2178",
        "CR 2M" to "CRC2182",
        "CR 2N" to "CRC2186",
        "CR 3B" to "CRC3175",
        "CR 3E" to "CRC3182",
        "CR 3F" to "CRC3186",
        "CR 3G" to "CRC3190",
        "CR 3H" to "CRC3194",
        "SC 1" to "SC012",
        "SP 1" to "SPC101",
        "SP 2" to "SPC105",
        "SP 3" to "SPC102",
        "SP 4" to "SPC106",
        "SP 5" to "SPC110",
        "Therm 1" to "TM103",
        "Therm 2" to "TM101",
        "WA 1" to "WA200VE",
        "WA 2" to "WA200VG",
    )

    /** [name] without its details and with even spacing, e.g. "VR 170" for "VR_170 (7p, Library)". */
    fun canonical(name: String): String = name.replace(PARENTHESES, "").replace(SPACING, " ").trim()

    /**
     * MazeMap identifiers [name] could have, likeliest first. These are guesses until MazeMap
     * confirms one, and there are none for what isn't a room, such as "**No Room Required".
     */
    fun identifierCandidates(name: String): List<String> {
        val key = canonical(name)
        OVERRIDES[key]?.let { return listOf(it) }
        val (building, room) = ROOM_CODE.find(key)?.destructured ?: return emptyList()
        return listOfNotNull(BUILDING_CODES[building]?.plus(room), building + room)
    }

    /**
     * The MazeMap identifier of an office as the people pages write it, e.g. "HCME328" for
     * "Horst Complex ME328". Null for what doesn't name a room, such as "Spiegel kantoortuin".
     */
    fun officeIdentifier(office: String): String? {
        val (building, room) = OFFICE.matchEntire(office.trim())?.destructured ?: return null
        return when (building) {
            "Carré" -> "CRC" + room.removePrefix("C")
            else -> OFFICE_BUILDINGS[building]?.plus(room)
        }
    }

    /**
     * What to search the people pages for to find the office with this MazeMap identifier, or
     * nothing if it isn't one: "NH107" for "HCNH107". The site won't match "3045" to "C3045", so
     * Carré takes both spellings, the common one first. Room codes can repeat across buildings,
     * so results still have to be checked with [officeIdentifier].
     */
    fun officeSearchTerms(identifier: String): List<String> {
        val carreRoom = identifier.removePrefix("CRC")
        if (carreRoom != identifier) {
            return if (OFFICE_ROOM.matches(carreRoom)) listOf("C$carreRoom", carreRoom) else emptyList()
        }
        val code = OFFICE_BUILDINGS.values.firstOrNull(identifier::startsWith) ?: return emptyList()
        return listOf(identifier.removePrefix(code)).filter(OFFICE_ROOM::matches)
    }

    private val OFFICE = Regex("""(.+?) ([A-Z]*\d+[A-Z]*)""")
    private val OFFICE_ROOM = Regex("""[A-Z]*\d+[A-Z]*""")

    /**
     * Checked against MazeMap. Carré is handled apart, as its rooms may or may not repeat the "C",
     * and Capitool is missing because MazeMap has no identifiers for its offices.
     */
    private val OFFICE_BUILDINGS = mapOf(
        "Boerderij" to "BO",
        "Citadel" to "CI",
        "Horst Complex" to "HC",
        "Langezijds" to "LA",
        "Nanolab" to "NL",
        "Paviljoen" to "PA",
        "Ravelijn" to "RA",
        "Spiegel" to "SP",
        "Sportcentrum" to "SC",
        "Technohal" to "TL",
        "Vrijhof" to "VR",
        "Zilverling" to "ZI",
    )
}
