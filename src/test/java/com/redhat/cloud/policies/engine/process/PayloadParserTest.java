package com.redhat.cloud.policies.engine.process;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.redhat.cloud.policies.engine.process.PayloadParser.CATEGORY_NAME;
import static com.redhat.cloud.policies.engine.process.PayloadParser.CHECK_IN_FIELD;
import static com.redhat.cloud.policies.engine.process.PayloadParser.DISPLAY_NAME_FIELD;
import static com.redhat.cloud.policies.engine.process.PayloadParser.FQDN_NAME_FIELD;
import static com.redhat.cloud.policies.engine.process.PayloadParser.HOST_FIELD;
import static com.redhat.cloud.policies.engine.process.PayloadParser.HOST_ID;
import static com.redhat.cloud.policies.engine.process.PayloadParser.INVENTORY_ID_FIELD;
import static com.redhat.cloud.policies.engine.process.PayloadParser.NETWORK_INTERFACES_FIELD;
import static com.redhat.cloud.policies.engine.process.PayloadParser.YUM_REPOS_FIELD;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PayloadParserTest {

    @Inject
    PayloadParser payloadParser;

    @Inject
    MeterRegistry meterRegistry;

    Counter incomingMessagesCount;
    Counter rejectedCount;
    Counter rejectedCountType;
    Counter rejectedCountHost;
    Counter rejectedCountReporter;
    Counter rejectedCountId;
    Counter processingErrors;

    @PostConstruct
    void postConstruct() {
        incomingMessagesCount = meterRegistry.counter("engine.input.processed", "queue", "host-egress");
        rejectedCount = meterRegistry.counter("engine.input.rejected", "queue", "host-egress");
        rejectedCountType = meterRegistry.counter("engine.input.rejected.detail", "queue", "host-egress", "reason", "type");
        rejectedCountHost = meterRegistry.counter("engine.input.rejected.detail", "queue", "host-egress", "reason", "noHost");
        rejectedCountReporter = meterRegistry.counter("engine.input.rejected.detail", "queue", "host-egress", "reason", "reporter");
        rejectedCountId = meterRegistry.counter("engine.input.rejected.detail", "queue", "host-egress", "reason", "insightsId");
        processingErrors = meterRegistry.counter("engine.input.processed.errors", "queue", "host-egress");
    }

    private static class CounterExpectations {
        long incomingMessages;
        long rejected;
        long rejectedType;
        long rejectedHost;
        long rejectedReporter;
        long rejectedId;
        long processingErrors;
    }

    private final Map<Counter, Double> counterValues = new HashMap<>();

    private void saveCounterValues(Counter... counters) {
        for (Counter counter : counters) {
            counterValues.put(counter, counter.count());
        }
    }

    private void checkCounterValues(CounterExpectations counterExpectations) {
        assertEquals(counterExpectations.incomingMessages, getCounterIncrease(incomingMessagesCount), "Unexpected count: incoming messages");
        assertEquals(counterExpectations.rejected, getCounterIncrease(rejectedCount), "Unexpected count: rejected messages");
        assertEquals(counterExpectations.rejectedType, getCounterIncrease(rejectedCountType), "Unexpected count: rejected messages (type)");
        assertEquals(counterExpectations.rejectedHost, getCounterIncrease(rejectedCountHost), "Unexpected count: rejected messages (host)");
        assertEquals(counterExpectations.rejectedReporter, getCounterIncrease(rejectedCountReporter), "Unexpected count: rejected messages (reporter)");
        assertEquals(counterExpectations.rejectedId, getCounterIncrease(rejectedCountId), "Unexpected count: rejected messages (id)");
        assertEquals(counterExpectations.processingErrors, getCounterIncrease(processingErrors), "Unexpected count: processing errors");
    }

    private double getCounterIncrease(Counter counter) {
        return counter.count() - counterValues.getOrDefault(counter, 0D);
    }

    @BeforeEach
    void beforeEach() {
        saveCounterValues(incomingMessagesCount, rejectedCount, rejectedCountType, rejectedCountHost, rejectedCountReporter, rejectedCountId, processingErrors);
    }

    @Test
    void testValidSimpleJson() {
        String payload = loadResource("input/host.json");
        Event event = payloadParser.parse(payload).get();

        assertEquals("integration-test-account", event.getAccountId());
        assertEquals("integration-test-org-id", event.getOrgId());
        assertNotNull(event.getId());
        assertEquals(CATEGORY_NAME, event.getCategory());
        assertTrue(event.getText().startsWith("host-egress report"));
        assertEquals(2, event.getTotalTagsSize());
        assertEquals("VM", event.getTags(DISPLAY_NAME_FIELD).iterator().next().value);
        assertTrue(event.getTags(DISPLAY_NAME_FIELD).iterator().next().isSynthetic);
        assertEquals("ba11a21a-8b22-431b-9b4b-b06006472d54", event.getTags(INVENTORY_ID_FIELD).iterator().next().value);
        assertTrue(event.getTags(INVENTORY_ID_FIELD).iterator().next().isSynthetic);
        assertEquals(2, event.getContext().size());
        assertEquals("ba11a21a-8b22-431b-9b4b-b06006472d54", event.getContext().get(INVENTORY_ID_FIELD));
        assertEquals("2020-04-16T16:10:42.199046+00:00", event.getContext().get(CHECK_IN_FIELD));
        assertNull(event.getFacts().get(FQDN_NAME_FIELD));
        assertNotNull(event.getFacts().get(NETWORK_INTERFACES_FIELD));
        assertNotNull(event.getFacts().get(YUM_REPOS_FIELD));

        CounterExpectations expectations = new CounterExpectations();
        expectations.incomingMessages = 1;
        checkCounterValues(expectations);
    }

    @Test
    void testValidComplexJson() {
        String payload = loadResource("input/thomas-host.json");
        Event event = payloadParser.parse(payload).get();

        assertEquals("integration-test-account", event.getAccountId());
        assertEquals("integration-test-org-id", event.getOrgId());
        assertNotNull(event.getId());
        assertEquals(CATEGORY_NAME, event.getCategory());
        assertTrue(event.getText().startsWith("host-egress report"));
        assertEquals(7, event.getTotalTagsSize());
        assertEquals("Thomas RHEL8 VM", event.getTags(DISPLAY_NAME_FIELD).iterator().next().value);
        assertTrue(event.getTags(DISPLAY_NAME_FIELD).iterator().next().isSynthetic);
        assertEquals("77fa88f0-5afd-4301-a87d-1b516eded11e", event.getTags(INVENTORY_ID_FIELD).iterator().next().value);
        assertTrue(event.getTags(INVENTORY_ID_FIELD).iterator().next().isSynthetic);
        assertEquals("Thomas Heute", event.getTags("owner").iterator().next().value);
        assertFalse(event.getTags("owner").iterator().next().isSynthetic);
        assertEquals("spam@redhat.com", event.getTags("contact").iterator().next().value);
        assertFalse(event.getTags("contact").iterator().next().isSynthetic);
        assertTrue(event.getTags("location").stream().map(t -> t.value).collect(Collectors.toList()).containsAll(List.of("Neuchatel", "Charmey", "Gruchet le Valasse")));
        event.getTags("location").forEach(tagContent -> assertFalse(tagContent.isSynthetic));
        assertEquals(2, event.getContext().size());
        assertEquals("77fa88f0-5afd-4301-a87d-1b516eded11e", event.getContext().get(INVENTORY_ID_FIELD));
        assertEquals("2020-04-16T16:10:42.199046+00:00", event.getContext().get(CHECK_IN_FIELD));
        assertEquals("rhel80", event.getFacts().get(FQDN_NAME_FIELD));
        assertNotNull(event.getFacts().get(NETWORK_INTERFACES_FIELD));
        assertNotNull(event.getFacts().get(YUM_REPOS_FIELD));

        CounterExpectations expectations = new CounterExpectations();
        expectations.incomingMessages = 1;
        checkCounterValues(expectations);
    }

    @Test
    void testInvalidJson() {
        Optional<Event> event = payloadParser.parse("I am not a valid JSON!");
        assertTrue(event.isEmpty());

        CounterExpectations expectations = new CounterExpectations();
        expectations.incomingMessages = 1;
        expectations.processingErrors = 1;
        checkCounterValues(expectations);
    }

    @Test
    void testWrongType() {
        String payload = loadResource("input/host.json");

        // Let's replace the allowed type with a wrong one.
        JsonObject jsonObject = new JsonObject(payload);
        jsonObject.put("type", "deleted");
        payload = jsonObject.encode();

        Optional<Event> event = payloadParser.parse(payload);
        assertTrue(event.isEmpty());

        CounterExpectations expectations = new CounterExpectations();
        expectations.incomingMessages = 1;
        expectations.rejected = 1;
        expectations.rejectedType = 1;
        checkCounterValues(expectations);
    }

    @Test
    void testNoHost() {
        String payload = loadResource("input/host.json");

        // Let's remove the host field from the payload.
        JsonObject jsonObject = new JsonObject(payload);
        jsonObject.remove(HOST_FIELD);
        payload = jsonObject.encode();

        Optional<Event> event = payloadParser.parse(payload);
        assertTrue(event.isEmpty());

        CounterExpectations expectations = new CounterExpectations();
        expectations.incomingMessages = 1;
        expectations.rejected = 1;
        expectations.rejectedHost = 1;
        checkCounterValues(expectations);
    }

    @Test
    void testWrongReporter() {
        String payload = loadResource("input/host.json");

        // Let's replace the allowed reporter with a wrong one.
        JsonObject jsonObject = new JsonObject(payload);
        jsonObject.getJsonObject("host").put("reporter", "rhsm-conduit");
        payload = jsonObject.encode();

        Optional<Event> event = payloadParser.parse(payload);
        assertTrue(event.isEmpty());

        CounterExpectations expectations = new CounterExpectations();
        expectations.incomingMessages = 1;
        expectations.rejected = 1;
        expectations.rejectedReporter = 1;
        checkCounterValues(expectations);
    }

    @Test
    void testNoHostId() {
        String payload = loadResource("input/host.json");

        // Let's remove the host ID field from the payload.
        JsonObject jsonObject = new JsonObject(payload);
        jsonObject.getJsonObject(HOST_FIELD).remove(HOST_ID);
        payload = jsonObject.encode();

        Optional<Event> event = payloadParser.parse(payload);
        assertTrue(event.isEmpty());

        CounterExpectations expectations = new CounterExpectations();
        expectations.incomingMessages = 1;
        expectations.rejected = 1;
        expectations.rejectedId = 1;
        checkCounterValues(expectations);
    }

    private String loadResource(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            return IOUtils.toString(is, UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
