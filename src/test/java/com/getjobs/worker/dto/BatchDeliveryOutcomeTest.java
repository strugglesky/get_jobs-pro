package com.getjobs.worker.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BatchDeliveryOutcomeTest {
    @Test
    void reportsFullBatchResultsWithoutInferringIndividualResultsForPartialBatches() {
        assertEquals(DeliveryNotification.Status.SUCCESS,
                new BatchDeliveryOutcome(5, 0).statusFor(5));
        assertEquals(DeliveryNotification.Status.FAILED,
                new BatchDeliveryOutcome(0, 5).statusFor(5));
        assertEquals(DeliveryNotification.Status.UNCONFIRMED,
                new BatchDeliveryOutcome(2, 3).statusFor(5));
    }
}
