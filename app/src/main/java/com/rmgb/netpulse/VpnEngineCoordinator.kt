package com.rmgb.netpulse

/**
 * Serializes ownership of Android's single per-user VPN slot.
 * Claims are process-local; each engine must release its claim on every stop/failure path.
 */
internal object VpnEngineCoordinator {
    enum class Engine { WIREGUARD, SING_BOX }

    @Volatile
    private var owner: Engine? = null

    @Synchronized
    fun acquire(engine: Engine): Boolean {
        val current = owner
        if (current != null && current != engine) return false
        owner = engine
        return true
    }

    @Synchronized
    fun release(engine: Engine) {
        if (owner == engine) owner = null
    }

    @Synchronized
    fun currentOwner(): Engine? = owner

    @Synchronized
    fun resetForTests() {
        owner = null
    }
}
