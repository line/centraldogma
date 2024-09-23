package com.linecorp.centraldogma.server.auth.saml

import com.google.common.annotations.VisibleForTesting
import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.server.ServerPort
import com.linecorp.armeria.server.saml.SamlServiceProvider
import com.linecorp.centraldogma.server.auth.AuthProvider
import com.linecorp.centraldogma.server.auth.AuthProviderFactory
import com.linecorp.centraldogma.server.auth.AuthProviderParameters
import org.opensaml.security.credential.CredentialResolver
import org.opensaml.security.credential.UsageType
import org.opensaml.security.credential.impl.StaticCredentialResolver
import org.opensaml.security.x509.BasicX509Credential
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration

class SamlAuthProviderFactory : AuthProviderFactory {
    override fun create(parameters: AuthProviderParameters): AuthProvider {
        val properties =
            checkNotNull(
                parameters.authConfig().properties(SamlAuthProperties::class.java),
            ) { "authentication properties are not specified" }

        val idp = properties.idp

        val samlServiceProvider =
            SamlServiceProvider.builder()
                .entityId(properties.entityId)
                .hostname(properties.hostname)
                .schemeAndPort(ServerPort(443, SessionProtocol.HTTPS))
                .authorizer(parameters.authorizer())
                .ssoHandler(
                    SamlAuthSsoHandler(
                        sessionIdGenerator = { parameters.sessionIdGenerator().get() },
                        loginSessionPropagator = { parameters.loginSessionPropagator().apply(it) },
                        sessionValidDuration =
                            Duration.ofMillis(
                                parameters.authConfig().sessionTimeoutMillis(),
                            ),
                        loginNameNormalizer = { parameters.authConfig().loginNameNormalizer().apply(it) },
                        subjectLoginNameIdFormat = idp.subjectLoginNameIdFormat,
                        attributeLoginName = idp.attributeLoginName,
                        attributeGroupName = idp.attributeGroupName,
                    ),
                )
                .credentialResolver(metadataResolver(properties))
                .signatureAlgorithm(properties.signatureAlgorithm)
                .apply {
                    idp()
                        .entityId(idp.entityId)
                        .ssoEndpoint(idp.endpoint)
                        .signingKey(idp.signingKey)
                        .encryptionKey(idp.encryptionKey)
                }
                .build()

        return SamlAuthProvider(samlServiceProvider)
    }

    @VisibleForTesting
    internal fun metadataResolver(properties: SamlAuthProperties): CredentialResolver {
        val factory = CertificateFactory.getInstance("X.509")

        val certificate =
            factory.generateCertificate(properties.samlMetadata.byteInputStream()) as X509Certificate

        val keyPairGenerator = KeyPairGenerator.getInstance(certificate.publicKey.algorithm)

        val generateKeyPair = keyPairGenerator.generateKeyPair()

        val credential =
            BasicX509Credential(certificate).apply {
                setUsageType(UsageType.SIGNING)
                setPrivateKey(generateKeyPair.private)
            }

        return StaticCredentialResolver(credential)
    }
}
