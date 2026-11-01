package de.openspri.persistence

import de.openspri.domain.BusinessCase
import de.openspri.domain.ChangeIndicator
import de.openspri.domain.order.HandoverPhase
import de.openspri.domain.order.Order
import de.openspri.domain.order.OrderData
import de.openspri.domain.order.OrderDrafts
import de.openspri.domain.order.OrderHistoryEntry
import de.openspri.domain.order.OrderId
import de.openspri.domain.order.OrderRepository
import de.openspri.domain.order.OrderRole
import de.openspri.domain.order.OrderState
import de.openspri.domain.order.Phase
import de.openspri.domain.order.RequestKind
import de.openspri.domain.partner.PartnerId
import de.openspri.domain.exchange.Direction
import de.openspri.domain.message.MessageType
import de.openspri.domain.query.OrderFilter
import de.openspri.domain.query.OrderQueries
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp

@Repository
class JdbcOrderDrafts(private val jdbc: JdbcClient) : OrderDrafts {
    override fun put(orderId: OrderId, content: ByteArray) {
        jdbc.sql(
            """INSERT INTO spri_order_draft (order_id, content, updated_at) VALUES (:id, :content, NOW(3))
               ON DUPLICATE KEY UPDATE content = VALUES(content), updated_at = VALUES(updated_at)""",
        ).param("id", orderId.value).param("content", content).update()
    }

    override fun get(orderId: OrderId): ByteArray? =
        jdbc.sql("SELECT content FROM spri_order_draft WHERE order_id = :id").param("id", orderId.value)
            .query { rs, _ -> rs.getBytes(1) }.optional().orElse(null)

    override fun remove(orderId: OrderId) {
        jdbc.sql("DELETE FROM spri_order_draft WHERE order_id = :id").param("id", orderId.value).update()
    }
}

@Repository
class JdbcOrderRepository(private val jdbc: JdbcClient) : OrderRepository, OrderQueries {

    override fun find(partnerId: PartnerId, role: OrderRole, externalOrderNumber: String): Order? =
        jdbc.sql("SELECT * FROM spri_order WHERE partner_id = :p AND role = :r AND external_order_number = :n")
            .param("p", partnerId.value).param("r", role.name).param("n", externalOrderNumber)
            .query { rs, _ -> map(rs) }.optional().orElse(null)

    override fun findAnyPartner(role: OrderRole, externalOrderNumber: String, businessCase: BusinessCase): List<Order> =
        jdbc.sql("SELECT * FROM spri_order WHERE role = :r AND external_order_number = :n AND business_case = :b")
            .param("r", role.name).param("n", externalOrderNumber).param("b", businessCase.code)
            .query { rs, _ -> map(rs) }.list()

    override fun hasOpenOrder(role: OrderRole, infrastructureKey: String, except: OrderId?): Boolean =
        jdbc.sql(
            "SELECT COUNT(*) FROM spri_order WHERE role = :r AND infrastructure_key = :k AND NOT finished AND id <> :except",
        ).param("r", role.name).param("k", infrastructureKey).param("except", except?.value ?: -1L)
            .query(Long::class.java).single() > 0

    fun findById(id: OrderId): Order? =
        jdbc.sql("SELECT * FROM spri_order WHERE id = :id").param("id", id.value).query { rs, _ -> map(rs) }.optional().orElse(null)

    override fun get(id: OrderId): Order? = findById(id)

    override fun search(filter: OrderFilter): List<Order> {
        val where = mutableListOf("tenant_id = :tenant")
        val params = mutableMapOf<String, Any?>("tenant" to Tenant.DEFAULT, "limit" to filter.limit.coerceIn(1, 500))
        filter.role?.let { where += "role = :role"; params["role"] = it.name }
        filter.partnerId?.let { where += "partner_id = :partner"; params["partner"] = it.value }
        filter.businessCase?.let { where += "business_case = :bc"; params["bc"] = it.code }
        filter.phase?.let { where += "phase = :phase"; params["phase"] = it.name }
        filter.handover?.let { where += "handover_phase = :handover"; params["handover"] = it.name }
        filter.beforeId?.let { where += "id < :before"; params["before"] = it }
        if (filter.openOnly) where += "NOT finished"
        filter.text?.takeIf { it.isNotBlank() }?.let {
            where += "(external_order_number LIKE :text OR line_id LIKE :text OR contract_number LIKE :text)"
            params["text"] = it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        return jdbc.sql("SELECT * FROM spri_order WHERE ${where.joinToString(" AND ")} ORDER BY id DESC LIMIT :limit")
            .params(params).query { rs, _ -> map(rs) }.list()
    }

    override fun history(id: OrderId): List<OrderHistoryEntry> =
        jdbc.sql("SELECT * FROM spri_order_history WHERE order_id = :id ORDER BY id").param("id", id.value).query { rs, _ ->
            OrderHistoryEntry(
                orderId = id,
                partnerId = PartnerId(rs.getLong("partner_id")),
                archiveId = rs.getLong("archive_id").takeIf { !rs.wasNull() },
                kind = OrderHistoryEntry.Kind.valueOf(rs.getString("kind")),
                messageType = rs.getString("message_type")?.let(MessageType::fromCode),
                changeIndicator = ChangeIndicator.valueOf(rs.getString("change_indicator")),
                codes = rs.getString("codes")?.split(',').orEmpty(),
                findings = rs.getString("findings")?.split('\n').orEmpty(),
                at = rs.getTimestamp("created_at").toInstant(),
                direction = Direction.valueOf(rs.getString("direction")),
            )
        }.list()

    override fun save(order: Order): Order {
        val s = order.state
        val d = order.data
        val params = mapOf(
            "tenant" to Tenant.DEFAULT, "partner" to order.partnerId.value, "role" to order.role.name,
            "number" to d.externalOrderNumber, "bc" to d.businessCase.code,
            "phase" to s.phase.name, "requests" to s.openRequests.joinToString(",") { it.name }, "cancelled" to s.cancelled,
            "complaints" to s.complaints, "appointment" to s.appointmentRequested, "connectivity" to s.connectivity,
            "bracketed" to s.bracketed, "handover" to s.handover?.name, "handoverOnly" to s.handoverOnly, "finished" to s.finished,
            "client" to d.clientNumber, "service" to d.serviceNumber, "ordererClient" to d.ordererClientNumber,
            "ordererService" to d.ordererServiceNumber, "desired" to d.desiredDate, "contract" to d.contractNumber,
            "line" to d.lineId, "preAgreement" to d.preAgreementId, "home" to d.homeId,
            "products" to d.products.joinToString("|").take(1000).ifEmpty { null },
            "project" to d.projectId, "coupling" to d.couplingId, "bracket" to d.bracketId, "bracketSize" to d.bracketSize,
            "ont" to d.ontSerial, "infra" to d.infrastructureKey?.take(300), "seqKey" to order.sequenceKey,
            "created" to Timestamp.from(order.createdAt), "updated" to Timestamp.from(order.updatedAt),
        )
        if (order.id == null) {
            val keys = GeneratedKeyHolder()
            jdbc.sql(
                """INSERT INTO spri_order (tenant_id, partner_id, role, external_order_number, business_case, phase, open_requests,
                       cancelled, complaints, appointment_requested, connectivity, bracketed, handover_phase, handover_only, finished,
                       client_number, service_number, orderer_client_number, orderer_service_number, desired_date, contract_number,
                       line_id, pre_agreement_id, home_id, products, project_id, coupling_id, bracket_id, bracket_size, ont_serial,
                       infrastructure_key, sequence_key, created_at, updated_at)
                   VALUES (:tenant, :partner, :role, :number, :bc, :phase, :requests, :cancelled, :complaints, :appointment,
                       :connectivity, :bracketed, :handover, :handoverOnly, :finished, :client, :service, :ordererClient,
                       :ordererService, :desired, :contract, :line, :preAgreement, :home, :products, :project, :coupling,
                       :bracket, :bracketSize, :ont, :infra, :seqKey, :created, :updated)""",
            ).params(params).update(keys, "id")
            return order.copy(id = OrderId(keys.key!!.toLong()))
        }
        val updated = jdbc.sql(
            // all columns: a draft may change any content before it is sent
            """UPDATE spri_order SET external_order_number = :number, business_case = :bc, phase = :phase, open_requests = :requests,
                   cancelled = :cancelled, complaints = :complaints, appointment_requested = :appointment, connectivity = :connectivity,
                   bracketed = :bracketed, handover_phase = :handover, finished = :finished, client_number = :client,
                   service_number = :service, orderer_client_number = :ordererClient, orderer_service_number = :ordererService,
                   desired_date = :desired, contract_number = :contract, line_id = :line, pre_agreement_id = :preAgreement,
                   home_id = :home, products = :products, project_id = :project, coupling_id = :coupling, bracket_id = :bracket,
                   bracket_size = :bracketSize, ont_serial = :ont, infrastructure_key = :infra, sequence_key = :seqKey,
                   updated_at = :updated, version = version + 1
               WHERE id = :id AND version = :version""",
        ).params(params + mapOf("id" to order.id!!.value, "version" to order.version)).update()
        if (updated != 1) throw OptimisticLockingFailureException("order ${order.id} was changed concurrently")
        return order.copy(version = order.version + 1)
    }

    override fun deleteDraft(id: OrderId): Boolean {
        jdbc.sql("DELETE FROM spri_order_draft WHERE order_id = :id").param("id", id.value).update()
        return jdbc.sql("DELETE FROM spri_order WHERE id = :id AND phase = 'DRAFT'").param("id", id.value).update() == 1
    }

    override fun append(entry: OrderHistoryEntry) {
        jdbc.sql(
            """INSERT INTO spri_order_history (order_id, partner_id, archive_id, kind, direction, message_type, change_indicator, codes, findings, created_at)
               VALUES (:order, :partner, :archive, :kind, :direction, :type, :indicator, :codes, :findings, :at)""",
        )
            .param("direction", entry.direction.name)
            .param("order", entry.orderId?.value).param("partner", entry.partnerId.value).param("archive", entry.archiveId)
            .param("kind", entry.kind.name).param("type", entry.messageType?.code).param("indicator", entry.changeIndicator.name)
            .param("codes", entry.codes.joinToString(",").ifEmpty { null })
            .param("findings", entry.findings.joinToString("\n").take(4000).ifEmpty { null })
            .param("at", Timestamp.from(entry.at))
            .update()
    }

    private fun map(rs: ResultSet): Order {
        val businessCase = BusinessCase.fromCode(rs.getString("business_case"))
        return Order(
            id = OrderId(rs.getLong("id")),
            partnerId = PartnerId(rs.getLong("partner_id")),
            role = OrderRole.valueOf(rs.getString("role")),
            data = OrderData(
                externalOrderNumber = rs.getString("external_order_number"),
                businessCase = businessCase,
                changeIndicator = ChangeIndicator.STANDARD,
                clientNumber = rs.getString("client_number"),
                serviceNumber = rs.getString("service_number"),
                ordererClientNumber = rs.getString("orderer_client_number"),
                ordererServiceNumber = rs.getString("orderer_service_number"),
                desiredDate = rs.getDate("desired_date")?.toLocalDate(),
                contractNumber = rs.getString("contract_number"),
                lineId = rs.getString("line_id"),
                preAgreementId = rs.getString("pre_agreement_id"),
                homeId = rs.getString("home_id"),
                products = rs.getString("products")?.split('|').orEmpty(),
                projectId = rs.getString("project_id"),
                couplingId = rs.getString("coupling_id"),
                bracketId = rs.getString("bracket_id"),
                bracketSize = rs.getInt("bracket_size").takeIf { !rs.wasNull() },
                ontSerial = rs.getString("ont_serial"),
                infrastructureKey = rs.getString("infrastructure_key"),
            ),
            state = OrderState(
                businessCase = businessCase,
                phase = Phase.valueOf(rs.getString("phase")),
                connectivity = rs.getBoolean("connectivity"),
                bracketed = rs.getBoolean("bracketed"),
                openRequests = rs.getString("open_requests").split(',').filter { it.isNotEmpty() }.map(RequestKind::valueOf).toSet(),
                cancelled = rs.getBoolean("cancelled"),
                complaints = rs.getInt("complaints"),
                appointmentRequested = rs.getBoolean("appointment_requested"),
                handover = rs.getString("handover_phase")?.let(HandoverPhase::valueOf),
                handoverOnly = rs.getBoolean("handover_only"),
            ),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
            version = rs.getLong("version"),
            sequenceKey = rs.getString("sequence_key"),
        )
    }
}
