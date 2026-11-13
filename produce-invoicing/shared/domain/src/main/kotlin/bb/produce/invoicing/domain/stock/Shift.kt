package bb.produce.invoicing.domain.stock

import bb.produce.invoicing.domain.common.Actor
import bb.produce.invoicing.domain.common.Outcome
import bb.produce.invoicing.domain.common.Role

/** Shift/day lifecycle (docs/09). */
enum class ShiftStatus { PLANNED, LOADED, OPEN, CLOSING_PENDING_SYNC, RECONCILED, APPROVED, REOPENED_WITH_REASON }

enum class ShiftError { INVALID_TRANSITION, NOT_AUTHORIZED, REASON_REQUIRED, UNSYNCED_EVENTS_REMAIN, UNRESOLVED_VARIANCE }

/** Facts the server supplies when an owner tries to approve a close (Task 12 computes them). */
data class CloseReadiness(val unsyncedEvents: Int, val openConflicts: Int, val unexplainedVariances: Int)

object ShiftLifecycle {
    private data class Rule(val to: ShiftStatus, val roles: Set<Role>)

    private val RULES: Map<ShiftStatus, List<Rule>> = mapOf(
        ShiftStatus.PLANNED to listOf(Rule(ShiftStatus.LOADED, setOf(Role.OWNER))),
        ShiftStatus.LOADED to listOf(Rule(ShiftStatus.OPEN, setOf(Role.DRIVER, Role.OWNER))),
        // A driver can save a provisional close offline.
        ShiftStatus.OPEN to listOf(Rule(ShiftStatus.CLOSING_PENDING_SYNC, setOf(Role.DRIVER, Role.OWNER))),
        ShiftStatus.CLOSING_PENDING_SYNC to listOf(Rule(ShiftStatus.RECONCILED, setOf(Role.OWNER))),
        ShiftStatus.RECONCILED to listOf(
            Rule(ShiftStatus.APPROVED, setOf(Role.OWNER)),
            Rule(ShiftStatus.REOPENED_WITH_REASON, setOf(Role.OWNER)),
        ),
        ShiftStatus.APPROVED to listOf(Rule(ShiftStatus.REOPENED_WITH_REASON, setOf(Role.OWNER))),
        ShiftStatus.REOPENED_WITH_REASON to listOf(Rule(ShiftStatus.CLOSING_PENDING_SYNC, setOf(Role.DRIVER, Role.OWNER))),
    )

    fun transition(
        from: ShiftStatus,
        to: ShiftStatus,
        actor: Actor,
        reason: String? = null,
        readiness: CloseReadiness? = null,
    ): Outcome<ShiftStatus, ShiftError> {
        val rule = RULES[from].orEmpty().firstOrNull { it.to == to } ?: return Outcome.Err(ShiftError.INVALID_TRANSITION)
        if (actor.role !in rule.roles) return Outcome.Err(ShiftError.NOT_AUTHORIZED)
        if (to == ShiftStatus.REOPENED_WITH_REASON && reason.isNullOrBlank()) return Outcome.Err(ShiftError.REASON_REQUIRED)
        if (to == ShiftStatus.APPROVED || to == ShiftStatus.RECONCILED) {
            val r = readiness ?: return Outcome.Err(ShiftError.UNSYNCED_EVENTS_REMAIN)
            if (r.unsyncedEvents > 0 || r.openConflicts > 0) return Outcome.Err(ShiftError.UNSYNCED_EVENTS_REMAIN)
            if (to == ShiftStatus.APPROVED && r.unexplainedVariances > 0) return Outcome.Err(ShiftError.UNRESOLVED_VARIANCE)
        }
        return Outcome.Ok(to)
    }
}
