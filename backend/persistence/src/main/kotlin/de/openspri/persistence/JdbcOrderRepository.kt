package de.openspri.persistence

import de.openspri.domain.BusinessCase
import de.openspri.domain.ChangeIndicator
import de.openspri.domain.order.HandoverPhase
import de.openspri.domain.order.Order
import de.openspri.domain.order.OrderData
import de.openspri.domain.order.OrderHistoryEntry
import de.openspri.domain.order.OrderId
import de.openspri.domain.order.OrderRepository
import de.openspri.domain.order.OrderRole
import de.openspri.domain.order.OrderState
import de.openspri.domain.order.Phase
import de.openspri.domain.order.RequestKind
import de.openspri.domain.partner.PartnerId
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp

@Repository
class JdbcOrderRepository(private val jdbc: JdbcClient) : OrderRepository {

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
            "ont" to d.ontSerial, "infra" to d.infrastructureKey?.take(300),
            "created" to Timestamp.from(order.createdAt), "updated" to Timestamp.from(order.updatedAt),
        )
        if (order.id == null) {
            val keys = GeneratedKeyHolder()
            jdbc.sql(
                """INSERT INTO spri_order (tenant_id, partner_id, role, external_order_number, business_case, phase, open_requests,
                       cancelled, complaints, appointment_requested, connectivity, bracketed, handover_phase, handover_only, finished,
                       client_number, service_number, orderer_client_number, orderer_service_number, desired_date, contract_number,
                       line_id, pre_agreement_id, home_id, products, project_id, coupling_id, bracket_id, bracket_size, ont_serial,
                       infrastructure_key, created_at, updated_at)
                   VALUES (:tenant, :partner, :role, :number, :bc, :phase, :requests, :cancelled, :complaints, :appointment,
                       :connectivity, :bracketed, :handover, :handoverOnly, :finished, :client, :service, :ordererClient,
                       :ordererService, :desired, :contract, :line, :preAgreement, :home, :products, :project, :coupling,
                       :bracket, :bracketSize, :ont, :infra, :created, :updated)""",
            ).params(params).update(keys, "id")
            return order.copy(id = OrderId(keys.key!!.toLong()))
        }
        val updated = jdbc.sql(
            """UPDATE spri_order SET phase = :phase, open_requests = :requests, cancelled = :cancelled, complaints = :complaints,
                   appointment_requested = :appointment, handover_phase = :handover, finished = :finished,
                   desired_date = :desired, contract_number = :contract, line_id = :line, ont_serial = :ont,
                   updated_at = :updated, version = version + 1
               WHERE id = :id AND version = :version""",
        ).params(params + mapOf("id" to order.id!!.value, "version" to order.version)).update()
        if (updated != 1) throw OptimisticLockingFailureException("order ${order.id} was changed concurrently")
        return order.copy(version = order.version + 1)
    }

    override fun append(entry: OrderHistoryEntry) {
        jdbc.sql(
            """INSERT INTO spri_order_history (order_id, partner_id, archive_id, kind, message_type, change_indicator, codes, findings, created_at)
               VALUES (:order, :partner, :archive, :kind, :type, :indicator, :codes, :findings, :at)""",
        )
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
        )
    }
}
