package app.pixlink.companion

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import app.pixlink.link.LinkService

/**
 * Android binds this while the PC is nearby (quiet mode), which keeps Pixlink running without
 * a notification. It just makes sure the connection service is up.
 */
class PixlinkCompanionService : CompanionDeviceService() {
    @Deprecated("Deprecated in Android 16, still called on 13–15")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        runCatching { LinkService.start(this) }
    }

    @Deprecated("Deprecated in Android 16, still called on 13–15")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {}
}
