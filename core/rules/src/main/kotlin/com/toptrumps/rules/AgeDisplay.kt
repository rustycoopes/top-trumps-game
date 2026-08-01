package com.toptrumps.rules

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * "Age — N years" from a stored model year, computed at the UI layer from an injected [Clock] —
 * the engine itself never sees a derived value or a clock, per TDD §7. Deliberately just a
 * calendar-year subtraction: precise to the day is not the point, and the raw stored [year] is
 * what every comparison actually runs on.
 */
public fun yearsSince(year: Int, clock: Clock, timeZone: TimeZone = TimeZone.currentSystemDefault()): Int =
    clock.now().toLocalDateTime(timeZone).year - year
