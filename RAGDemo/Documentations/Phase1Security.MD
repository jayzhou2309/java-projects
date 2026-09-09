# Phase 1 application configuration and security
Date: 2026-09-09
Status: Implemented; six security/config tests and real HTTP smoke checks passed.

## Purpose and files

- RagDemoApplication.java removes the dangling IBKRProperties import/registration. It runs ordinary Boot auto-configuration with no provider dependency.
- config/AppAuthProperties.java binds required, nonblank app.auth credentials. Its string representation is redacted.
- config/SecurityConfig.java explicitly enables web security, creates the filter chain, password encoder and one in-memory RESEARCHER user.
- controller/HealthController.java exposes GET /api/v1/health with only {"status":"UP"}.
- application.yaml configures database variables, Flyway migrations, Hibernate validation, UTC, disabled open-in-view, loopback web binding and suppressed error details.
- config/AppAuthPropertiesTest.java and SecurityConfigTest.java verify binding and HTTP policy.

Only GET /api/v1/health is public. Other paths require HTTP Basic authentication. CSRF remains enabled, including for authenticated writes. No analysis endpoint has been added, so an authenticated request there returns 404. The health endpoint is liveness, not a continuous database-readiness check.

Application credentials are independent of database credentials. No OpenAI key, SEC user agent, IBKR login, model call or provider connection is needed. Provider and Spring AI starters were removed from the Phase 1 build rather than being activated with placeholder credentials.

No .env file is automatically imported by Spring. Export the selected environment file explicitly as shown in Phase1Checkpoint.MD. This avoids silently activating old provider settings. Compose is manually started, not application-managed.

## Methods

| Class / method | Added or changed | Inputs → output | Function |
| --- | --- | --- | --- |
| RagDemoApplication.main(args) | Body unchanged; removed missing configuration registration | String[] → void | Start Spring application |
| AppAuthProperties canonical constructor | Added record | username/password strings → properties | Immutable binding with nonblank validation |
| AppAuthProperties.username(), password() | Added record accessors | None → String | Supply auth configuration to security bean |
| AppAuthProperties.toString() | Added override | None → redacted String | Avoid exposing credentials through routine object logging |
| SecurityConfig.securityFilterChain(http) | Added | HttpSecurity → SecurityFilterChain | Public liveness, authenticated other routes, HTTP Basic, retained CSRF |
| SecurityConfig.passwordEncoder() | Added | None → PasswordEncoder | Delegating encoder for in-memory password storage |
| SecurityConfig.userDetailsService(properties, encoder) | Added | AppAuthProperties, PasswordEncoder → UserDetailsService | Build one configured RESEARCHER user |
| HealthController.health() | Added | None → Map<String,String> | Minimal UP response |

Generated record equality/hash methods have standard Java record semantics and are not customized. There is no credential persistence or user-administration API.

## Tests

Each test method has no arguments and returns void with assertions.

| Class.method | Function |
| --- | --- |
| AppAuthPropertiesTest.missingOrBlankCredentialsFailBinding | Reject absent/blank credentials without inheriting shell auth variables |
| AppAuthPropertiesTest.configuredCredentialsBindWithoutLeakingThroughToString | Valid binding and redacted string representation |
| SecurityConfigTest.healthIsPublicAndMinimal | Health response without login |
| SecurityConfigTest.otherRoutesRequireAuthentication | Protect analysis/old-monitor paths and reject wrong credentials |
| SecurityConfigTest.validCredentialsPassSecurityButDoNotInventAnAnalysisApi | Authenticated request reaches routing and returns 404 |
| SecurityConfigTest.csrfRemainsRequiredForWrites | Authenticated write without token rejected; valid token passes security to missing route |

Maven reports six test methods (2 binding + 4 HTTP).

The test-only Binding nested configuration enables AppAuthProperties; it has no custom methods. The initial WebMvcTest needed explicit EnableWebSecurity for its HttpSecurity bean. The missing-credentials test initially inherited shell credentials during integration testing; removing only its systemEnvironment property source fixed test isolation. Both fixes were rerun successfully on Java 21.

Real packaged app on 127.0.0.1:18080 returned:
- GET /api/v1/health: 200, {"status":"UP"}.
- GET /api/v1/analysis without credentials: 401.
- GET /api/v1/analysis with configured credentials: 404.

The temporary app was stopped after the smoke test.

## Adjustable behavior and limits

See the central variable table in [Phase1Checkpoint.MD](Phase1Checkpoint.MD).
Local HTTP Basic is a Phase 1 development foundation; shared deployment still needs TLS, persistent user identity/ownership and an API-specific write/auth policy. No shared deployment has been performed. The default 127.0.0.1 bind confines this server to the local host.
