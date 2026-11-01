# ADR-0004: Technikversionen für den Start (Spring Boot 4.1, JDK 25)

Status: angenommen | Datum: 2026-11-01 | Präzisiert: D-09

## Kontext

D-09 hatte „Spring Boot 3, JDK 21“ festgelegt. Bei der Umsetzung von M0 (November 2026) zeigte sich:

- Die kostenlosen Updates der Spring-Boot-3.x-Linie sind ausgelaufen. Aktuell ist Spring Boot 4.1 (Spring Framework 7, Spring Security 7, Jackson 3).
- Apache CXF 4.2.4, die SOAP- und WS-Security-Grundlage, ist gegen Spring Boot 4.1.1 und Spring 7 gebaut.
- JDK 25 ist das aktuelle LTS-Release und wird von Spring Boot 4 und Kotlin 2.3 unterstützt.

Eine Software, die zertifiziert und viele Jahre betrieben werden soll, darf nicht auf einer Linie starten, die keine Sicherheitsupdates mehr bekommt.

## Entscheidung

| Bereich | Version | Bemerkung |
|---|---|---|
| JDK | 25 (LTS, Temurin) | Gradle-Toolchain lädt sie bei Bedarf automatisch |
| Kotlin | 2.3.21 | Version, die das Spring-Boot-BOM verwaltet |
| Spring Boot | 4.1.1 | passend zu CXF 4.2.4 |
| Apache CXF | 4.2.4 | ab M1 |
| Gradle | 9.8.0 (Wrapper) | |
| MariaDB | 11.8 LTS | |
| nginx | 1.30 (stable) | Proxy und Frontend |
| certbot | 5.1.0 | |
| Node.js / pnpm | 24 LTS / 12.13 | nur zum Bauen des Frontends |
| React / TypeScript / Vite | 19.3 / 6.0 / 8.3 | TypeScript 7 (native Version) erst, wenn die Werkzeuge es unterstützen |

Weitere Festlegungen:
- Paketnamensraum `de.openspri`. Das ist ein Namensraum des Projekts, keine registrierte Domain.
- Alle Versionen sind fest gepinnt und mindestens zwei Wochen alt. Dependabot schlägt Updates wöchentlich vor.

## Konsequenzen

- Code-Beispiele für Spring Boot 3 aus dem Internet passen nicht immer. Wichtige Unterschiede: Starter `spring-boot-starter-webmvc`, Testmodule `spring-boot-starter-webmvc-test`, Jackson 3 (`tools.jackson.*`), neue Paketnamen der Auto-Konfigurationen.
- Updates innerhalb der Linie (Patch und Minor) laufen über Dependabot. Ein Major-Wechsel braucht ein ADR.
