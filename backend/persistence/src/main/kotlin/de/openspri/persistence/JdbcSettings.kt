package de.openspri.persistence

import de.openspri.domain.settings.CompanySettings
import de.openspri.domain.settings.SettingsRepository
import de.openspri.domain.settings.SigningKeyRepository
import de.openspri.domain.settings.StoredSigningKey
import de.openspri.domain.settings.WbciMode
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant

@Repository
class JdbcSettings(private val jdbc: JdbcClient) : SettingsRepository {

    override fun load(): CompanySettings? =
        jdbc.sql("SELECT * FROM company_settings WHERE tenant_id = :t").param("t", Tenant.DEFAULT).query { rs, _ ->
            CompanySettings(
                name = rs.getString("name"), street = rs.getString("street"), postalCode = rs.getString("postal_code"), city = rs.getString("city"),
                carrierCode = rs.getString("carrier_code"), contactEmail = rs.getString("contact_email"),
                roleAuftraggeber = rs.getBoolean("role_auftraggeber"), roleLeistungserbringer = rs.getBoolean("role_leistungserbringer"),
                estEnabled = rs.getBoolean("est_enabled"), diagEnabled = rs.getBoolean("diag_enabled"),
                connectivityEnabled = rs.getBoolean("connectivity_enabled"), thirdPartyOrdersEnabled = rs.getBoolean("third_party_orders_enabled"),
                wbciMode = WbciMode.valueOf(rs.getString("wbci_mode")), publicSpriEndpoint = rs.getString("public_spri_endpoint"),
                outboundAddresses = rs.getString("outbound_addresses")?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            )
        }.optional().orElse(null)

    override fun save(settings: CompanySettings) {
        jdbc.sql(
            """INSERT INTO company_settings (tenant_id, name, street, postal_code, city, carrier_code, contact_email, role_auftraggeber,
                   role_leistungserbringer, est_enabled, diag_enabled, connectivity_enabled, third_party_orders_enabled, wbci_mode,
                   public_spri_endpoint, outbound_addresses, updated_at)
               VALUES (:t, :name, :street, :zip, :city, :icc, :mail, :ag, :le, :est, :diag, :con, :third, :wbci, :url, :out, :now)
               ON DUPLICATE KEY UPDATE name = VALUES(name), street = VALUES(street), postal_code = VALUES(postal_code), city = VALUES(city),
                   carrier_code = VALUES(carrier_code), contact_email = VALUES(contact_email), role_auftraggeber = VALUES(role_auftraggeber),
                   role_leistungserbringer = VALUES(role_leistungserbringer), est_enabled = VALUES(est_enabled), diag_enabled = VALUES(diag_enabled),
                   connectivity_enabled = VALUES(connectivity_enabled), third_party_orders_enabled = VALUES(third_party_orders_enabled),
                   wbci_mode = VALUES(wbci_mode), public_spri_endpoint = VALUES(public_spri_endpoint),
                   outbound_addresses = VALUES(outbound_addresses), updated_at = VALUES(updated_at)""",
        ).param("t", Tenant.DEFAULT).param("name", settings.name).param("street", settings.street).param("zip", settings.postalCode)
            .param("city", settings.city).param("icc", settings.carrierCode).param("mail", settings.contactEmail)
            .param("ag", settings.roleAuftraggeber).param("le", settings.roleLeistungserbringer).param("est", settings.estEnabled)
            .param("diag", settings.diagEnabled).param("con", settings.connectivityEnabled).param("third", settings.thirdPartyOrdersEnabled)
            .param("wbci", settings.wbciMode.name).param("url", settings.publicSpriEndpoint)
            .param("out", settings.outboundAddresses.joinToString(",").ifEmpty { null }).param("now", Timestamp.from(Instant.now()))
            .update()
    }

    override fun setupCompleted(): Boolean =
        jdbc.sql("SELECT completed FROM setup_state WHERE tenant_id = :t").param("t", Tenant.DEFAULT).query(Boolean::class.java).single()

    override fun markSetupCompleted(at: Instant) {
        jdbc.sql("UPDATE setup_state SET completed = TRUE, completed_at = :at WHERE tenant_id = :t").param("at", Timestamp.from(at)).param("t", Tenant.DEFAULT).update()
    }
}

@Repository
class JdbcSigningKeys(private val jdbc: JdbcClient) : SigningKeyRepository {

    override fun active(): StoredSigningKey? =
        jdbc.sql("SELECT * FROM signing_key WHERE tenant_id = :t AND active").param("t", Tenant.DEFAULT).query { rs, _ ->
            StoredSigningKey(
                rs.getLong("id"), rs.getBytes("encrypted_pkcs12"), rs.getString("subject"), rs.getString("serial_number"),
                rs.getTimestamp("not_after").toInstant(), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
            )
        }.optional().orElse(null)

    @Transactional
    override fun activate(key: StoredSigningKey): StoredSigningKey {
        jdbc.sql("UPDATE signing_key SET active = FALSE WHERE tenant_id = :t").param("t", Tenant.DEFAULT).update()
        val keys = org.springframework.jdbc.support.GeneratedKeyHolder()
        jdbc.sql(
            """INSERT INTO signing_key (tenant_id, encrypted_pkcs12, subject, serial_number, not_after, active, created_by, created_at)
               VALUES (:t, :k, :s, :n, :na, TRUE, :cb, :c)""",
        ).param("t", Tenant.DEFAULT).param("k", key.encryptedPkcs12).param("s", key.subject).param("n", key.serialNumber)
            .param("na", Timestamp.from(key.notAfter)).param("cb", key.createdBy).param("c", Timestamp.from(key.createdAt)).update(keys, "id")
        return key.copy(id = keys.key!!.toLong())
    }
}
