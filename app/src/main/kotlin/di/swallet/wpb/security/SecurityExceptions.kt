/**
 * Security-related HTTP exceptions for wallet authorization failures.
 */

package di.swallet.wpb.security

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * Exception thrown when a mandatory security header is missing or invalid.
 */
class UnauthorizedWalletException(message: String) : 
    ResponseStatusException(HttpStatus.UNAUTHORIZED, message)
