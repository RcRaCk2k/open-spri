package de.openspri.domain.message

/** Parties in an S/PRI exchange. The abgebender EKP only takes part in the provider change (PV). */
enum class Party { AUFTRAGGEBER, LEISTUNGSERBRINGER, ABGEBENDER_EKP }

/**
 * Business message types (specification chapter 7.1 and 8; XSD MeldungstypType without TEQ).
 * The technical acknowledgement (TEQ) is not a business message and is handled by the SOAP layer.
 */
enum class MessageType(val code: String, val sender: Party, val recipient: Party) {
    QEB("QEB", Party.LEISTUNGSERBRINGER, Party.AUFTRAGGEBER),
    ABM("ABM", Party.LEISTUNGSERBRINGER, Party.AUFTRAGGEBER),
    ERLM("ERLM", Party.LEISTUNGSERBRINGER, Party.AUFTRAGGEBER),
    ENTM("ENTM", Party.LEISTUNGSERBRINGER, Party.AUFTRAGGEBER),
    ABBM("ABBM", Party.LEISTUNGSERBRINGER, Party.AUFTRAGGEBER),
    VZM("VZM", Party.LEISTUNGSERBRINGER, Party.AUFTRAGGEBER),
    TAM("TAM", Party.LEISTUNGSERBRINGER, Party.AUFTRAGGEBER),
    MTAM("MTAM", Party.LEISTUNGSERBRINGER, Party.AUFTRAGGEBER),
    ZWM_LE("ZWM-LE", Party.LEISTUNGSERBRINGER, Party.AUFTRAGGEBER),
    TBK_LE("TBK-LE", Party.LEISTUNGSERBRINGER, Party.AUFTRAGGEBER),
    KDLE("KDLE", Party.LEISTUNGSERBRINGER, Party.AUFTRAGGEBER),
    ZWM_AG("ZWM-AG", Party.AUFTRAGGEBER, Party.LEISTUNGSERBRINGER),
    TBK_AG("TBK-AG", Party.AUFTRAGGEBER, Party.LEISTUNGSERBRINGER),
    ERLM_K("ERLM-K", Party.AUFTRAGGEBER, Party.LEISTUNGSERBRINGER),
    AKM_PV("AKM-PV", Party.LEISTUNGSERBRINGER, Party.ABGEBENDER_EKP),
    ABM_PV("ABM-PV", Party.LEISTUNGSERBRINGER, Party.ABGEBENDER_EKP),
    ERLM_PV("ERLM-PV", Party.LEISTUNGSERBRINGER, Party.ABGEBENDER_EKP),
    ENTM_PV("ENTM-PV", Party.LEISTUNGSERBRINGER, Party.ABGEBENDER_EKP),
    ABBM_PV("ABBM-PV", Party.LEISTUNGSERBRINGER, Party.ABGEBENDER_EKP),
    RUEM_PV("RUEM-PV", Party.ABGEBENDER_EKP, Party.LEISTUNGSERBRINGER),
    ;

    companion object {
        fun fromCode(code: String): MessageType? = entries.firstOrNull { it.code == code }
    }
}
