package com.swordfish.lemuroid.lib.saves

data class SaveInfo(
    val exists: Boolean,
    val date: Long,
    /**
     * Defaults to zero for callers describing something which has no file behind it, such as a
     * timestamp standing in for one during a migration.
     */
    val size: Long = 0,
)
