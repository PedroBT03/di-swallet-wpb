/**
 * JPA entity linking credentials to attested holder keys and binding format.
 */

package di.swallet.wpb.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import java.time.Instant

/**
 * One-to-one binding between a stored credential and the attested key used for holder proofs.
 */
@Entity
@Table(name = "credential_key_bindings")
class CredentialKeyBinding(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credential_id", nullable = false, unique = true)
    val credential: WalletCredential,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attested_key_id", nullable = false)
    val attestedKey: AttestedKeyRecord,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val bindingFormat: CredentialBindingFormat,

    @Column(nullable = false)
    val boundAt: Instant = Instant.now(),
)

/** Credential presentation format supported by a holder-key binding. */
enum class CredentialBindingFormat {
    SD_JWT,
    MDOC,
}
