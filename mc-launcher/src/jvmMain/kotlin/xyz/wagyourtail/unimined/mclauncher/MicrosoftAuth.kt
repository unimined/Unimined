package xyz.wagyourtail.unimined.mclauncher

import net.raphimc.minecraftauth.MinecraftAuth
import net.raphimc.minecraftauth.step.java.session.StepFullJavaSession.FullJavaSession
import net.raphimc.minecraftauth.step.msa.StepMsaDeviceCode
import java.nio.file.Path

/**
 * Microsoft account auth via `net.raphimc:MinecraftAuth`'s device-code
 * chain (MSA → Xbox Live → XSTS → Minecraft services).
 *
 * - `session()` silently refreshes the stored credential for
 *   [username] (or the sole stored user when [username] is null).
 * - With [interactive] true and no usable stored credential, the
 *   device-code flow runs once and the result is persisted — this is
 *   the `:loginMicrosoft` path, not a mid-launch fallback.
 * - Headless/CI: point [store] at a file provisioned by
 *   `:loginMicrosoft` on a machine with a browser, or inject a
 *   `custom` provider.
 */
class MicrosoftAuth(
    private val store: SessionStore,
    private val username: String? = null,
    private val interactive: Boolean = false,
    /** Persist refreshed sessions back to the store — `false` makes
     *  runs read+refresh in memory only (`unimined.auth.storeCredentials=false`).
     *  [login] always persists: writing the credential is its purpose. */
    private val storeOnRefresh: Boolean = true,
    @Transient private val onDeviceCode: (StepMsaDeviceCode.MsaDeviceCode) -> Unit = {
        println(
            "[Unimined/Auth] Microsoft login: visit ${it.directVerificationUri}" +
                " (or ${it.verificationUri} and enter ${it.userCode})"
        )
    }
) : AuthProvider {

    constructor(
        storePath: Path,
        username: String? = null,
        interactive: Boolean = false
    ) : this(CredentialStore(storePath), username, interactive)

    // built per call — HttpClient isn't Serializable and only lives
    // for the duration of session()
    private val client get() = MinecraftAuth.createHttpClient()

    private fun FullJavaSession.toSession(): Session = Session(
        username = mcProfile.name,
        uuid = mcProfile.id.toString().replace("-", ""),
        accessToken = mcProfile.mcToken.accessToken,
        userType = "msa"
    )

    private fun persist(session: FullJavaSession): FullJavaSession {
        store.put(session.mcProfile.name,
            MinecraftAuth.JAVA_DEVICE_CODE_LOGIN.toJson(session))
        return session
    }

    private fun login(): FullJavaSession = persist(
        MinecraftAuth.JAVA_DEVICE_CODE_LOGIN.getFromInput(
            client,
            StepMsaDeviceCode.MsaDeviceCodeCallback(onDeviceCode)
        )
    )

    private fun stored(): FullJavaSession? {
        // username selects the stored account; when nothing matches
        // (e.g. the offline-name default) fall back to the sole user
        val json = username?.let { store.get(it) }
            ?: store.users().singleOrNull()?.let { store.get(it) }
        return json?.let {
            runCatching {
                val refreshed = MinecraftAuth.JAVA_DEVICE_CODE_LOGIN.refresh(
                    client,
                    MinecraftAuth.JAVA_DEVICE_CODE_LOGIN.fromJson(it)
                )
                if (storeOnRefresh) persist(refreshed) else refreshed
            }.getOrNull()
        }
    }

    override fun session(): Session {
        stored()?.let { return it.toSession() }
        if (interactive) return login().toSession()
        throw IllegalStateException(
            "No stored Microsoft credentials" +
                (username?.let { " for '$it'" } ?: "") +
                " — run :loginMicrosoft on a machine with a browser," +
                " then retry"
        )
    }

}
