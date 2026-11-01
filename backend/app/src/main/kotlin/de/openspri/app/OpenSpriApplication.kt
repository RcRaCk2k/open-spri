package de.openspri.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["de.openspri"])
class OpenSpriApplication

fun main(args: Array<String>) {
    runApplication<OpenSpriApplication>(*args)
}
