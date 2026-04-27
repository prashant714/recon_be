package com.reconciliation.reconciliation;

import com.reconciliation.reconciliation.rules.ReconciliationRule;
import com.reconciliation.reconciliation.service.ReconciliationEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReconciliationEngineTest {

    @Test
    void runsAllRulesEvenIfOneFails() {
        ReconciliationRule firstRule = mock(ReconciliationRule.class);
        ReconciliationRule secondRule = mock(ReconciliationRule.class);

        org.mockito.Mockito.when(firstRule.getName()).thenReturn("first-rule");
        org.mockito.Mockito.when(secondRule.getName()).thenReturn("second-rule");
        doThrow(new RuntimeException("boom")).when(firstRule).evaluate();

        ReconciliationEngine engine = new ReconciliationEngine(
                List.of(firstRule, secondRule),
                new SimpleMeterRegistry()
        );

        engine.runAll();

        verify(firstRule).evaluate();
        verify(secondRule).evaluate();
    }
}
