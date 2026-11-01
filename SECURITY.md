# Sicherheitslücken melden

open-spri verarbeitet Aufträge zwischen Telekommunikationsanbietern und personenbezogene Endkundendaten. Wir nehmen Sicherheitsmeldungen sehr ernst.

## So meldest du eine Lücke

Bitte melde Sicherheitslücken **nicht** als öffentliches Issue, sondern:

1. **Bevorzugt:** über GitHub, Reiter „Security“ → „Report a vulnerability“ (Private Vulnerability Reporting) im Repository [RcRaCk2k/open-spri](https://github.com/RcRaCk2k/open-spri/security)
2. **Alternativ:** per E-Mail an **abuse@rsm-freilassing.de**, Betreff beginnend mit `[open-spri security]`

Hilfreich sind:
- betroffene Version bzw. Commit oder Image-Digest
- Beschreibung, Auswirkung und Schritte zur Reproduktion
- ob die Lücke die S/PRI-Schnittstelle (SOAP), die REST-API, das Frontend oder den Betrieb betrifft

## Was dann passiert

- Wir bestätigen den Eingang innerhalb von 5 Werktagen.
- Wir bewerten die Meldung und stimmen mit dir einen Zeitplan zur Behebung ab.
- Nach der Behebung veröffentlichen wir ein GitHub Security Advisory. Auf Wunsch nennen wir dich darin.

## Unterstützte Versionen

Sicherheitskorrekturen gibt es für das jeweils neueste Release. Vor Version 1.0 gibt es keine unterstützten Versionen.

## Hinweis für Betreiber

Zertifizierte Builds (siehe README) werden als signierte Container-Images veröffentlicht. Betreiber sollten die Signatur prüfen und Sicherheitsupdates zeitnah einspielen. Bitte beachte, dass eine Sicherheitskorrektur einen neuen zertifizierten Build erfordern kann.
