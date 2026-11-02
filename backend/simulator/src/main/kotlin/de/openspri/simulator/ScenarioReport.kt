package de.openspri.simulator

import java.time.Instant

/**
 * Report of a simulator run (AUFGABE 10.3): JUnit XML for CI tools and an HTML page, one entry per test case
 * with its TF-ID, the role of open-spri and the transcript. Suitable for the KFT preparation with Enghouse.
 */
object ScenarioReport {

    fun junitXml(results: List<ScenarioResult>, at: Instant = Instant.now()): String = buildString {
        val failures = results.count { !it.passed }
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<testsuite name="open-spri KFT-Szenarien" tests="${results.size}" failures="$failures" errors="0" skipped="0" """)
        append("""time="${seconds(results.sumOf { it.millis })}" timestamp="$at">""").append('\n')
        results.forEach { r ->
            append("""  <testcase classname="KFT.${role(r)}" name="${xml(r.scenario.case.testName)}" time="${seconds(r.millis)}">""").append('\n')
            r.failure?.let { append("""    <failure message="${xml(it.lineSequence().first())}">${xml(transcript(r))}</failure>""").append('\n') }
            append("""    <system-out>${xml(transcript(r))}</system-out>""").append('\n')
            append("  </testcase>\n")
        }
        append("</testsuite>\n")
    }

    fun html(results: List<ScenarioResult>, at: Instant = Instant.now()): String = buildString {
        val passed = results.count { it.passed }
        append("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\"><title>KFT-Szenarien</title>")
        append("<style>body{font-family:sans-serif;margin:2em}table{border-collapse:collapse;width:100%}td,th{border:1px solid #ccc;padding:4px 8px;vertical-align:top;text-align:left}")
        append(".ok{color:#0a0}.fail{color:#c00}pre{margin:0;white-space:pre-wrap;font-size:90%}</style></head><body>")
        append("<h1>open-spri – KFT-Szenarien</h1><p>Lauf vom $at: <b>$passed von ${results.size}</b> Testfällen bestanden.</p>")
        append("<table><tr><th>TF-ID</th><th>Rolle open-spri</th><th>Beschreibung</th><th>Ergebnis</th><th>Protokoll</th></tr>")
        results.forEach { r ->
            val case = r.scenario.case
            append("<tr><td>${xml(case.id)}</td><td>${role(r)}</td><td>${xml(case.description)}</td>")
            append(if (r.passed) "<td class=\"ok\">bestanden</td>" else "<td class=\"fail\">${xml(r.failure!!)}</td>")
            append("<td><pre>${xml(transcript(r))}</pre></td></tr>")
        }
        append("</table></body></html>\n")
    }

    private fun role(r: ScenarioResult) = r.scenario.role.name
    private fun transcript(r: ScenarioResult) = r.transcript.joinToString("\n")
    private fun seconds(millis: Long) = "%.3f".format(java.util.Locale.ROOT, millis / 1000.0)
    private fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
