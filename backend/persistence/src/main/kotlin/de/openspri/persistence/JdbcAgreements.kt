package de.openspri.persistence

import de.openspri.domain.BusinessCase
import de.openspri.domain.order.OrderRole
import de.openspri.domain.partner.AgreementDocument
import de.openspri.domain.partner.FrameworkContract
import de.openspri.domain.partner.MaintenanceAnnouncement
import de.openspri.domain.partner.MaintenanceAnnouncements
import de.openspri.domain.partner.PartnerAgreements
import de.openspri.domain.partner.PartnerId
import de.openspri.domain.partner.PowerOfAttorney
import de.openspri.domain.partner.Product
import de.openspri.domain.partner.ProductCatalog
import de.openspri.domain.partner.ProductChange
import de.openspri.domain.partner.ProjectAgreement
import de.openspri.domain.partner.Validity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp

@Repository
class JdbcPartnerAgreements(private val jdbc: JdbcClient) : PartnerAgreements {

    override fun contracts(partnerId: PartnerId): List<FrameworkContract> =
        jdbc.sql("SELECT * FROM partner_contract WHERE partner_id = :p ORDER BY id").param("p", partnerId.value).query { rs, _ ->
            FrameworkContract(rs.getLong("id"), partnerId, OrderRole.valueOf(rs.getString("role")), rs.getString("client_number"),
                rs.getString("service_number"), rs.getString("description"), validity(rs))
        }.list()

    override fun saveContract(contract: FrameworkContract): FrameworkContract {
        val params = mapOf(
            "p" to contract.partnerId.value, "role" to contract.role.name, "client" to contract.clientNumber, "service" to contract.serviceNumber,
            "description" to contract.description, "from" to contract.validity.from, "to" to contract.validity.to,
        )
        return contract.copy(id = upsert(
            contract.id,
            "INSERT INTO partner_contract (partner_id, role, client_number, service_number, description, valid_from, valid_to) " +
                "VALUES (:p, :role, :client, :service, :description, :from, :to)",
            "UPDATE partner_contract SET role = :role, client_number = :client, service_number = :service, description = :description, " +
                "valid_from = :from, valid_to = :to WHERE id = :id AND partner_id = :p",
            params,
        ))
    }

    override fun deleteContract(partnerId: PartnerId, id: Long) = delete("partner_contract", partnerId, id)

    override fun powersOfAttorney(partnerId: PartnerId): List<PowerOfAttorney> =
        jdbc.sql("SELECT * FROM partner_power_of_attorney WHERE partner_id = :p ORDER BY id").param("p", partnerId.value).query { rs, _ ->
            PowerOfAttorney(rs.getLong("id"), partnerId, rs.getString("orderer_client_number"), rs.getString("orderer_service_number"),
                rs.getString("client_number"), rs.getString("service_number"), rs.getString("description"), validity(rs), rs.getString("document_name"))
        }.list()

    override fun savePowerOfAttorney(power: PowerOfAttorney): PowerOfAttorney {
        val params = mapOf(
            "p" to power.partnerId.value, "oc" to power.ordererClientNumber, "os" to power.ordererServiceNumber,
            "client" to power.clientNumber, "service" to power.serviceNumber, "description" to power.description,
            "from" to power.validity.from, "to" to power.validity.to,
        )
        val id = upsert(
            power.id,
            "INSERT INTO partner_power_of_attorney (partner_id, orderer_client_number, orderer_service_number, client_number, service_number, " +
                "description, valid_from, valid_to) VALUES (:p, :oc, :os, :client, :service, :description, :from, :to)",
            "UPDATE partner_power_of_attorney SET orderer_client_number = :oc, orderer_service_number = :os, client_number = :client, " +
                "service_number = :service, description = :description, valid_from = :from, valid_to = :to WHERE id = :id AND partner_id = :p",
            params,
        )
        return powersOfAttorney(power.partnerId).first { it.id == id }
    }

    override fun deletePowerOfAttorney(partnerId: PartnerId, id: Long) = delete("partner_power_of_attorney", partnerId, id)

    override fun document(partnerId: PartnerId, powerOfAttorneyId: Long): AgreementDocument? =
        jdbc.sql("SELECT document_name, document_type, document FROM partner_power_of_attorney WHERE id = :id AND partner_id = :p AND document IS NOT NULL")
            .param("id", powerOfAttorneyId).param("p", partnerId.value)
            .query { rs, _ -> AgreementDocument(rs.getString(1), rs.getString(2), rs.getBytes(3)) }.optional().orElse(null)

    override fun saveDocument(partnerId: PartnerId, powerOfAttorneyId: Long, document: AgreementDocument): Boolean =
        jdbc.sql("UPDATE partner_power_of_attorney SET document_name = :n, document_type = :t, document = :d WHERE id = :id AND partner_id = :p")
            .param("n", document.name).param("t", document.contentType).param("d", document.content)
            .param("id", powerOfAttorneyId).param("p", partnerId.value).update() == 1

    override fun projects(partnerId: PartnerId): List<ProjectAgreement> =
        jdbc.sql("SELECT * FROM partner_project WHERE partner_id = :p ORDER BY id").param("p", partnerId.value).query { rs, _ ->
            ProjectAgreement(rs.getLong("id"), partnerId, rs.getString("project_id"), rs.getString("description"), validity(rs))
        }.list()

    override fun saveProject(project: ProjectAgreement): ProjectAgreement {
        val params = mapOf(
            "p" to project.partnerId.value, "project" to project.projectId, "description" to project.description,
            "from" to project.validity.from, "to" to project.validity.to,
        )
        return project.copy(id = upsert(
            project.id,
            "INSERT INTO partner_project (partner_id, project_id, description, valid_from, valid_to) VALUES (:p, :project, :description, :from, :to)",
            "UPDATE partner_project SET project_id = :project, description = :description, valid_from = :from, valid_to = :to WHERE id = :id AND partner_id = :p",
            params,
        ))
    }

    override fun deleteProject(partnerId: PartnerId, id: Long) = delete("partner_project", partnerId, id)

    /** Inserts (id null) or updates; an update of an id of another partner changes nothing and is reported as not found. */
    private fun upsert(id: Long?, insert: String, update: String, params: Map<String, Any?>): Long {
        if (id == null) {
            val keys = GeneratedKeyHolder()
            jdbc.sql(insert).params(params).update(keys, "id")
            return keys.key!!.toLong()
        }
        if (jdbc.sql(update).params(params + ("id" to id)).update() != 1) throw NoSuchElementException("Eintrag $id existiert nicht")
        return id
    }

    private fun delete(table: String, partnerId: PartnerId, id: Long) =
        jdbc.sql("DELETE FROM $table WHERE id = :id AND partner_id = :p").param("id", id).param("p", partnerId.value).update() == 1

    private fun validity(rs: ResultSet) = Validity(rs.getDate("valid_from")?.toLocalDate(), rs.getDate("valid_to")?.toLocalDate())
}

@Repository
class JdbcProductCatalog(private val jdbc: JdbcClient) : ProductCatalog {

    override fun list(partnerId: PartnerId?, role: OrderRole?): List<Product> {
        val where = mutableListOf("tenant_id = :tenant")
        val params = mutableMapOf<String, Any?>("tenant" to Tenant.DEFAULT)
        // the products of a partner include those for all partners
        partnerId?.let { where += "(partner_id = :p OR partner_id IS NULL)"; params["p"] = it.value }
        role?.let { where += "role = :role"; params["role"] = it.name }
        return jdbc.sql("SELECT * FROM product WHERE ${where.joinToString(" AND ")} ORDER BY identifier, id").params(params).query { rs, _ -> map(rs) }.list()
    }

    override fun get(id: Long): Product? =
        jdbc.sql("SELECT * FROM product WHERE id = :id").param("id", id).query { rs, _ -> map(rs) }.optional().orElse(null)

    override fun save(product: Product): Product {
        val params = mapOf(
            "tenant" to Tenant.DEFAULT, "p" to product.partnerId?.value, "role" to product.role.name, "identifier" to product.identifier,
            "group" to product.productGroup, "description" to product.description, "active" to product.active,
        )
        if (product.id == null) {
            val keys = GeneratedKeyHolder()
            jdbc.sql(
                "INSERT INTO product (tenant_id, partner_id, role, identifier, product_group, description, active) " +
                    "VALUES (:tenant, :p, :role, :identifier, :group, :description, :active)",
            ).params(params).update(keys, "id")
            return product.copy(id = keys.key!!.toLong())
        }
        val updated = jdbc.sql(
            "UPDATE product SET partner_id = :p, role = :role, identifier = :identifier, product_group = :group, description = :description, " +
                "active = :active WHERE id = :id",
        ).params(params + ("id" to product.id)).update()
        if (updated != 1) throw NoSuchElementException("Produkt ${product.id} existiert nicht")
        return product
    }

    override fun delete(id: Long) = jdbc.sql("DELETE FROM product WHERE id = :id").param("id", id).update() == 1

    override fun changes(partnerId: PartnerId?): List<ProductChange> =
        jdbc.sql("SELECT * FROM product_change WHERE tenant_id = :tenant AND partner_id <=> :p ORDER BY from_product, to_product")
            .param("tenant", Tenant.DEFAULT).param("p", partnerId?.value)
            .query { rs, _ ->
                ProductChange(partnerId, rs.getString("from_product"), rs.getString("to_product"), BusinessCase.fromCode(rs.getString("business_case")))
            }.list()

    @Transactional
    override fun replaceChanges(partnerId: PartnerId?, changes: List<ProductChange>) {
        jdbc.sql("DELETE FROM product_change WHERE tenant_id = :tenant AND partner_id <=> :p").param("tenant", Tenant.DEFAULT).param("p", partnerId?.value).update()
        changes.forEach { change ->
            jdbc.sql("INSERT INTO product_change (tenant_id, partner_id, from_product, to_product, business_case) VALUES (:tenant, :p, :from, :to, :bc)")
                .param("tenant", Tenant.DEFAULT).param("p", partnerId?.value).param("from", change.from).param("to", change.to)
                .param("bc", change.businessCase.code).update()
        }
    }

    private fun map(rs: ResultSet) = Product(
        rs.getLong("id"), rs.getLong("partner_id").takeIf { !rs.wasNull() }?.let(::PartnerId), OrderRole.valueOf(rs.getString("role")),
        rs.getString("identifier"), rs.getString("product_group"), rs.getString("description"), rs.getBoolean("active"),
    )
}

@Repository
class JdbcMaintenanceAnnouncements(private val jdbc: JdbcClient) : MaintenanceAnnouncements {

    override fun save(announcement: MaintenanceAnnouncement): MaintenanceAnnouncement {
        val keys = GeneratedKeyHolder()
        jdbc.sql(
            """INSERT INTO maintenance_announcement (tenant_id, starts_at, ends_at, subject, text, partner_ids, recipients, warnings, created_at, created_by)
               VALUES (:tenant, :start, :end, :subject, :text, :partners, :recipients, :warnings, :created, :by)""",
        ).param("tenant", Tenant.DEFAULT).param("start", Timestamp.from(announcement.start)).param("end", Timestamp.from(announcement.end))
            .param("subject", announcement.subject).param("text", announcement.text)
            .param("partners", announcement.partnerIds.joinToString(",") { it.value.toString() }.take(2000))
            .param("recipients", announcement.recipients).param("warnings", announcement.warnings.joinToString("\n").take(4000).ifEmpty { null })
            .param("created", Timestamp.from(announcement.createdAt)).param("by", announcement.createdBy)
            .update(keys, "id")
        return announcement.copy(id = keys.key!!.toLong())
    }

    override fun list(limit: Int): List<MaintenanceAnnouncement> =
        jdbc.sql("SELECT * FROM maintenance_announcement WHERE tenant_id = :tenant ORDER BY id DESC LIMIT :limit")
            .param("tenant", Tenant.DEFAULT).param("limit", limit.coerceIn(1, 500))
            .query { rs, _ ->
                MaintenanceAnnouncement(
                    rs.getLong("id"), rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
                    rs.getString("subject"), rs.getString("text"),
                    rs.getString("partner_ids").split(',').filter { it.isNotBlank() }.map { PartnerId(it.toLong()) },
                    rs.getTimestamp("created_at").toInstant(), rs.getString("created_by"), rs.getInt("recipients"),
                    rs.getString("warnings")?.split('\n').orEmpty(),
                )
            }.list()
}
