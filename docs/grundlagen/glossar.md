# Glossar

| Begriff | Bedeutung | im Code |
|---|---|---|
| AG, EKP | Auftraggeber bzw. Endkundenprovider: bestellt beim LE | `OrderRole.AUFTRAGGEBER`, `Party.AUFTRAGGEBER` |
| LE, NGA, NGAB | Leistungserbringer bzw. Netzbetreiber: liefert das Vorleistungsprodukt | `OrderRole.LEISTUNGSERBRINGER` |
| EKPab, EKPauf | abgebender bzw. aufnehmender Endkundenprovider beim Providerwechsel | `OrderRole.ABGEBENDER_EKP` |
| GF | Geschäftsfall (NEU, KUE-AG, …) | `BusinessCase` |
| Änderungskennzeichen | Standard, Storno, Terminverschiebung | `ChangeIndicator` |
| TV | Terminverschiebung | `RequestKind.TERMINVERSCHIEBUNG` |
| TEQ | technische Quittung, synchrone Antwort auf jede Nachricht | `TeqCode` |
| QEB | qualifizierte Eingangsbestätigung | `MessageType.QEB` |
| ABM | Auftragsbestätigung mit verbindlichem Termin | `MessageType.ABM` |
| ERLM / ENTM | Erledigungs- bzw. Entgeltmeldung | |
| ABBM | Abbruchmeldung | |
| VZM | Verzögerungsmeldung | |
| TAM / MTAM | (Mahn-)Terminanforderung | |
| KDLE | Kündigung durch den Leistungserbringer | |
| MC | Meldungscode (Anlage 5) | `MessageCode` |
| ICC | ITU Carrier Code des Providers | `Partner.carrierCode` |
| LineID | Kennung des Kundenanschlusses, vom LE mit der ersten ABM vergeben | `OrderData.lineId` |
| HomeID | Kennung der Wohn- bzw. Geschäftseinheit (optional) | `OrderData.homeId` |
| VorabstimmungsID | ID aus der WBCI-Vorabstimmung, Voraussetzung für den PV | `OrderData.preAgreementId` |
| Replikat | tägliche Verfügbarkeitsdatei des LE (ab M6) | |
| KFT | Konformitätstest beim Simulator von Enghouse | `KftSequenceReplayTest` |
| Anlage 1 | fachliches Schema: Pflicht/Kann/unzulässig je Attribut | Modul `spri-rules` |
| Anlage 3 | XSD/WSDL | Modul `spri-schema` |
