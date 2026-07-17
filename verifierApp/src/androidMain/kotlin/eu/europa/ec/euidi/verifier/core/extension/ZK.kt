/*
 * Copyright (c) 2026 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */

package eu.europa.ec.euidi.verifier.core.extension

import com.kss.euid.zk.sdk.NatMode
import com.kss.euid.zk.sdk.PredicateMode
import com.kss.euid.zk.sdk.ZkPublicStatement
import com.kss.euid.zk.sdk.demoIssuerPublicKey
import com.kss.euid.zk.sdk.isoAlpha2ToNumeric
import com.kss.euid.zk.sdk.predicateModeToken
import com.kss.euid.zk.sdk.resultAgeOver
import com.kss.euid.zk.sdk.verifyIdentity
import com.kss.euid.zk.sdk.zkContractV1
import eu.europa.ec.eudi.verifier.core.response.DeviceResponse
import eu.europa.ec.euidi.verifier.domain.config.model.ClaimItem
import eu.europa.ec.euidi.verifier.domain.config.model.ClaimKind
import eu.europa.ec.euidi.verifier.domain.config.model.ZkPredicateValue
import eu.europa.ec.euidi.verifier.domain.model.DocumentValidityDomain
import eu.europa.ec.euidi.verifier.domain.model.ReceivedDocumentDomain
import eu.europa.ec.euidi.verifier.presentation.model.RequestedDocumentUi
import org.multipaz.mdoc.zkp.ZkDocument
import org.multipaz.mdoc.zkp.ZkSystemSpec
import kotlin.time.ExperimentalTime

/**
 * Attaches the STWO ZK spec when this PID request carries one or more zero-knowledge predicate
 * claims ([ClaimKind.Zk]) **with an explicit parameter**. The predicate parameters — age threshold
 * and accepted nationality set — are read off each selected predicate's [ZkPredicateValue]. There
 * are no defaults: a predicate selected without a usable value is not requested, and if no predicate
 * carries a value, no spec is produced and the request proceeds entirely over plaintext.
 *
 * Only ZK claims are considered here; disclosure claims are requested in plaintext separately.
 */
internal fun RequestedDocumentUi.intoZkSystemSpecs(): List<ZkSystemSpec> {
    val contract = zkContractV1()
    val inputs = this.requestedZkInputs() ?: return emptyList()

    val mode = when {
        inputs.wantsAge && inputs.wantsNat -> PredicateMode.AND
        inputs.wantsAge -> PredicateMode.AGE
        else -> PredicateMode.NAT
    }

    val spec = ZkSystemSpec(id = contract.specIdPid, system = contract.systemName).apply {
        addParam(contract.paramPredicateMode, predicateModeToken(mode))
        inputs.minAge?.let { addParam(contract.paramMinAge, it.toLong()) }
        if (inputs.wantsNat) {
            addParam(contract.paramAcceptedCountries, inputs.acceptedCountries.joinToString(","))
        }
        addParam(contract.paramVersion, 1L)
        addParam(contract.paramNumAttributes, 2L)
    }
    return listOf(spec)
}

/**
 * The verifier-supplied public inputs of a ZK predicate request: the age threshold and the accepted
 * nationality set (numeric ISO-3166 codes, in request order). These define what the proof must
 * satisfy. They are derived only from the request — never echoed back by the prover — so the
 * verifier alone fixes the bar; see [requestedZkInputsBySpecId].
 */
internal data class RequestedZkInputs(
    val minAge: UInt?,
    val acceptedCountries: List<UInt>,
) {
    val wantsAge: Boolean get() = minAge != null
    val wantsNat: Boolean get() = acceptedCountries.isNotEmpty()
}

/**
 * Extracts the ZK predicate inputs this PID request carries, or null when it requests no usable
 * predicate. Used both to build the spec (what we ask the wallet to prove) and to reconstruct the
 * statement at verification time (what we check the proof against), so the two cannot drift.
 */
private fun RequestedDocumentUi.requestedZkInputs(): RequestedZkInputs? {
    val contract = zkContractV1()
    if (this.documentType.docType != contract.doctypePid) return null

    // Pair each selected ZK predicate with its (attribute label, operator-supplied value).
    val zkPredicates = this.claims.mapNotNull { claim ->
        (claim.kind as? ClaimKind.Zk)?.let { claim.label to it.value }
    }
    if (zkPredicates.isEmpty()) return null

    val minAge: UInt? = zkPredicates.firstNotNullOfOrNull { (label, value) ->
        if (label == contract.elementBirthDate) {
            (value as? ZkPredicateValue.AgeOver)?.years?.toUInt()
        } else {
            null
        }
    }
    val acceptedCountries: List<UInt> = zkPredicates.firstNotNullOfOrNull { (label, value) ->
        if (label == contract.elementNationality) {
            (value as? ZkPredicateValue.NationalityIn)?.countries
        } else {
            null
        }
    }?.mapNotNull { isoAlpha2ToNumeric(it) }.orEmpty()

    // No parameter resolved to a usable value → the predicate is not valid, so request no ZK.
    return if (minAge == null && acceptedCountries.isEmpty()) {
        null
    } else {
        RequestedZkInputs(minAge = minAge, acceptedCountries = acceptedCountries)
    }
}

/**
 * Maps each requested ZK spec id to the predicate inputs the verifier asked for, so that
 * [verifiedZKDocuments] can supply them as the proof's public inputs. Correlation by spec id is
 * required because neither the threshold nor the accepted set is carried back in the response —
 * and trusting the prover for them would let a wallet satisfy a weaker predicate than required.
 */
internal fun List<RequestedDocumentUi>.requestedZkInputsBySpecId(): Map<String, RequestedZkInputs> {
    val contract = zkContractV1()
    val inputs = this.firstNotNullOfOrNull { it.requestedZkInputs() } ?: return emptyMap()
    return mapOf(contract.specIdPid to inputs)
}

/**
 * Verifies the Zero-Knowledge proofs in a response and maps them to received documents.
 *
 * For each [ZkDocument] we reconstruct the public statement the wallet proved against — the issuer
 * key hash pinned from the verifier's own trust store (the PQ mdoc carries no x5chain), `today` from
 * the proof timestamp, the nonce from the session transcript, and the predicate inputs (mode, age
 * threshold, accepted nationality set) from
 * [requestedInputsBySpecId] (correlated by spec id). The predicate inputs come from the verifier's
 * request, NOT from the prover's asserted claims: taking the threshold/set from the response would
 * let a wallet relabel its result (e.g. prove `age_over_18` when `age_over_21` was required) and
 * have it accepted. The asserted boolean claims are surfaced only as the document's display claims.
 */
@OptIn(ExperimentalTime::class)
internal fun DeviceResponse.verifiedZKDocuments(
    requestedInputsBySpecId: Map<String, RequestedZkInputs> = emptyMap(),
): List<ReceivedDocumentDomain> {
    val contract = zkContractV1()
    return deviceResponse.zkDocuments.mapNotNull { zkDoc ->
        val data = zkDoc.documentData
        val resultClaims = data.issuerSigned[contract.pidNamespace] ?: return@mapNotNull null

        // The predicate to verify against is the one WE requested for this spec, never what the
        // prover asserted. A document for a spec we never requested has no inputs → it cannot verify.
        val requested = requestedInputsBySpecId[data.zkSystemSpecId]

        // Every predicate we asked to be proven must actually be asserted in the response. The proof
        // binds the predicate mode, but the asserted result claims are separate metadata, so a wallet
        // could answer (say) an age+nationality request with nationality only. Require each requested
        // predicate's result claim to be present, otherwise the response is incomplete → not trusted.
        val requiredResultKeys = buildSet {
            requested?.minAge?.let { add(resultAgeOver(it)) }
            if (requested?.wantsNat == true) add(contract.resultNatInSet)
        }
        val requestedPredicatesPresent =
            requested != null && resultClaims.keys.containsAll(requiredResultKeys)

        val statement = ZkPublicStatement(
            specId = data.zkSystemSpecId,
            version = 1u,
            doctype = contract.doctypePid,
            namespace = contract.pidNamespace,
            issuerPublicKeyHash = java.security.MessageDigest.getInstance("SHA-256").digest(demoIssuerPublicKey()),
            todayEpochDay = (data.timestamp.epochSeconds / 86_400L).toInt(),
            nonce = sessionTranscript,
            predicateMode = when {
                requested?.wantsAge == true && requested.wantsNat -> PredicateMode.AND
                requested?.wantsAge == true -> PredicateMode.AGE
                else -> PredicateMode.NAT
            },
            ageThresholdYears = requested?.minAge,
            acceptedNumericCountries = requested?.acceptedCountries?.takeIf { it.isNotEmpty() },
            natMode = NatMode.ANY,
        )

        val verified = requestedPredicatesPresent && runCatching {
            verifyIdentity(statement = statement, proof = zkDoc.proof.toByteArray()).ok
        }.getOrDefault(false)

        ReceivedDocumentDomain(
            isTrusted = verified,
            docType = data.docType,
            claims = resultClaims.keys.associate { claimKey ->
                ClaimItem(label = claimKey) to if (verified) "true" else "unverified"
            },
            validity = DocumentValidityDomain(
                isDeviceSignatureValid = null,
                // The ZK proof attests the issuer (ML-DSA-65/SHA-256) signature over the predicate.
                isIssuerSignatureValid = verified,
                isDataIntegrityIntact = verified,
                signed = null,
                validFrom = null,
                validUntil = null,
            ),
        )
    }
}