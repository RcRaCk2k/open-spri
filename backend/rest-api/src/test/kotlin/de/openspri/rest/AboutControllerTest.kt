package de.openspri.rest

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.boot.info.BuildProperties
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AboutControllerTest {

    private fun controller(digest: String?, certified: List<String>): AboutController {
        val beans = StaticListableBeanFactory()
        beans.addBean("buildProperties", BuildProperties(Properties().apply { setProperty("version", "1.2.3") }))
        return AboutController(beans.getBeanProvider(BuildProperties::class.java), CertificationProperties(digest, certified))
    }

    @Test
    fun `reports version and S-PRI version`() {
        val about = controller(null, emptyList()).about()
        assertEquals("1.2.3", about.version)
        assertEquals("4.4", about.spriVersion)
        assertFalse(about.certifiedBuild)
    }

    @Test
    fun `certified only if the running digest is listed`() {
        assertTrue(controller("sha256:abc", listOf("sha256:abc")).about().certifiedBuild)
        assertFalse(controller("sha256:def", listOf("sha256:abc")).about().certifiedBuild)
        assertFalse(controller("", listOf("")).about().certifiedBuild)
    }
}
