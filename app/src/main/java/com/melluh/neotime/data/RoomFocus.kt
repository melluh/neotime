package com.melluh.neotime.data

import com.melluh.neotime.timeedit.Room
import com.melluh.neotime.timeedit.RoomLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A room one tab asks another to show. This lives outside the routes because tabs restore their
 * saved state, which a route argument would get in the way of. Each tab clears what it has shown.
 */
class RoomFocus(private val maps: MapRepository) {
    private val _onMap = MutableStateFlow<RoomLocation?>(null)
    val onMap: StateFlow<RoomLocation?> = _onMap

    private val _inRooms = MutableStateFlow<Room?>(null)
    val inRooms: StateFlow<Room?> = _inRooms

    /** Sets the room up for the Map tab. Returns why it can't be shown, or null if it can. */
    suspend fun showOnMap(name: String, identifier: String? = null): String? =
        when (val result = maps.locate(name, identifier)) {
            is MapResult.Failed -> result.message
            is MapResult.Ok -> {
                _onMap.value = result.value
                if (result.value == null) "Not on the map" else null
            }
        }

    fun showInRooms(room: Room) {
        _inRooms.value = room
    }

    fun clearMap() {
        _onMap.value = null
    }

    fun clearRooms() {
        _inRooms.value = null
    }
}
