package com.blizzardcaron.freeolleefaces.vm

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal fun randomId(): String = Uuid.random().toString()
