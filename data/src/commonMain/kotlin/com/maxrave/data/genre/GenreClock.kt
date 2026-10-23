package com.maxrave.data.genre

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
internal fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
