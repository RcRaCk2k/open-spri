package de.openspri.rest

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * @property buildDigest digest of the running image, injected at build/deploy time (OPENSPRI_BUILD_DIGEST)
 * @property certifiedDigests image digests that passed the KFT; published with each certified release
 */
@ConfigurationProperties("openspri.certification")
data class CertificationProperties(
    val buildDigest: String? = null,
    val certifiedDigests: List<String> = emptyList(),
)
