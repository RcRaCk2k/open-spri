package de.openspri.rest.order

import de.openspri.domain.order.Order
import de.openspri.domain.order.OrderEvent
import de.openspri.domain.order.OrderHistoryEntry
import de.openspri.domain.order.OutboundOrderService
import de.openspri.soap.OrderComposer
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalDate


@Schema(description = "Ein Auftrag (Übersicht). Der vollständige Auftrag liegt im Nachrichtenarchiv.")
data class OrderDto(
    @field:Schema(requiredMode = REQUIRED) val id: Long,
    @field:Schema(requiredMode = REQUIRED) val partnerId: Long,
    @field:Schema(description = "Unsere Rolle: AUFTRAGGEBER, LEISTUNGSERBRINGER oder ABGEBENDER_EKP", requiredMode = REQUIRED) val role: String,
    @field:Schema(example = "AG-2026-000123", requiredMode = REQUIRED) val externalOrderNumber: String,
    @field:Schema(description = "Geschäftsfall wie in der Spezifikation", example = "NEU", requiredMode = REQUIRED) val businessCase: String,
    @field:Schema(description = "Phase des Zustandsautomaten; DRAFT = Entwurf, noch nicht gesendet", example = "CONFIRMED", requiredMode = REQUIRED) val phase: String,
    @field:Schema(requiredMode = REQUIRED) val finished: Boolean,
    @field:Schema(description = "offene Änderungen: STORNO, TERMINVERSCHIEBUNG, REKLAMATION", requiredMode = REQUIRED) val openRequests: List<String>,
    @field:Schema(requiredMode = REQUIRED) val cancelled: Boolean,
    @field:Schema(description = "Austausch mit dem abgebenden EKP beim Providerwechsel") val handoverPhase: String?,
    val clientNumber: String?,
    val serviceNumber: String?,
    val desiredDate: LocalDate?,
    val contractNumber: String?,
    val lineId: String?,
    val homeId: String?,
    @field:Schema(requiredMode = REQUIRED) val products: List<String>,
    @field:Schema(requiredMode = REQUIRED) val createdAt: Instant,
    @field:Schema(requiredMode = REQUIRED) val updatedAt: Instant,
) {
    companion object {
        fun of(order: Order) = OrderDto(
            id = order.id!!.value,
            partnerId = order.partnerId.value,
            role = order.role.name,
            externalOrderNumber = order.data.externalOrderNumber,
            businessCase = order.data.businessCase.code,
            phase = order.state.phase.name,
            finished = order.state.finished,
            openRequests = order.state.openRequests.map { it.name }.sorted(),
            cancelled = order.state.cancelled,
            handoverPhase = order.state.handover?.name,
            clientNumber = order.data.clientNumber,
            serviceNumber = order.data.serviceNumber,
            desiredDate = order.data.desiredDate,
            contractNumber = order.data.contractNumber,
            lineId = order.data.lineId,
            homeId = order.data.homeId,
            products = order.data.products,
            createdAt = order.createdAt,
            updatedAt = order.updatedAt,
        )
    }
}

@Schema(description = "Eine Aktion, die der Zustandsautomat jetzt zulässt")
data class ActionDto(
    @field:Schema(
        description = "MESSAGE (POST /orders/{id}/messages), REQUEST (STORNO: /cancel, TERMINVERSCHIEBUNG: /reschedule, " +
            "REKLAMATION: /complaint) oder SEND (Entwurf senden: /send)",
        requiredMode = REQUIRED,
    )
    val kind: String,
    @field:Schema(description = "bei MESSAGE: Meldungstyp", example = "ABM") val messageType: String?,
    @field:Schema(description = "bei MESSAGE: Änderungskennzeichen des beantworteten Auftrags", example = "Standard") val changeIndicator: String?,
    @field:Schema(description = "bei REQUEST: STORNO, TERMINVERSCHIEBUNG oder REKLAMATION") val request: String?,
) {
    companion object {
        fun of(event: OrderEvent) = when (event) {
            is OrderEvent.Message -> ActionDto("MESSAGE", event.type.code, event.changeIndicator.code, null)
            is OrderEvent.Request -> ActionDto("REQUEST", null, null, event.kind.name)
            OrderEvent.Send -> ActionDto("SEND", null, null, null)
        }
    }
}

@Schema(description = "Ein Eintrag im Verlauf: Auftrag, Änderung oder Meldung mit den Befunden der Prüfungen")
data class HistoryDto(
    @field:Schema(description = "ORDER, REQUEST oder MESSAGE", requiredMode = REQUIRED) val kind: String,
    @field:Schema(description = "INBOUND (vom Partner) oder OUTBOUND (von uns)", requiredMode = REQUIRED) val direction: String,
    @field:Schema(description = "eingehend: Id der Rohnachricht (GET /messages/{id}/raw); ausgehend: siehe GET /deliveries?orderId=…") val archiveId: Long?,
    val messageType: String?,
    @field:Schema(requiredMode = REQUIRED) val changeIndicator: String,
    @field:Schema(requiredMode = REQUIRED) val codes: List<String>,
    @field:Schema(description = "Befunde der Prüfungen (z. B. Anlage 1 im Report-Modus)", requiredMode = REQUIRED) val findings: List<String>,
    @field:Schema(requiredMode = REQUIRED) val at: Instant,
) {
    companion object {
        fun of(entry: OrderHistoryEntry) = HistoryDto(
            entry.kind.name, entry.direction.name, entry.archiveId, entry.messageType?.code, entry.changeIndicator.code, entry.codes, entry.findings, entry.at,
        )
    }
}

@Schema(description = "Auftrag mit den jetzt zulässigen Aktionen")
data class OrderDetailDto(
    @field:Schema(requiredMode = REQUIRED) val order: OrderDto,
    @field:Schema(description = "was wir als Nächstes senden dürfen", requiredMode = REQUIRED) val allowedActions: List<ActionDto>,
) {
    companion object {
        fun of(order: Order, service: OutboundOrderService) = OrderDetailDto(OrderDto.of(order), service.allowedActions(order).map(ActionDto::of))
    }
}

@Schema(description = "Neuer Auftrag an einen Partner (wir sind Auftraggeber)")
data class CreateOrderRequest(
    @field:Schema(requiredMode = REQUIRED) val partnerId: Long,
    @field:Schema(
        description = "Das XSD-Element `auftrag` (Anlage 3, order.xsd) als JSON: Feldnamen wie im XSD, abstrakte Typen mit `@type` " +
            "(z. B. `\"geschaeftsfall\": {\"@type\": \"BereitstellungType\", …}`). Den Kopf (`control`) setzt open-spri.",
        requiredMode = REQUIRED,
    )
    val auftrag: JsonNode,
)

@Schema(description = "Terminverschiebung")
data class RescheduleRequest(
    @field:Schema(description = "neuer Termin (terminNeu)", requiredMode = REQUIRED) val newDate: LocalDate,
)

@Schema(description = "Reklamation einer abgeschlossenen Entstörung")
data class ComplaintRequest(
    @field:Schema(description = "der Entstörauftrag mit `reklamation` (wie bei POST /orders)", requiredMode = REQUIRED) val auftrag: JsonNode,
)

@Schema(description = "Ein Meldungscode; ohne Text wird der Text aus der Meldungscodetabelle übernommen")
data class CodeDto(
    @field:Schema(example = "0000", requiredMode = REQUIRED) val code: String,
    val text: String? = null,
)

@Schema(description = "Eine Meldung zu einem Auftrag. Kopf, externe Auftragsnummer und Auftraggebernummer übernimmt open-spri aus dem Auftrag.")
data class SendMessageRequest(
    @field:Schema(description = "Meldungstyp", example = "ABM", requiredMode = REQUIRED) val type: String,
    @field:Schema(description = "Änderungskennzeichen des Auftrags, auf den die Meldung antwortet", example = "Standard") val changeIndicator: String = "Standard",
    @field:Schema(requiredMode = REQUIRED) val codes: List<CodeDto>,
    @field:Schema(
        description = "weitere `meldungsattribute` des XSD-Typs `Meldungsattribute<Typ>Type`, z. B. " +
            "`{\"verbindlicherLiefertermin\": \"2026-12-01\"}` bei ABM",
    )
    val attributes: JsonNode? = null,
)

@Schema(description = "Kündigung durch den Leistungserbringer (KUE-LE): Inhalt der KDLE (MeldungsattributeKDLEType)")
data class TerminationRequest(
    @field:Schema(description = "der Auftraggeber, dem wir kündigen", requiredMode = REQUIRED) val partnerId: Long,
    @field:Schema(description = "von uns vergebene Nummer dieses Vorgangs", example = "KDLE-2026-0001", requiredMode = REQUIRED) val externalOrderNumber: String,
    @field:Schema(description = "Vertragsnummer des Einzelvertrags", requiredMode = REQUIRED) val contractNumber: String,
    @field:Schema(description = "Auftraggebernummer", requiredMode = REQUIRED) val clientNumber: String,
    @field:Schema(description = "abschaltungstermin", requiredMode = REQUIRED) val shutdownDate: LocalDate,
    @field:Schema(description = "abschaltungsgrund, z. B. Zahlungsverzug") val reason: String? = null,
    @field:Schema(description = "LineID der Leitung; nicht Teil der KDLE, ordnet den Postausgang und hilft bei der Suche") val lineId: String? = null,
    @field:Schema(requiredMode = REQUIRED) val codes: List<CodeDto>,
    @field:Schema(description = "weitere `meldungsattribute` von MeldungsattributeKDLEType") val attributes: JsonNode? = null,
)

@Schema(description = "Providerwechsel-Anfrage: eine AKM-PV, die wir als abgebender EKP erhalten haben")
data class PvRequestDto(
    @field:Schema(requiredMode = REQUIRED) val order: OrderDto,
    @field:Schema(description = "die RUEM-PV steht noch aus", requiredMode = REQUIRED) val replyDue: Boolean,
    @field:Schema(description = "Id der AKM-PV im Nachrichtenarchiv (GET /messages/{id}, Scope nötig)") val announcementArchiveId: Long?,
    @field:Schema(description = "aufnehmender Provider (providername der AKM-PV)") val newProvider: String?,
    @field:Schema(description = "uebernahmedatumGeplant") val plannedTransferDate: LocalDate?,
    @field:Schema(description = "antwortfrist: bis dahin muss die RUEM-PV gesendet sein") val replyDeadline: LocalDate?,
    @field:Schema(description = "bisherige LineID (anschluss)") val lineId: String?,
)

@Schema(description = "RUEM-PV: Antwort auf eine AKM-PV. Vertrags- und Auftragsnummern, VorabstimmungsID und `anschluss` übernimmt open-spri aus der AKM-PV.")
data class PvReplyRequest(
    @field:Schema(description = "zustimmungProviderwechsel: true = J, false = N", requiredMode = REQUIRED) val consent: Boolean,
    @field:Schema(description = "providername (unser Name)") val providerName: String? = null,
    @field:Schema(description = "antwortcode, z. B. bei Ablehnung") val answerCode: String? = null,
    @field:Schema(description = "antworttext") val answerText: String? = null,
    @field:Schema(requiredMode = REQUIRED) val codes: List<CodeDto>,
)

@Schema(description = "Person (`salutation` 1 Herr, 2 Frau, 9 keine Anrede) oder mit `company` eine Firma")
data class PartyDto(
    @field:Schema(example = "2") val salutation: String? = null,
    @field:Schema(example = "Erika") val firstName: String? = null,
    @field:Schema(example = "Musterfrau") val lastName: String? = null,
    @field:Schema(description = "Firmenname; dann wird `firma` statt `person` gesetzt") val company: String? = null,
    @field:Schema(example = "0123 456789") val phone: String? = null,
) {
    fun toComposer() = OrderComposer.Party(salutation, firstName, lastName, company, phone)
}

@Schema(description = "Anschrift des Standorts")
data class AddressDto(
    val street: String? = null,
    val houseNumber: String? = null,
    val houseNumberSuffix: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val district: String? = null,
    val buildingPart: String? = null,
) {
    fun toComposer() = OrderComposer.Address(street, houseNumber, houseNumberSuffix, postalCode, city, district, buildingPart)
}

@Schema(description = "Angaben zur Störung (EST)")
data class FaultDto(
    @field:Schema(description = "stoerungsNrAuftraggeber") val number: String? = null,
    @field:Schema(description = "stoerungsbeschreibung") val description: String? = null,
    @field:Schema(description = "vorpruefungErfolgt") val preChecked: Boolean = false,
    @field:Schema(description = "kontaktEndkunde") val customer: PartyDto? = null,
    @field:Schema(description = "kontaktaufnahmeErwuenscht") val contactWanted: Boolean = false,
) {
    fun toComposer() = OrderComposer.Fault(number, description, preChecked, customer?.toComposer(), contactWanted)
}

@Schema(description = "Einfache Angaben, aus denen open-spri das `auftrag`-JSON des Geschäftsfalls zusammensetzt")
data class ComposeOrderRequest(
    @field:Schema(description = "NEU, KUE-AG, AEN-LMAE, LAE, PV, EST, DIAG-GET, DIAG-SET", example = "NEU", requiredMode = REQUIRED) val businessCase: String,
    val externalOrderNumber: String? = null,
    @field:Schema(description = "auftraggebernummer") val clientNumber: String? = null,
    @field:Schema(description = "leistungsnummer") val serviceNumber: String? = null,
    @field:Schema(description = "auftraggeberWunschtermin") val desiredDate: LocalDate? = null,
    @field:Schema(description = "Ansprechpartner Auftragsmanagement") val contact: PartyDto? = null,
    @field:Schema(description = "KUE-AG, AEN-LMAE, LAE, EST, DIAG: Vertragsnummer aus dem Bestand") val contractNumber: String? = null,
    @field:Schema(description = "Leitungen im Bestand und PV") val lineId: String? = null,
    @field:Schema(description = "VorabstimmungsID (WBCI), Pflicht bei PV") val preAgreementId: String? = null,
    @field:Schema(description = "erstes = Produkt der Auftragsposition, weitere = Positionen (AEN-LMAE: hinzugefügte Leistungsmerkmale)") val products: List<String> = emptyList(),
    @field:Schema(description = "Endkunde am Standort (NEU, PV)") val siteParty: PartyDto? = null,
    @field:Schema(description = "Standort A (NEU, PV)") val site: AddressDto? = null,
    val homeId: String? = null,
    val fault: FaultDto? = null,
    @field:Schema(description = "DIAG: Operationen, z. B. BBdown, BBup oder RESET") val operations: List<String> = emptyList(),
    @field:Schema(description = "Zeitfenster des Wunschtermins (1 bis 20)") val timeWindow: String? = null,
)

@Schema(description = "Das zusammengesetzte `auftrag`-JSON; noch nicht geprüft und nicht gespeichert")
data class ComposedOrderDto(
    @field:Schema(description = "für POST /orders (Feldnamen der XSD)", requiredMode = REQUIRED) val auftrag: JsonNode,
    @field:Schema(description = "was der Geschäftsfall noch braucht (XSD-Pfade)", requiredMode = REQUIRED) val missing: List<String>,
)
