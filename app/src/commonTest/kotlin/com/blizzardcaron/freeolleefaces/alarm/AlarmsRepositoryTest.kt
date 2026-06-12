package com.blizzardcaron.freeolleefaces.alarm

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlarmsRepositoryTest {
    @Test fun `save inserts then replaces by id, capped at MAX_ALARMS`() {
        val repo = AlarmsRepository(MapSettings())
        repeat(6) { i -> repo.save(Alarm(id = "id$i", hour = i.coerceAtMost(23), minute = 0)) }
        // 6th insert is dropped by the cap.
        assertEquals(AlarmsRepository.MAX_ALARMS, repo.getAll().size)
        assertEquals(5, AlarmsRepository.MAX_ALARMS)

        // Replace keeps position and count.
        repo.save(Alarm(id = "id0", hour = 22, minute = 15))
        assertEquals(22, repo.get("id0")!!.hour)
        assertEquals(5, repo.getAll().size)
    }

    @Test fun `delete removes by id`() {
        val repo = AlarmsRepository(MapSettings())
        repo.save(Alarm(id = "a", hour = 7, minute = 0))
        repo.delete("a")
        assertNull(repo.get("a"))
    }
}
