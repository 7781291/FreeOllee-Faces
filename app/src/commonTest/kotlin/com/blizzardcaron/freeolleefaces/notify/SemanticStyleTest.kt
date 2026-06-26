package com.blizzardcaron.freeolleefaces.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticStyleTest {

    @Test fun hardFailuresAreDanger() {
        assertEquals(SemanticStyle.DANGER, FailureKind.WATCH_UNREACHABLE.semanticStyle())
        assertEquals(SemanticStyle.DANGER, FailureKind.ALARM_UNREACHABLE.semanticStyle())
    }

    @Test fun softFailuresAreCaution() {
        assertEquals(SemanticStyle.CAUTION, FailureKind.WEATHER_FETCH_FAILED.semanticStyle())
        assertEquals(SemanticStyle.CAUTION, FailureKind.SETUP_INCOMPLETE.semanticStyle())
        assertEquals(SemanticStyle.CAUTION, FailureKind.SUN_UNREACHABLE.semanticStyle())
        assertEquals(SemanticStyle.CAUTION, FailureKind.HEALTH_UNAVAILABLE.semanticStyle())
    }

    @Test fun everyFailureKindMapsToDangerOrCaution() {
        FailureKind.entries.forEach {
            val style = it.semanticStyle()
            assertTrue(style == SemanticStyle.DANGER || style == SemanticStyle.CAUTION)
        }
    }
}
