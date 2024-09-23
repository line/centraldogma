package com.linecorp.centraldogma.server.auth.saml

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.common.base.Strings
import com.linecorp.armeria.common.AggregatedHttpRequest
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.saml.InvalidSamlRequestException
import com.linecorp.armeria.server.saml.SamlBindingProtocol
import com.linecorp.armeria.server.saml.SamlEndpoint
import com.linecorp.armeria.server.saml.SamlIdentityProviderConfig
import com.linecorp.armeria.server.saml.SamlNameIdFormat
import com.linecorp.armeria.server.saml.SamlSingleSignOnHandler
import com.linecorp.centraldogma.server.auth.Session
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil
import io.netty.handler.codec.http.QueryStringDecoder
import org.opensaml.core.xml.schema.XSString
import org.opensaml.messaging.context.MessageContext
import org.opensaml.saml.common.messaging.context.SAMLBindingContext
import org.opensaml.saml.saml2.core.Attribute
import org.opensaml.saml.saml2.core.AuthnRequest
import org.opensaml.saml.saml2.core.Response
import org.opensaml.xmlsec.signature.support.SignatureConstants
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.time.Duration
import java.util.Objects.requireNonNull
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

data class Idp(
    val entityId: String,
    val uri: String,
    val binding: SamlBindingProtocol = SamlBindingProtocol.HTTP_POST,
    val signingKey: String,
    val encryptionKey: String,
    val subjectLoginNameIdFormat: String?,
    val attributeLoginName: String?,
    val attributeGroupName: String?,
) {
    @JsonCreator
    constructor(
        @JsonProperty("entityId") entityId: String,
        @JsonProperty("uri") uri: String,
        @JsonProperty("binding") binding: String?,
        @JsonProperty("signingKey") signingKey: String?,
        @JsonProperty("encryptionKey") encryptionKey: String?,
        @JsonProperty("subjectLoginNameIdFormat") subjectLoginNameIdFormat: String?,
        @JsonProperty("attributeLoginName") attributeLoginName: String?,
        @JsonProperty("attributeGroupName") attributeGroupName: String?,
    ) : this(
        entityId = entityId,
        uri = uri,
        binding = binding?.let(SamlBindingProtocol::valueOf) ?: SamlBindingProtocol.HTTP_POST,
        signingKey = listOfNotNull(signingKey, entityId).first(),
        encryptionKey = listOfNotNull(encryptionKey, entityId).first(),
        subjectLoginNameIdFormat =
            if (subjectLoginNameIdFormat == null && attributeLoginName == null) {
                subjectLoginNameIdFormat
            } else {
                SamlNameIdFormat.EMAIL.urn()
            },
        attributeLoginName =
            if (subjectLoginNameIdFormat == null && attributeLoginName == null) {
                null
            } else {
                attributeLoginName
            },
        attributeGroupName,
    )

    val endpoint =
        when (binding) {
            SamlBindingProtocol.HTTP_POST -> SamlEndpoint.ofHttpPost(uri)
            SamlBindingProtocol.HTTP_REDIRECT -> SamlEndpoint.ofHttpRedirect(uri)
            else -> throw IllegalStateException("Failed to get an endpoint of the Idp: $entityId")
        }
}

data class SamlAuthProperties(
    val entityId: String,
    val hostname: String,
    val signingKey: String = "signing",
    val encryptionKey: String = "encryption",
    val samlMetadata: String,
    val signatureAlgorithm: String = SignatureConstants.ALGO_ID_SIGNATURE_RSA,
    val idp: Idp,
)

class SamlAuthSsoHandler(
    private val sessionIdGenerator: () -> String,
    private val loginSessionPropagator: (Session) -> CompletableFuture<Void>,
    private val sessionValidDuration: Duration,
    private val loginNameNormalizer: (String) -> String,
    private val subjectLoginNameIdFormat: String?,
    private val attributeLoginName: String?,
    private val attributeGroupName: String?,
) : SamlSingleSignOnHandler {
    override fun beforeInitiatingSso(
        ctx: ServiceRequestContext,
        req: HttpRequest,
        message: MessageContext<AuthnRequest>,
        idpConfig: SamlIdentityProviderConfig,
    ): CompletionStage<Void> {
        val decoder = QueryStringDecoder(req.path(), true)
        val ref = decoder.parameters()["ref"]
        if (ref.isNullOrEmpty()) return CompletableFuture.completedFuture(null)

        val relayState = ref[0]
        if (idpConfig.ssoEndpoint().bindingProtocol() == SamlBindingProtocol.HTTP_REDIRECT &&
            relayState.length > 80
        ) {
            return CompletableFuture.completedFuture(null)
        }

        message.getSubcontext(SAMLBindingContext::class.java, true).apply {
            checkNotNull(this) { SAMLBindingContext::class.java.name }
            this.relayState = relayState
        }

        return CompletableFuture.completedFuture(null)
    }

    override fun loginSucceeded(
        ctx: ServiceRequestContext,
        req: AggregatedHttpRequest,
        message: MessageContext<Response>,
        sessionIndex: String?,
        relayState: String?,
    ): HttpResponse {
        val response =
            requireNonNull(message, "message").message!!
        val username =
            Optional.ofNullable<String>(findLoginNameFromSubjects(response))
                .orElseGet { findLoginNameFromAttributes(response) }
        if (Strings.isNullOrEmpty(username)) {
            return loginFailed(
                ctx,
                req,
                message,
                IllegalStateException("Cannot get a username from the response"),
            )
        }

        val sessionId: String = sessionIdGenerator()
        val samlSession = SamlSession(findGroupsFromAttributes(response))
        val session =
            Session(sessionId, loginNameNormalizer(username), sessionValidDuration, samlSession)
        val redirectionScript =
            if (!Strings.isNullOrEmpty(relayState)) {
                try {
                    "window.location.href='/#${URLEncoder.encode(relayState, "UTF-8")}'"
                } catch (e: UnsupportedEncodingException) {
                    // Should never reach here.
                    throw Error()
                }
            } else {
                "window.location.href='/'"
            }
        return HttpResponse.of(
            loginSessionPropagator(session).thenApply { _ ->
                HttpResponse.of(
                    HttpStatus.OK,
                    MediaType.HTML_UTF_8,
                    HtmlUtil.getHtmlWithOnload(
                        "localStorage.setItem('sessionId','$sessionId')",
                        redirectionScript,
                    ),
                )
            },
        )
    }

    private fun findLoginNameFromSubjects(response: Response): String? {
        if (subjectLoginNameIdFormat.isNullOrEmpty()) return null
        return response.assertions
            .map { it.subject.nameID }
            .firstOrNull { it.format == subjectLoginNameIdFormat }
            ?.value
    }

    private fun findLoginNameFromAttributes(response: Response): String? {
        if (attributeLoginName.isNullOrEmpty()) return null
        return response.assertions
            .asSequence()
            .flatMap { it.attributeStatements.asSequence() }
            .flatMap { it.attributes.asSequence() }
            .filter { it.name == attributeLoginName }
            .firstOrNull()
            ?.let { attr: Attribute ->
                val v = attr.attributeValues[0]
                if (v is XSString) {
                    v.value
                } else {
                    null
                }
            }
    }

    private fun findGroupsFromAttributes(response: Response): List<String> {
        if (attributeGroupName.isNullOrEmpty()) return emptyList()

        return response.assertions
            .asSequence()
            .flatMap { it.attributeStatements }
            .flatMap { it.attributes }
            .filter { it.name == attributeGroupName }
            .flatMap { it.attributeValues }
            .filterIsInstance<XSString>()
            .mapNotNull { it.value }
            .toList()
    }

    override fun loginFailed(
        ctx: ServiceRequestContext,
        req: AggregatedHttpRequest,
        message: MessageContext<Response>?,
        cause: Throwable,
    ): HttpResponse {
        val status =
            if (cause is InvalidSamlRequestException) {
                HttpStatus.BAD_REQUEST
            } else {
                HttpStatus.INTERNAL_SERVER_ERROR
            }
        return HttpApiUtil.newResponse(ctx, status, cause)
    }
}
