package com.example.credit_system_kotlin.auth.token

import com.example.credit_system_kotlin.auth.config.JwtProperties
import com.example.credit_system_kotlin.user.domain.UserRole
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
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import javax.crypto.spec.SecretKeySpec

private val log = LoggerFactory.getLogger(AccessTokenService::class.java)

/** 검증을 통과한 액세스 토큰이 말해 주는 것. 요청마다 DB 를 보지 않고 이 값만으로 신원과 권한을 정한다. */
data class AccessPrincipal(val userId: Long, val role: UserRole)

/**
 * 액세스 JWT(HS256)를 내고 확인한다. 서버에 저장하지 않아 도중에 무효로 만들 수 없다.
 * 로그아웃·역할 변경은 다음 갱신 때 반영된다.
 */
@Service
class AccessTokenService(
    private val jwtProperties: JwtProperties,
    private val clock: Clock
) {

    private val encoder: JwtEncoder
    private val decoder: JwtDecoder

    init {
        val key = SecretKeySpec(jwtProperties.secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build()
        decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build().apply {
            // 기본 검증기는 시스템 시계와 60초 여유를 쓴다. 발급과 검증이 같은 서버라 시계 차이를 봐줄
            // 이유가 없으므로 여유를 0 으로 두고, 주입받은 시계로 만료를 판단한다.
            setJwtValidator(
                DelegatingOAuth2TokenValidator(
                    JwtTimestampValidator(Duration.ZERO).apply { setClock(clock) },
                    JwtIssuerValidator(jwtProperties.issuer)
                )
            )
        }
    }

    /** 역할을 토큰에 넣어 낸다. 그래서 역할이 바뀌어도 이 토큰이 만료될 때까지는 예전 역할이다. */
    fun issue(userId: Long, role: UserRole): String {
        val now = clock.instant()
        val claims = JwtClaimsSet.builder()
            .issuer(jwtProperties.issuer)
            .subject(userId.toString())
            .claim(ROLE_CLAIM, role.name)
            .issuedAt(now)
            .expiresAt(now.plus(jwtProperties.accessTtl))
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }

    /** 서명·만료·issuer 가 맞으면 신원을 돌려준다. 어느 하나라도 틀리면 이유를 가리지 않고 null 이다. */
    fun verify(token: String): AccessPrincipal? {
        val jwt = try {
            decoder.decode(token)
        } catch (e: JwtException) {
            // 만료는 정상 흐름(갱신 신호)이라 조용히 넘긴다. 토큰 자체는 남기지 않는다.
            log.debug("액세스 토큰 검증 실패: {}", e.javaClass.simpleName)
            return null
        }
        return toPrincipal(jwt)
    }

    /** sub 가 숫자가 아니거나 role 이 모르는 값이면 null. 서명이 맞아도 믿지 않는다. */
    private fun toPrincipal(jwt: Jwt): AccessPrincipal? {
        val userId = jwt.subject?.toLongOrNull()
        val role = UserRole.entries.firstOrNull { it.name == jwt.getClaimAsString(ROLE_CLAIM) }
        if (userId == null || role == null) {
            // 서명은 맞는데 내용이 우리가 낸 모양이 아니다. 키가 다른 용도로 쓰였거나 발급 코드가 어긋난 것이다.
            log.warn("서명은 유효하지만 sub·role 클레임이 올바르지 않은 액세스 토큰")
            return null
        }
        return AccessPrincipal(userId, role)
    }

    companion object {
        const val ROLE_CLAIM = "role"
    }
}
