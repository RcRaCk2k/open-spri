package de.openspri.app

import de.openspri.simulator.SimulatorMain
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["de.openspri"])
class OpenSpriApplication

/** `OPENSPRI_MODE=SIMULATOR` starts the KFT simulator instead of open-spri (same image, AUFGABE 8.1/10.3). */
fun main(args: Array<String>) {
    if (System.getenv("OPENSPRI_MODE").equals("SIMULATOR", ignoreCase = true)) return SimulatorMain.main(args)
    runApplication<OpenSpriApplication>(*args)
}
