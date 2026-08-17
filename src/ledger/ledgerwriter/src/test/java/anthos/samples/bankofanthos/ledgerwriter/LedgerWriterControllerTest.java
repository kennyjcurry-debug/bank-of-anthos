/*
 * Copyright 2020, Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package anthos.samples.bankofanthos.ledgerwriter;

import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_DUPLICATE_TRANSACTION;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_INSUFFICIENT_BALANCE;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_TRANSACTION_UNDER_REVIEW;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_WHEN_AUTHORIZATION_HEADER_NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.lang.Nullable;
import io.micrometer.stackdriver.StackdriverConfig;
import io.micrometer.stackdriver.StackdriverMeterRegistry;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

class LedgerWriterControllerTest {

    private LedgerWriterController ledgerWriterController;

    @Mock
    private TransactionValidator transactionValidator;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private JWTVerifier verifier;
    @Mock
    private Transaction transaction;
    @Mock
    private DecodedJWT jwt;
    @Mock
    private Claim claim;
    @Mock
    private Clock clock;

    private static final String VERSION = "v0.1.0";
    private static final String LOCAL_ROUTING_NUM = "123456789";
    private static final String NON_LOCAL_ROUTING_NUM = "987654321";
    private static final String BALANCES_API_ADDR = "balancereader:8080";
    private static final String AUTHED_ACCOUNT_NUM = "1234567890";
    private static final String BEARER_TOKEN = "Bearer abc";
    private static final String TOKEN = "abc";
    private static final String EXCEPTION_MESSAGE = "Invalid variable";
    private static final int SENDER_BALANCE = 40;
    private static final int LARGER_THAN_SENDER_BALANCE = 1000;
    private static final int SMALLER_THAN_SENDER_BALANCE = 10;
    // The documented default of TRANSFER_REVIEW_THRESHOLD, in cents.
    private static final int TRANSFER_REVIEW_THRESHOLD = 1000000;
    private static final int AT_REVIEW_THRESHOLD = 1000000;
    private static final int BELOW_REVIEW_THRESHOLD = 999999;
    private static final int ABOVE_REVIEW_THRESHOLD = 1000001;

    private ListAppender<ILoggingEvent> auditLog;

    /**
     * Capture what the controller actually logs.
     *
     * The log4j2 API this service codes against is bridged to SLF4J on the
     * test classpath, so the appender attaches at the SLF4J level. If that
     * binding ever changes the capture returns nothing and the assertions
     * below fail loudly rather than passing silently.
     */
    private void attachAuditLog() {
        auditLog = new ListAppender<>();
        auditLog.start();
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                LedgerWriterController.class.getName()))
                .addAppender(auditLog);
    }

    @AfterEach
    void tearDown() {
        if (auditLog != null) {
            ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                    LedgerWriterController.class.getName()))
                    .detachAppender(auditLog);
            auditLog.stop();
        }
    }

    /** The single INFO audit event for the high value review rule. */
    private String onlyReviewAuditEvent() {
        List<String> matches = new ArrayList<>();
        for (ILoggingEvent event : auditLog.list) {
            if (event.getLevel().isGreaterOrEqual(Level.INFO)
                    && event.getFormattedMessage().contains(
                            LedgerWriterController
                                    .RULE_HIGH_VALUE_TRANSACTION_REVIEW)) {
                matches.add(event.getFormattedMessage());
            }
        }
        assertEquals(1, matches.size(),
                "expected exactly one audit event at INFO or above for the "
                + "review rule, got: " + matches);
        return matches.get(0);
    }

    private boolean sawReviewAuditEvent() {
        for (ILoggingEvent event : auditLog.list) {
            if (event.getFormattedMessage().contains(
                    LedgerWriterController.RULE_HIGH_VALUE_TRANSACTION_REVIEW)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Assert no review audit event fired.
     *
     * Checks the capture is live first, so that a broken log binding fails
     * here rather than making the absence of an event look like a pass.
     */
    private void assertNoReviewAuditEvent() {
        assertTrue(!auditLog.list.isEmpty(),
                "log capture is not working: the controller logs on every "
                + "path, so the captured event list should not be empty");
        assertTrue(!sawReviewAuditEvent(),
                "no review audit event should be logged below the threshold");
    }

    /**
     * Build a controller with the given review threshold, in cents.
     */
    private LedgerWriterController newController(int reviewThreshold) {
        StackdriverMeterRegistry meterRegistry = new StackdriverMeterRegistry(new StackdriverConfig() {
              @Override
              public boolean enabled() {
                return false;
              }

              @Override
              public String projectId() {
                return "test";
              }

              @Override
              @Nullable
              public String get(String key) {
                return null;
              }
          }, clock);

        return new LedgerWriterController(verifier,
                meterRegistry,
                transactionRepository, transactionValidator,
                LOCAL_ROUTING_NUM, BALANCES_API_ADDR, VERSION,
                reviewThreshold);
    }

    @BeforeEach
    void setUp() {
        initMocks(this);
        attachAuditLog();

        ledgerWriterController = newController(TRANSFER_REVIEW_THRESHOLD);

        when(verifier.verify(TOKEN)).thenReturn(jwt);
        when(jwt.getClaim(
                LedgerWriterController.JWT_ACCOUNT_KEY)).thenReturn(claim);
    }

    @Test
    @DisplayName("Given version number in the environment, " +
            "return a ResponseEntity with the version number")
    void version() {
        // When
        final ResponseEntity actualResult = ledgerWriterController.version();

        // Then
        assertNotNull(actualResult);
        assertEquals(VERSION, actualResult.getBody());
        assertEquals(HttpStatus.OK, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the server is serving requests, return HTTP Status 200")
    void readiness() {
        // When
        final ResponseEntity actualResult = ledgerWriterController.readiness();

        // Then
        assertNotNull(actualResult);
        assertEquals(ledgerWriterController.READINESS_CODE,
                actualResult.getBody());
        assertEquals(HttpStatus.OK, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the transaction is external, return HTTP Status 201")
    void addTransactionSuccessWhenDiffThanLocalRoutingNum(TestInfo testInfo) {
        // Given
        when(transaction.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());

        // When
        final ResponseEntity actualResult =
                ledgerWriterController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(ledgerWriterController.READINESS_CODE,
                actualResult.getBody());
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the transaction is internal and the transaction amount == sender balance, " +
            "return HTTP Status 201")
    void addTransactionSuccessWhenAmountEqualToBalance(TestInfo testInfo) {
        // Given
        LedgerWriterController spyLedgerWriterController =
                spy(ledgerWriterController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromRoutingNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(SENDER_BALANCE);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        doReturn(SENDER_BALANCE).when(
                spyLedgerWriterController).getAvailableBalance(
                TOKEN, AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity actualResult =
                spyLedgerWriterController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(ledgerWriterController.READINESS_CODE,
                actualResult.getBody());
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the transaction is internal and the transaction amount < sender balance, " +
            "return HTTP Status 201")
    void addTransactionSuccessWhenAmountSmallerThanBalance(TestInfo testInfo) {
        // Given
        LedgerWriterController spyLedgerWriterController =
                spy(ledgerWriterController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromRoutingNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(SMALLER_THAN_SENDER_BALANCE);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        doReturn(SENDER_BALANCE).when(
                spyLedgerWriterController).getAvailableBalance(
                TOKEN, AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity actualResult =
                spyLedgerWriterController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(ledgerWriterController.READINESS_CODE,
                actualResult.getBody());
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the transaction is internal and the transaction amount > sender balance, " +
            "return HTTP Status 400")
    void addTransactionFailWhenWhenAmountLargerThanBalance(TestInfo testInfo) {
        // Given
        LedgerWriterController spyLedgerWriterController =
                spy(ledgerWriterController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(LARGER_THAN_SENDER_BALANCE);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        doReturn(SENDER_BALANCE).when(
                spyLedgerWriterController).getAvailableBalance(
                TOKEN, AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity actualResult =
                spyLedgerWriterController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(
                EXCEPTION_MESSAGE_INSUFFICIENT_BALANCE,
                actualResult.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given JWT verifier cannot verify the given bearer token, " +
            "return HTTP Status 401")
    void addTransactionWhenJWTVerificationExceptionThrown() {
        // Given
        when(verifier.verify(TOKEN)).thenThrow(
                JWTVerificationException.class);

        // When
        final ResponseEntity actualResult =
                ledgerWriterController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(ledgerWriterController.UNAUTHORIZED_CODE,
                actualResult.getBody());
        assertEquals(HttpStatus.UNAUTHORIZED, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given exception thrown on validation, return HTTP Status 400")
    void addTransactionWhenIllegalArgumentExceptionThrown() {
        // Given
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);
        doThrow(new IllegalArgumentException(EXCEPTION_MESSAGE)).
                when(transactionValidator).validateTransaction(
                        LOCAL_ROUTING_NUM, AUTHED_ACCOUNT_NUM, transaction);

        // When
        final ResponseEntity actualResult =
                ledgerWriterController.addTransaction(
                BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(EXCEPTION_MESSAGE,
                actualResult.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given HTTP request 'Authorization' header is null, " +
            "return HTTP Status 400")
    void addTransactionWhenBearerTokenNull() {
        // When
        final ResponseEntity actualResult =
                ledgerWriterController.addTransaction(
                        null, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(EXCEPTION_MESSAGE_WHEN_AUTHORIZATION_HEADER_NULL,
                actualResult.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the transaction is internal, check available balance and the balance " +
            "reader throws an error, return HTTP Status 500")
    void addTransactionWhenResourceAccessExceptionThrown(TestInfo testInfo) {
        // Given
        LedgerWriterController spyLedgerWriterController =
                spy(ledgerWriterController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        doThrow(new ResourceAccessException(EXCEPTION_MESSAGE)).when(
                spyLedgerWriterController).getAvailableBalance(
                TOKEN, AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity actualResult =
                spyLedgerWriterController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(EXCEPTION_MESSAGE, actualResult.getBody());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the transaction is external and the transaction cannot be saved to the " +
            "transaction repository, return HTTP Status 500")
    void addTransactionWhenCannotCreateTransactionExceptionExceptionThrown(TestInfo testInfo) {
        // Given
        when(transaction.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        doThrow(new CannotCreateTransactionException(EXCEPTION_MESSAGE)).when(
                transactionRepository).save(transaction);

        // When
        final ResponseEntity actualResult =
                ledgerWriterController.addTransaction(
                        TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(EXCEPTION_MESSAGE, actualResult.getBody());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the transaction is internal, check available balance and the balance " +
            "service returns 500, return HTTP Status 500")
    void addTransactionWhenHttpServerErrorExceptionThrown(TestInfo testInfo) {
        // Given
        LedgerWriterController spyLedgerWriterController =
                spy(ledgerWriterController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        doThrow(new HttpServerErrorException(
                HttpStatus.INTERNAL_SERVER_ERROR)).when(
                        spyLedgerWriterController).getAvailableBalance(
                TOKEN, AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity actualResult =
                spyLedgerWriterController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.toString(),
                actualResult.getBody());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                actualResult.getStatusCode());
    }

    @Test
    @DisplayName("When duplicate UUID transactions are sent, " +
            "second one is rejected with HTTP status 400")
    void addTransactionWhenDuplicateUuidExceptionThrown(TestInfo testInfo) {
        // Given
        LedgerWriterController spyLedgerWriterController =
                spy(ledgerWriterController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromRoutingNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(SMALLER_THAN_SENDER_BALANCE);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        doReturn(SENDER_BALANCE).when(
                spyLedgerWriterController).getAvailableBalance(
                TOKEN, AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity originalResult =
                spyLedgerWriterController.addTransaction(
                        BEARER_TOKEN, transaction);
        final ResponseEntity duplicateResult =
                spyLedgerWriterController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(originalResult);
        assertEquals(ledgerWriterController.READINESS_CODE,
                originalResult.getBody());
        assertEquals(HttpStatus.CREATED, originalResult.getStatusCode());

        assertNotNull(duplicateResult);
        assertEquals(
                EXCEPTION_MESSAGE_DUPLICATE_TRANSACTION,
                duplicateResult.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, duplicateResult.getStatusCode());
    }

    /**
     * Assert the audit event carries every field the standard requires:
     * account, amount, the rule that fired, the configured value it fired
     * against, and a timestamp.
     */
    private void assertReviewAuditEvent(int expectedAmount,
                                        int expectedThreshold) {
        final String event = onlyReviewAuditEvent();
        assertTrue(event.contains("account=" + AUTHED_ACCOUNT_NUM),
                "audit event missing account: " + event);
        assertTrue(event.contains("amountCents=" + expectedAmount),
                "audit event missing amount: " + event);
        assertTrue(event.contains("thresholdCents=" + expectedThreshold),
                "audit event missing configured threshold: " + event);
        assertTrue(event.contains(
                LedgerWriterController.RULE_HIGH_VALUE_TRANSACTION_REVIEW),
                "audit event missing rule: " + event);
        assertTrue(event.matches(".*timestamp=\\S+.*"),
                "audit event missing timestamp: " + event);
        assertTrue(!event.contains(TOKEN),
                "audit event must not contain the bearer token: " + event);
    }

    @Test
    @DisplayName("The held transaction audit event records the account, amount, rule, "
            + "configured threshold and timestamp, and no credentials")
    void heldTransactionAuditEventRecordsRequiredFields() {
        // Given
        final Instant firedAt = Instant.parse("2026-08-16T12:00:00Z");

        // When
        final String event = LedgerWriterController.heldTransactionAuditEvent(
                AUTHED_ACCOUNT_NUM, AT_REVIEW_THRESHOLD,
                TRANSFER_REVIEW_THRESHOLD, firedAt);

        // Then
        assertTrue(event.contains("account=" + AUTHED_ACCOUNT_NUM), event);
        assertTrue(event.contains("amountCents=" + AT_REVIEW_THRESHOLD), event);
        assertTrue(event.contains(
                "thresholdCents=" + TRANSFER_REVIEW_THRESHOLD), event);
        assertTrue(event.contains(
                LedgerWriterController.RULE_HIGH_VALUE_TRANSACTION_REVIEW), event);
        assertTrue(event.contains("timestamp=" + firedAt), event);
        assertTrue(!event.contains(TOKEN), event);
        // The amount is rendered as integer cents, never as dollars.
        assertTrue(!event.contains("10000.00"), event);
    }

    @Test
    @DisplayName("Given an incoming deposit from an external account at the review "
            + "threshold, hold it and return HTTP Status 202 — the control covers "
            + "every transaction on the endpoint, not just outgoing transfers")
    void addTransactionHeldWhenExternalDepositAtReviewThreshold(
            TestInfo testInfo) {
        // Given a transaction whose sender is at another bank, i.e. a deposit
        LedgerWriterController spyLedgerWriterController =
                spy(ledgerWriterController);
        when(transaction.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(AT_REVIEW_THRESHOLD);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());

        // When
        final ResponseEntity actualResult =
                spyLedgerWriterController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(EXCEPTION_MESSAGE_TRANSACTION_UNDER_REVIEW,
                actualResult.getBody());
        assertEquals(HttpStatus.ACCEPTED, actualResult.getStatusCode());
        verify(transactionRepository, never()).save(any());
        assertReviewAuditEvent(AT_REVIEW_THRESHOLD, TRANSFER_REVIEW_THRESHOLD);
    }

    @Test
    @DisplayName("Given a local outgoing transfer at the review threshold, hold it "
            + "and return HTTP Status 202 without consulting the balance service")
    void addTransactionHeldWhenLocalTransferAtReviewThreshold(
            TestInfo testInfo) {
        // Given a transaction whose sender is at this bank
        LedgerWriterController spyLedgerWriterController =
                spy(ledgerWriterController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(AT_REVIEW_THRESHOLD);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());

        // When
        final ResponseEntity actualResult =
                spyLedgerWriterController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(EXCEPTION_MESSAGE_TRANSACTION_UNDER_REVIEW,
                actualResult.getBody());
        assertEquals(HttpStatus.ACCEPTED, actualResult.getStatusCode());
        verify(transactionRepository, never()).save(any());
        // The hold precedes the balance lookup, so balancereader is not called
        // for a transaction that will not be written.
        verify(spyLedgerWriterController, never()).getAvailableBalance(
                TOKEN, AUTHED_ACCOUNT_NUM);
        assertReviewAuditEvent(AT_REVIEW_THRESHOLD, TRANSFER_REVIEW_THRESHOLD);
    }

    @Test
    @DisplayName("Given the transaction amount is exactly at the review threshold, "
            + "hold it and return HTTP Status 202")
    void addTransactionHeldWhenAmountAtReviewThreshold(TestInfo testInfo) {
        // Given
        when(transaction.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(AT_REVIEW_THRESHOLD);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());

        // When
        final ResponseEntity actualResult =
                ledgerWriterController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(EXCEPTION_MESSAGE_TRANSACTION_UNDER_REVIEW,
                actualResult.getBody());
        assertEquals(HttpStatus.ACCEPTED, actualResult.getStatusCode());
        verify(transactionRepository, never()).save(any());
        assertReviewAuditEvent(AT_REVIEW_THRESHOLD, TRANSFER_REVIEW_THRESHOLD);
    }

    @Test
    @DisplayName("Given the transaction amount is above the review threshold, "
            + "hold it and return HTTP Status 202")
    void addTransactionHeldWhenAmountAboveReviewThreshold(TestInfo testInfo) {
        // Given
        when(transaction.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(ABOVE_REVIEW_THRESHOLD);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());

        // When
        final ResponseEntity actualResult =
                ledgerWriterController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(EXCEPTION_MESSAGE_TRANSACTION_UNDER_REVIEW,
                actualResult.getBody());
        assertEquals(HttpStatus.ACCEPTED, actualResult.getStatusCode());
        verify(transactionRepository, never()).save(any());
        assertReviewAuditEvent(ABOVE_REVIEW_THRESHOLD,
                TRANSFER_REVIEW_THRESHOLD);
    }

    @Test
    @DisplayName("Given the transaction amount is just below the review threshold, "
            + "write it and return HTTP Status 201")
    void addTransactionWrittenWhenAmountBelowReviewThreshold(TestInfo testInfo) {
        // Given
        when(transaction.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(BELOW_REVIEW_THRESHOLD);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());

        // When
        final ResponseEntity actualResult =
                ledgerWriterController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(ledgerWriterController.READINESS_CODE,
                actualResult.getBody());
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
        verify(transactionRepository).save(transaction);
        assertNoReviewAuditEvent();
    }

    /**
     * The @Value expression declared for the review threshold on the
     * controller constructor, read from the annotation itself so that a
     * change to the declared default is visible to the test.
     */
    private static String declaredThresholdExpression() throws Exception {
        final Constructor<LedgerWriterController> constructor =
                LedgerWriterController.class.getConstructor(
                        JWTVerifier.class,
                        StackdriverMeterRegistry.class,
                        TransactionRepository.class,
                        TransactionValidator.class,
                        String.class,
                        String.class,
                        String.class,
                        int.class);
        for (Annotation[] onParameter
                : constructor.getParameterAnnotations()) {
            for (Annotation annotation : onParameter) {
                if (annotation instanceof Value) {
                    final String expression = ((Value) annotation).value();
                    if (expression.contains("TRANSFER_REVIEW_THRESHOLD")) {
                        return expression;
                    }
                }
            }
        }
        return fail("no @Value declaring TRANSFER_REVIEW_THRESHOLD on the "
                + "LedgerWriterController constructor");
    }

    @Test
    @DisplayName("Given TRANSFER_REVIEW_THRESHOLD is absent from the environment, the "
            + "declared default resolves to 1000000 cents and the transaction is held "
            + "with HTTP Status 202")
    void addTransactionHeldWhenThresholdConfigAbsent(TestInfo testInfo)
            throws Exception {
        // Given an environment in which TRANSFER_REVIEW_THRESHOLD is absent.
        final StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        assertNull(environment.getProperty("TRANSFER_REVIEW_THRESHOLD"));

        // When the declared @Value expression is resolved against it, the
        // documented default applies. Resolving the real annotation means
        // removing or changing the default fails this test.
        final int resolvedThreshold = Integer.parseInt(
                environment.resolveRequiredPlaceholders(
                        declaredThresholdExpression()));

        // Then
        assertEquals(TRANSFER_REVIEW_THRESHOLD, resolvedThreshold,
                "the documented default of TRANSFER_REVIEW_THRESHOLD changed");

        // And a controller built from that resolved value holds at the
        // default boundary.
        final LedgerWriterController defaultedController =
                newController(resolvedThreshold);
        when(transaction.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(AT_REVIEW_THRESHOLD);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());

        // When
        final ResponseEntity actualResult =
                defaultedController.addTransaction(BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(EXCEPTION_MESSAGE_TRANSACTION_UNDER_REVIEW,
                actualResult.getBody());
        assertEquals(HttpStatus.ACCEPTED, actualResult.getStatusCode());
        verify(transactionRepository, never()).save(any());
        assertReviewAuditEvent(AT_REVIEW_THRESHOLD, resolvedThreshold);
    }

    @Test
    @DisplayName("Given the transaction is below the review threshold, the insufficient "
            + "balance behaviour is unchanged and returns HTTP Status 400")
    void addTransactionUnaffectedWhenBelowReviewThreshold(TestInfo testInfo) {
        // Given
        LedgerWriterController spyLedgerWriterController =
                spy(ledgerWriterController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(LARGER_THAN_SENDER_BALANCE);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        doReturn(SENDER_BALANCE).when(
                spyLedgerWriterController).getAvailableBalance(
                TOKEN, AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity actualResult =
                spyLedgerWriterController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(EXCEPTION_MESSAGE_INSUFFICIENT_BALANCE,
                actualResult.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, actualResult.getStatusCode());
        verify(transactionRepository, never()).save(any());
        assertNoReviewAuditEvent();
    }
}
