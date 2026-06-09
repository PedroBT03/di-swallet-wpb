package di.swallet.wpb.consent

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

@Component
class ConsentSessionGuard {

    fun requireHolderMatch(sessionHolderId: String?, requestedHolderId: String?) {
        if (sessionHolderId.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Session has no holderId; consent requires an authenticated holder context")
        }
        if (requestedHolderId.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "holderId is required")
        }
        if (sessionHolderId != requestedHolderId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "holderId does not match session owner")
        }
    }
}
