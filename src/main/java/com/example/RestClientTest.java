package com.example;

import java.time.temporal.ChronoUnit;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.WithSpan;

@Path("/api/test")
@RegisterRestClient(configKey = "test-client")
public interface RestClientTest {
	String FALLBACK_TEXT = "fallback hello";

	@GET
	@Produces(MediaType.TEXT_PLAIN)
	@WithSpan(kind = SpanKind.CLIENT, value = "RestClientTest.hello")
	@CircuitBreaker(requestVolumeThreshold = 8, failureRatio = 0.5, delay = 2, delayUnit = ChronoUnit.SECONDS)
//  @CircuitBreakerName("hello")
	@Timeout(value = 2, unit = ChronoUnit.SECONDS)
	@Retry(maxRetries = 2, delay = 200, delayUnit = ChronoUnit.MILLIS)
	@Fallback(fallbackMethod = "fallbackHello")
	String hello();

	default String fallbackHello() {
		return FALLBACK_TEXT;
	}
}
