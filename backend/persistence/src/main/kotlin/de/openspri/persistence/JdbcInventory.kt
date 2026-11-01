package de.openspri.persistence

import de.openspri.domain.order.InventoryContract
import de.openspri.domain.order.InventoryFilter
import de.openspri.domain.order.InventoryStore
import de.openspri.domain.order.OrderId
import de.openspri.domain.order.OrderRole
import de.openspri.domain.partner.PartnerId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Repository
class JdbcInventory(private val jdbc: JdbcClient) : InventoryStore {

    override fun add(contract: InventoryContract): InventoryContract {
        val keys = GeneratedKeyHolder()
        jdbc.sql(
            """INSERT INTO inventory_contract (tenant_id, role, partner_id, contract_number, line_id, client_number, service_number, products, order_id, started_at)
               VALUES (:tenant, :role, :partner, :contract, :line, :client, :service, :products, :order, :started)""",
        ).param("tenant", Tenant.DEFAULT).param("role", contract.role.name).param("partner", contract.partnerId.value)
            .param("contract", contract.contractNumber).param("line", contract.lineId).param("client", contract.clientNumber)
            .param("service", contract.serviceNumber).param("products", contract.products.joinToString("|").take(1000).ifEmpty { null })
            .param("order", contract.orderId?.value).param("started", Timestamp.from(contract.startedAt))
            .update(keys, "id")
        return contract.copy(id = keys.key!!.toLong())
    }

    override fun end(role: OrderRole, lineId: String?, contractNumber: String?, at: Instant): Int {
        if (lineId == null && contractNumber == null) return 0
        val where = mutableListOf("tenant_id = :tenant", "role = :role", "ended_at IS NULL")
        lineId?.let { where += "line_id = :line" }
        contractNumber?.let { where += "contract_number = :contract" }
        return jdbc.sql("UPDATE inventory_contract SET ended_at = :at WHERE ${where.joinToString(" AND ")}")
            .param("tenant", Tenant.DEFAULT).param("role", role.name).param("at", Timestamp.from(at))
            .apply { lineId?.let { param("line", it) }; contractNumber?.let { param("contract", it) } }
            .update()
    }

    override fun active(role: OrderRole, lineId: String): InventoryContract? =
        jdbc.sql("SELECT * FROM inventory_contract WHERE tenant_id = :tenant AND role = :role AND line_id = :line AND ended_at IS NULL ORDER BY id DESC LIMIT 1")
            .param("tenant", Tenant.DEFAULT).param("role", role.name).param("line", lineId)
            .query { rs, _ -> map(rs) }.optional().orElse(null)

    override fun search(filter: InventoryFilter): List<InventoryContract> {
        val where = mutableListOf("tenant_id = :tenant")
        val params = mutableMapOf<String, Any?>("tenant" to Tenant.DEFAULT, "limit" to filter.limit.coerceIn(1, 500))
        filter.role?.let { where += "role = :role"; params["role"] = it.name }
        filter.partnerId?.let { where += "partner_id = :partner"; params["partner"] = it.value }
        filter.lineId?.let { where += "line_id = :line"; params["line"] = it }
        filter.beforeId?.let { where += "id < :before"; params["before"] = it }
        if (filter.activeOnly) where += "ended_at IS NULL"
        return jdbc.sql("SELECT * FROM inventory_contract WHERE ${where.joinToString(" AND ")} ORDER BY id DESC LIMIT :limit")
            .params(params).query { rs, _ -> map(rs) }.list()
    }

    private fun map(rs: ResultSet) = InventoryContract(
        id = rs.getLong("id"),
        role = OrderRole.valueOf(rs.getString("role")),
        partnerId = PartnerId(rs.getLong("partner_id")),
        contractNumber = rs.getString("contract_number"),
        lineId = rs.getString("line_id"),
        clientNumber = rs.getString("client_number"),
        serviceNumber = rs.getString("service_number"),
        products = rs.getString("products")?.split('|').orEmpty(),
        orderId = rs.getLong("order_id").takeIf { !rs.wasNull() }?.let(::OrderId),
        startedAt = rs.getTimestamp("started_at").toInstant(),
        endedAt = rs.getTimestamp("ended_at")?.toInstant(),
    )
}
