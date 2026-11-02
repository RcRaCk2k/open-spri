package de.openspri.domain.availability

import java.time.LocalDate

/**
 * Availability of products at one location (AUFGABE 5.4), one data record (`ds`) of a Replikat as specified by the
 * AK S&P ("Replikate zur Verfügbarkeitsprüfung" 1.7, chapter 3). Field names are English; each one names its
 * Replikat element. The record contains no personal data (Replikat 3.1).
 *
 * @property change `ak`: change mark of the record; mandatory in a delta, optional in a full Replikat
 * @property addressId `aid`: stable ID of the postal address (does not change when a street is renamed)
 * @property longitude `geo/long`, ETRS89; kept as given (the examples of Anlage 3 swap long and lat, SA-017)
 * @property latitude `geo/lat`
 */
data class Location(
    val change: ChangeMark? = null,
    val addressId: String? = null,
    val longitude: String? = null,
    val latitude: String? = null,
    /** `plz` */
    val postalCode: String,
    /** `o` */
    val city: String,
    /** `ot` */
    val district: String? = null,
    /** `s` */
    val street: String,
    /** `h`, 1-4 digits; a range like 16-20 is `h`=16, `hz`=20 (Replikat 3.3.7) */
    val houseNumber: String,
    /** `hz` */
    val houseNumberSuffix: String? = null,
    /** `gebaeudeteil` */
    val buildingPart: String? = null,
    /** `anzNutzungseinheiten` */
    val units: String? = null,
    /** `anzGeschaeftseinheiten` */
    val businessUnits: String? = null,
    /** `pl/p`: at least one product */
    val products: List<ProductAvailability>,
    /** `tl/t` */
    val technologies: List<TechnologyAvailability> = emptyList(),
) {
    /** All HomeIDs served at this location (`tl/t/hidl/hid`). */
    val homeIds: Set<String> get() = technologies.flatMap { it.homeIds }.toSet()

    /** Identity of the record for delta processing, see [LocationKey]. */
    val key: LocationKey get() = LocationKey.of(this)
}

/** `ak`: ADD, MOD, DEL, UCH (Replikat 3.3.1). */
enum class ChangeMark { ADD, MOD, DEL, UCH }

/**
 * Identity of a record (SA-016): the Adress-ID, otherwise the address (`plz`, `o`, `ot`, `s`, `h`, `hz`), plus the
 * building part and the HomeIDs. The XSD allows a location several times only if the sets of HomeIDs differ
 * (documentation of `ds`); the building part gets its own record per Replikat 3.3.10.
 */
data class LocationKey(val address: String, val buildingPart: String, val homeIds: String) {
    companion object {
        fun of(location: Location): LocationKey {
            val address = location.addressId?.let { "AID:$it" } ?: listOf(
                location.postalCode, location.city, location.district, location.street, location.houseNumber, location.houseNumberSuffix,
            ).joinToString("|") { normalize(it) }
            return LocationKey(address, normalize(location.buildingPart), location.homeIds.sorted().joinToString(","))
        }

        fun normalize(value: String?) = value?.trim()?.lowercase()?.replace(Regex("\\s+"), " ").orEmpty()
    }
}

/** `pl/p` (Replikat 3.5). */
data class ProductAvailability(
    /** `produkt`: product identifier as used in the order (`produkt/bezeichner`) */
    val product: String,
    /** `technologie`: reference to [TechnologyAvailability.technology] */
    val technology: String? = null,
    /** `technologieCpe`, e.g. VDSL, ETHERNET, GFAST */
    val cpeTechnology: String? = null,
    /** `netzabschlussKunde`, e.g. TAE, RJ45 */
    val networkTermination: String? = null,
    /** `erwarteteBandbreite/downBB` in Mbit/s */
    val downstream: String? = null,
    /** `erwarteteBandbreite/upBB` in Mbit/s */
    val upstream: String? = null,
    /** `realisierbarkeitProdukt/realisierbarAb`: earliest possible date */
    val availableFrom: LocalDate? = null,
    /** `realisierbarkeitProdukt/realisierbarBis` */
    val availableUntil: LocalDate? = null,
    /** `bereitstellungszeit` in working days */
    val provisioningDays: String? = null,
    /** `hausanschlussprodukt` */
    val houseConnectionProduct: Boolean? = null,
)

/** `tl/t` (Replikat 3.4). */
data class TechnologyAvailability(
    /** `technologie`, e.g. FTTH, FTTB, FTTC, KOAX */
    val technology: String,
    /** `kont`: quota assignment */
    val quota: String? = null,
    /** `ane`: operator of the last mile (ITU carrier code) */
    val lastMileOperator: String? = null,
    /** `hidl/hid` */
    val homeIds: List<String> = emptyList(),
    /** `ausbaustatus`, e.g. notPlanned, preMarketing, homesConnected */
    val expansionStatus: String? = null,
    /** `ausbauzeitraum/start` */
    val expansionStart: LocalDate? = null,
    /** `ausbauzeitraum/ende` */
    val expansionEnd: LocalDate? = null,
    /** `eigentuemerdaten`: owner data required in the order */
    val ownerDataRequired: Boolean? = null,
    /** `geeErforderlich`: landowner declaration (document) required */
    val landownerDeclarationRequired: Boolean? = null,
    /** `geeVorhanden` */
    val landownerDeclarationPresent: Boolean? = null,
    /** `foerdergebiet` */
    val fundedArea: Boolean? = null,
    /** `auftragsart`: STANDARD or KONNEKTIVITAET */
    val orderKind: OrderKind? = null,
    /** `negativeAusbauentscheidung` */
    val negativeDecision: String? = null,
    /** `zl/z`: possible time windows */
    val timeWindows: List<String> = emptyList(),
    /** `netzverteilerZuordnung`, e.g. OLT */
    val distributionPoint: String? = null,
    /** `migration` */
    val migration: Boolean? = null,
    /** `ausbauvariante` */
    val expansionVariant: String? = null,
    /** `locationNetzabschlussKunde`, e.g. Wohnung, Keller */
    val terminationLocation: String? = null,
    /** `schwellwertvermarktung` */
    val thresholdMarketing: Boolean? = null,
    /** `vermarktungszeitraum/start` */
    val marketingStart: LocalDate? = null,
    /** `vermarktungszeitraum/ende` */
    val marketingEnd: LocalDate? = null,
    /** `sollwertSchwellwertvermarktung` */
    val thresholdTarget: String? = null,
    /** `istwertSchwellwertvermarktung` */
    val thresholdActual: String? = null,
    /** `ausbauPhase`: Nachfragebuendelung, Initialausbau, Nachverdichtung, Regelvermarktung */
    val expansionPhase: String? = null,
)

/** `auftragsart` (Replikat 3.4.14): which kind of order the location needs. */
enum class OrderKind { STANDARD, KONNEKTIVITAET }
