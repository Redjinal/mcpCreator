package bb.produce.invoicing.session

import bb.produce.invoicing.core.data.DemoData
import bb.produce.invoicing.domain.common.Actor
import bb.produce.invoicing.domain.common.BusinessId
import bb.produce.invoicing.domain.common.DeviceId
import bb.produce.invoicing.domain.common.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The signed-in user on this device. Server-backed authentication arrives in Task 04. */
data class Session(val actor: Actor, val businessId: BusinessId, val displayName: String, val driverCode: String?) {
    val isOwner: Boolean get() = actor.role == Role.OWNER
}

data class SignInOption(val label: String, val detail: String, val session: Session)

/** Authentication seam. Screens depend on this interface only. */
interface AuthGateway {
    /** Null when sign-in is available; otherwise an honest reason shown to the user. */
    val unavailableReason: String?
    fun options(device: DeviceId): List<SignInOption>
}

/**
 * DEVELOPER BUILDS ONLY: lets a tester act as the synthetic demo driver or owner.
 * No password, no server check — this is NOT authentication and is compiled out of pilot behaviour
 * (pilot uses [NotProvisionedAuthGateway]).
 */
class DemoAuthGateway : AuthGateway {
    override val unavailableReason: String? = null
    override fun options(device: DeviceId) = listOf(
        SignInOption("Demo Driver (synthetic)", "Driver code AB · issues invoices on this device",
            Session(Actor(DemoData.driverUser, Role.DRIVER, device), DemoData.businessId, "Demo Driver (synthetic)", "AB")),
        SignInOption("Demo Owner (synthetic)", "Owner view · all drivers' invoices on this device, voids and corrections",
            Session(Actor(DemoData.ownerUser, Role.OWNER, device), DemoData.businessId, "Demo Owner (synthetic)", null)),
    )
}

class NotProvisionedAuthGateway : AuthGateway {
    override val unavailableReason =
        "This device is not provisioned. Device enrollment and sign-in are not implemented yet (Task 04), " +
            "so invoicing cannot be used in a pilot build."
    override fun options(device: DeviceId) = emptyList<SignInOption>()
}

class SessionStore {
    private val state = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = state.asStateFlow()
    fun signIn(s: Session) { state.value = s }
    fun signOut() { state.value = null }
}
