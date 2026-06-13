package com.blizzardcaron.freeolleefaces.timer

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimerSetsRepositoryTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun repo() = TimerSetsRepository(MapSettings())

    /** Builds a TimerSet with exactly 10 slots so the invariant is satisfied. */
    private fun makeSet(id: String, name: String, durationSeconds: Int = 60): TimerSet =
        TimerSet(id, name, List(10) { i -> TimerSlot(label = "S${i + 1}", durationSeconds = durationSeconds + i) })

    // ---------------------------------------------------------------------------
    // Basic save / load roundtrip
    // ---------------------------------------------------------------------------

    @Test fun getAll_empty_returnsEmptyList() {
        assertEquals(emptyList(), repo().getAll(), "fresh repo should return empty list")
    }

    @Test fun save_thenGetAll_containsSet() {
        val r = repo()
        val set = makeSet("id1", "Morning")
        r.save(set)
        val all = r.getAll()
        assertEquals(1, all.size, "after saving one set, getAll should return 1 item")
        assertEquals(set, all.first(), "saved set should roundtrip identically")
    }

    @Test fun save_thenGet_byId_returnsSet() {
        val r = repo()
        val set = makeSet("id2", "Evening")
        r.save(set)
        assertEquals(set, r.get("id2"), "get by id should return the saved set")
    }

    @Test fun get_missingId_returnsNull() {
        val r = repo()
        r.save(makeSet("id1", "Morning"))
        assertNull(r.get("doesNotExist"), "get with unknown id should return null")
    }

    @Test fun save_existingId_replacesInPlace() {
        val r = repo()
        val original = makeSet("id1", "Morning")
        r.save(original)
        val updated = makeSet("id1", "Morning Updated", durationSeconds = 120)
        r.save(updated)
        val all = r.getAll()
        assertEquals(1, all.size, "replace should not grow the list")
        assertEquals(updated, all.first(), "replaced set should reflect the new values")
    }

    @Test fun save_multipleDistinctSets_allRetrievable() {
        val r = repo()
        val sets = (1..5).map { makeSet("id$it", "Set $it") }
        sets.forEach { r.save(it) }
        val all = r.getAll()
        assertEquals(5, all.size, "should have 5 sets after saving 5 distinct sets")
        sets.forEach { expected ->
            assertTrue(all.contains(expected), "saved set '${expected.name}' should be in getAll()")
        }
    }

    // ---------------------------------------------------------------------------
    // Cap at MAX_SETS
    // ---------------------------------------------------------------------------

    @Test fun save_beyondMaxSets_capsAtMaxSets() {
        val r = repo()
        // Save MAX_SETS + 2 distinct sets
        val count = TimerSetsRepository.MAX_SETS + 2
        for (i in 1..count) {
            r.save(makeSet("id$i", "Set $i"))
        }
        val all = r.getAll()
        assertEquals(
            TimerSetsRepository.MAX_SETS,
            all.size,
            "getAll should never exceed MAX_SETS (${TimerSetsRepository.MAX_SETS}), got ${all.size}",
        )
    }

    @Test fun save_exactlyMaxSets_noTruncation() {
        val r = repo()
        for (i in 1..TimerSetsRepository.MAX_SETS) {
            r.save(makeSet("id$i", "Set $i"))
        }
        assertEquals(
            TimerSetsRepository.MAX_SETS,
            r.getAll().size,
            "saving exactly MAX_SETS sets should store all of them",
        )
    }

    /** Replacing an existing set must NOT count as appending; the list should stay the same length. */
    @Test fun save_replaceDoesNotCountAgainstCap() {
        val r = repo()
        for (i in 1..TimerSetsRepository.MAX_SETS) {
            r.save(makeSet("id$i", "Set $i"))
        }
        // Replace the first set — should not grow or shrink
        r.save(makeSet("id1", "Set 1 Updated", durationSeconds = 999))
        assertEquals(
            TimerSetsRepository.MAX_SETS,
            r.getAll().size,
            "replacing a set should not change the list size",
        )
    }

    // ---------------------------------------------------------------------------
    // delete
    // ---------------------------------------------------------------------------

    @Test fun delete_existingSet_removesIt() {
        val r = repo()
        r.save(makeSet("id1", "Alpha"))
        r.save(makeSet("id2", "Beta"))
        r.delete("id1")
        val all = r.getAll()
        assertEquals(1, all.size, "after delete, only one set should remain")
        assertEquals("id2", all.first().id, "remaining set should be the non-deleted one")
    }

    // ---------------------------------------------------------------------------
    // activeId
    // ---------------------------------------------------------------------------

    @Test fun activeId_unset_returnsNull() {
        assertNull(repo().activeId(), "activeId should be null on a fresh repo")
    }

    @Test fun setActive_thenActiveId_returnsId() {
        val r = repo()
        r.save(makeSet("id1", "Morning"))
        r.setActive("id1")
        assertEquals("id1", r.activeId(), "activeId should return the id passed to setActive")
    }

    @Test fun delete_activeSet_clearsActiveId() {
        val r = repo()
        r.save(makeSet("id1", "Morning"))
        r.setActive("id1")
        r.delete("id1")
        assertNull(r.activeId(), "deleting the active set should clear activeId")
    }

    // ---------------------------------------------------------------------------
    // reorder
    // ---------------------------------------------------------------------------

    @Test fun reorder_newOrder_persistsAndRoundtrips() {
        val r = repo()
        r.save(makeSet("a", "Alpha"))
        r.save(makeSet("b", "Beta"))
        r.save(makeSet("c", "Gamma"))
        r.reorder(listOf("c", "a", "b"))
        assertEquals(listOf("c", "a", "b"), r.getAll().map { it.id })
    }

    @Test fun reorder_idsAbsentFromOrder_areAppendedNotLost() {
        val r = repo()
        r.save(makeSet("a", "Alpha"))
        r.save(makeSet("b", "Beta"))
        r.save(makeSet("c", "Gamma"))
        r.reorder(listOf("c"))                       // only mention one id
        val ids = r.getAll().map { it.id }
        assertEquals("c", ids.first())               // requested id leads
        assertEquals(setOf("a", "b", "c"), ids.toSet()) // nothing lost
        assertEquals(3, ids.size)
    }

    @Test fun reorder_unknownIds_areIgnored() {
        val r = repo()
        r.save(makeSet("a", "Alpha"))
        r.save(makeSet("b", "Beta"))
        r.reorder(listOf("ghost", "b", "a"))
        assertEquals(listOf("b", "a"), r.getAll().map { it.id })
    }

    @Test fun reorder_preservesActiveId() {
        val r = repo()
        r.save(makeSet("a", "Alpha"))
        r.save(makeSet("b", "Beta"))
        r.setActive("a")
        r.reorder(listOf("b", "a"))
        assertEquals("a", r.activeId(), "reorder must not touch the active id")
    }

    @Test fun reorder_preservesSetContents() {
        val r = repo()
        val alpha = makeSet("a", "Alpha", durationSeconds = 200)
        r.save(alpha)
        r.save(makeSet("b", "Beta"))
        r.reorder(listOf("b", "a"))
        assertEquals(alpha, r.get("a"), "reorder must not alter set contents")
    }
}
