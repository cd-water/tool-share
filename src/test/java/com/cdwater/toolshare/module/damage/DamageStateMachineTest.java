package com.cdwater.toolshare.module.damage;

import com.cdwater.toolshare.shared.enums.ReportStatus;
import com.cdwater.toolshare.shared.web.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DamageStateMachineTest {

    @Test
    void allows_only_the_documented_transitions() {
        assertDoesNotThrow(() -> DamageStateMachine.assertTransition(
                ReportStatus.SUBMITTED, ReportStatus.VERIFIED));
        assertDoesNotThrow(() -> DamageStateMachine.assertTransition(
                ReportStatus.SUBMITTED, ReportStatus.REJECTED));
        assertDoesNotThrow(() -> DamageStateMachine.assertTransition(
                ReportStatus.VERIFIED, ReportStatus.REPAIRING));
        assertDoesNotThrow(() -> DamageStateMachine.assertTransition(
                ReportStatus.REPAIRING, ReportStatus.COMPLETED));
        assertDoesNotThrow(() -> DamageStateMachine.assertTransition(
                ReportStatus.REPAIRING, ReportStatus.SCRAPPED));
    }

    @Test
    void rejects_illegal_transitions_with_conflict() {
        assertConflict(ReportStatus.SUBMITTED, ReportStatus.REPAIRING);
        assertConflict(ReportStatus.SUBMITTED, ReportStatus.COMPLETED);
        assertConflict(ReportStatus.VERIFIED, ReportStatus.COMPLETED);
        assertConflict(ReportStatus.VERIFIED, ReportStatus.REJECTED);
        assertConflict(ReportStatus.REPAIRING, ReportStatus.VERIFIED);
        assertConflict(ReportStatus.REPAIRING, ReportStatus.REJECTED);
    }

    @Test
    void terminal_states_never_transition_again() {
        for (ReportStatus terminal : new ReportStatus[]{
                ReportStatus.COMPLETED, ReportStatus.REJECTED, ReportStatus.SCRAPPED}) {
            for (ReportStatus target : ReportStatus.values()) {
                if (target == terminal) {
                    continue;
                }
                assertConflict(terminal, target);
            }
        }
    }

    private void assertConflict(ReportStatus from, ReportStatus to) {
        BizException error = assertThrows(BizException.class,
                () -> DamageStateMachine.assertTransition(from, to),
                from + " -> " + to);
        assertEquals("C409", error.getCode(), from + " -> " + to);
    }
}
