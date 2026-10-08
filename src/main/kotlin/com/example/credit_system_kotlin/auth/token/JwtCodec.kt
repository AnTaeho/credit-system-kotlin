package com.example.credit_system_kotlin.auth.token

import com.example.credit_system_kotlin.auth.config.JwtProperties
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import javax.crypto.spec.SecretKeySpec

private val log = LoggerFactory.getLogger(JwtCodec::class.java)

// 두 토큰이 같은 키로 서명되므로 typ 클레임에 종류를 적어 서로의 자리에 쓰이지 못하게 한다.
enum class TokenType(val claimValue: String) {
    ACCESS("access"),
    REFRESH("refresh")
}

// 액세스·리프레시 JWT 를 같은 키(HS256)와 issuer 로 서명하고 검증한다.
@Component
class JwtCodec(
    private val jwtProperties: JwtProperties,
    private val clock: Clock
) {

    private val encoder: JwtEncoder
    private val decoder: JwtDecoder

    init {
        val key = SecretKeySpec(jwtProperties.secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build()
        decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build().apply {
            setJwtValidator(
                DelegatingOAuth2TokenValidator(
                    JwtTimestampValidator(Duration.ZERO).apply { setClock(clock) },
                    JwtIssuerValidator(jwtProperties.issuer)
                )
            )
        }
    }

    // sub·iat·exp·typ 은 여기서 채우고, 종류마다 다른 클레임(role, jti)은 호출자가 claims 로 더한다.
    fun encode(
        type: TokenType,
        userId: Long,
        ttl: Duration,
        claims: JwtClaimsSet.Builder.() -> Unit = {}
    ): String {
        val now = clock.instant()
        val claimsSet = JwtClaimsSet.builder()
            .issuer(jwtProperties.issuer)
            .subject(userId.toString())
            .claim(TYPE_CLAIM, type.claimValue)
            .issuedAt(now)
            .expiresAt(now.plus(ttl))
            .apply(claims)
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return encoder.encode(JwtEncoderParameters.from(header, claimsSet)).tokenValue
    }

    // 서명·만료·issuer 가 맞고 종류가 expected 인 토큰만 돌려준다. 어긋나면 null 이다.
    fun decode(token: String, expected: TokenType): Jwt? {
        val jwt = try {
            decoder.decode(token)
        } catch (e: JwtException) {
            // 만료는 정상 흐름(갱신 신호)이라 조용히 넘긴다. 토큰 자체는 남기지 않는다.
            log.debug("{} 토큰 검증 실패: {}", expected.claimValue, e.javaClass.simpleName)
            return null
        }
        if (jwt.getClaimAsString(TYPE_CLAIM) != expected.claimValue) {
            log.debug("{} 토큰 자리에 다른 종류의 토큰이 왔다", expected.claimValue)
            return null
        }
        return jwt
    }

    companion object {
        const val TYPE_CLAIM = "typ"
    }
}
