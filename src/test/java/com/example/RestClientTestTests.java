package com.example;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static jakarta.ws.rs.core.HttpHeaders.ACCEPT;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN;
import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Map;
import java.util.stream.IntStream;

import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager.TestInjector.AnnotatedAndMatchesType;
import io.quarkus.test.junit.QuarkusTest;

import com.example.RestClientTestTests.WiremockServerResource;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.smallrye.faulttolerance.api.CircuitBreakerMaintenance;
import io.smallrye.faulttolerance.api.CircuitBreakerState;

@QuarkusTest
@QuarkusTestResource(WiremockServerResource.class)
class RestClientTestTests {
	private static final String URI = "/api/test";

	@Inject
	@RestClient
	RestClientTest client;

	@InjectWireMock
	WireMockServer wireMockServer;

	@Inject
	CircuitBreakerMaintenance circuitBreakerMaintenance;

	  @BeforeEach
  public void beforeEach() {
    this.wireMockServer.resetAll();
  }

  @AfterEach
  public void afterEach() {
    // Reset all circuit breaker counts after each test
    this.circuitBreakerMaintenance.resetAll();
  }

	@Test
	public void circuitBreakerWorks() {
			this.wireMockServer.stubFor(
				get(urlEqualTo(URI))
					.willReturn(serverError())
			);

		assertThat(this.circuitBreakerMaintenance.currentState("hello"))
			.isEqualTo(CircuitBreakerState.CLOSED);

		// The way the circuit breaker works is that you have to fire at least requestVolumeThreshold
		// requests at the breaker before it starts to trip
		// This is so it can fill its window
		// Circuit breaker should trip after 2 calls to hello
		// 1 Call = 1 actual call + 2 retries = 3 total calls
		// Circuit breaker is set to trip after 8
		// So 3 invocations of the call should trip it
		// Let's do 12 invocations just to be safe
		// 12 invocations, each with 2 retries = 12 * (1 call + 2 retries) = 36 total calls
		IntStream.range(1, 12)
			.forEach(i ->
				assertThat(this.client.hello())
					.isEqualTo(RestClientTest.FALLBACK_TEXT)
			);

		assertThat(this.circuitBreakerMaintenance.currentState("hello"))
			.isEqualTo(CircuitBreakerState.OPEN);

		// Now verify that the server should only have seen 8 requests
		// 8 = the request volume threshold
		this.wireMockServer.verify(8,
			getRequestedFor(urlEqualTo(URI))
				.withHeader(ACCEPT, containing(TEXT_PLAIN))
		);
	}

	@Test
	public void timeoutTriggersFallback() {
		this.wireMockServer.stubFor(
			get(urlEqualTo(URI))
				.willReturn(
					okForContentType(TEXT_PLAIN, "Hello!")
						.withFixedDelay(5_000)
				)
		);

		assertThat(this.client.hello())
			.isEqualTo(RestClientTest.FALLBACK_TEXT);

		this.wireMockServer.verify(3,
			getRequestedFor(urlEqualTo(URI))
				.withHeader(ACCEPT, containing(TEXT_PLAIN))
		);
	}

	@Test
	public void allGood() {
		this.wireMockServer.stubFor(
			get(urlEqualTo(URI))
				.willReturn(okForContentType(TEXT_PLAIN, "Hello!"))
		);

		assertThat(this.client.hello())
			.isEqualTo("Hello!");

		this.wireMockServer.verify(getRequestedFor(urlEqualTo(URI))
				.withHeader(ACCEPT, containing(TEXT_PLAIN))
		);
	}

	@Target({ METHOD, CONSTRUCTOR, FIELD })
	@Retention(RUNTIME)
	@Documented
	@interface InjectWireMock {
	}

	public static class WiremockServerResource implements QuarkusTestResourceLifecycleManager {
		private final WireMockServer wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());

		@Override
		public Map<String, String> start() {
			this.wireMockServer.start();

			return Map.of(
				"quarkus.rest-client.test-client.url", String.format("http://localhost:%d", this.wireMockServer.isHttpsEnabled() ? this.wireMockServer.httpsPort() : this.wireMockServer.port())
			);
		}

		@Override
		public void stop() {
			this.wireMockServer.stop();
		}

		@Override
		public void inject(TestInjector testInjector) {
			testInjector.injectIntoFields(
				this.wireMockServer,
				new AnnotatedAndMatchesType(InjectWireMock.class, WireMockServer.class)
			);
		}
	}
}