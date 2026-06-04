package com.blizzardcaron.freeolleefaces.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationCountTest {

    private fun n(
        pkg: String = "com.example.app",
        clearable: Boolean = true,
        ongoing: Boolean = false,
        groupSummary: Boolean = false,
    ) = NotificationCount.ActiveNotification(pkg, clearable, ongoing, groupSummary)

    @Test fun countsOrdinaryNotifications() {
        val list = listOf(n(), n(pkg = "com.other"))
        assertEquals(2, NotificationCount.countFrom(list, ownPackage = "com.blizzardcaron.freeolleefaces"))
    }

    @Test fun excludesOngoing() {
        assertEquals(0, NotificationCount.countFrom(listOf(n(ongoing = true)), "com.me"))
    }

    @Test fun excludesNonClearable() {
        assertEquals(0, NotificationCount.countFrom(listOf(n(clearable = false)), "com.me"))
    }

    @Test fun excludesGroupSummary() {
        // A bundled app posts a summary + its children; only the children count.
        val list = listOf(n(groupSummary = true), n(), n())
        assertEquals(2, NotificationCount.countFrom(list, "com.me"))
    }

    @Test fun excludesOwnPackage() {
        val list = listOf(n(pkg = "com.blizzardcaron.freeolleefaces"), n(pkg = "com.other"))
        assertEquals(1, NotificationCount.countFrom(list, ownPackage = "com.blizzardcaron.freeolleefaces"))
    }

    @Test fun multipleFromOneAppEachCount() {
        val list = listOf(n(pkg = "com.chat"), n(pkg = "com.chat"), n(pkg = "com.chat"))
        assertEquals(3, NotificationCount.countFrom(list, "com.me"))
    }

    @Test fun formatZeroIsNull() {
        assertNull(NotificationCount.format(0))
    }

    @Test fun formatSingleDigitIsZeroPadded() {
        assertEquals("01", NotificationCount.format(1))
        assertEquals("09", NotificationCount.format(9))
    }

    @Test fun formatTwoDigits() {
        assertEquals("10", NotificationCount.format(10))
        assertEquals("99", NotificationCount.format(99))
    }

    @Test fun formatCapsAtNinetyNine() {
        assertEquals("99", NotificationCount.format(100))
        assertEquals("99", NotificationCount.format(4321))
    }
}
