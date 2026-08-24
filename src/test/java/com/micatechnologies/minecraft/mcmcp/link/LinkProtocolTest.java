package com.micatechnologies.minecraft.mcmcp.link;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Which rejections are worth retrying, and what the log says about each. */
class LinkProtocolTest {

    @Test
    void rejectionsThatOnlyAHumanCanClearAreNotRetried() {
        assertFalse(LinkProtocol.isRetryable(LinkProtocol.REASON_SECRET_MISMATCH));
        assertFalse(LinkProtocol.isRetryable(LinkProtocol.REASON_REVOKED));
        assertFalse(LinkProtocol.isRetryable(LinkProtocol.REASON_UNSUPPORTED_PROTOCOL));
        assertFalse(LinkProtocol.isRetryable(LinkProtocol.REASON_MALFORMED_HELLO));
    }

    @Test
    void waitingForApprovalIsRetried() {
        assertTrue(LinkProtocol.isRetryable(LinkProtocol.REASON_PENDING_APPROVAL));
    }

    @Test
    void anUnrecognisedReasonIsRetriedRatherThanTreatedAsFatal() {
        // A newer orchestrator inventing a reason this build has never heard of is far likelier
        // than a genuinely fatal condition, and the two mistakes cost very different amounts:
        // retrying a fatal rejection is a log line every thirty seconds, while giving up on a
        // transient one strands the instance until the game restarts.
        assertTrue(LinkProtocol.isRetryable("some-future-reason"));
        assertTrue(LinkProtocol.isRetryable(null));
    }

    @Test
    void everyKnownReasonExplainsItselfWithoutRepeatingTheRawCode() {
        // These sentences go straight into latest.log, which for most people is the only place this
        // failure is ever visible. "rejected: secret-mismatch" tells them nothing they can act on.
        String mismatch = LinkProtocol.explain(LinkProtocol.REASON_SECRET_MISMATCH, "");
        assertTrue(mismatch.contains("instanceSecret"), "names the config key to look at");
        assertTrue(mismatch.contains("Not retrying"), "says the link has given up");

        String pending = LinkProtocol.explain(LinkProtocol.REASON_PENDING_APPROVAL, "");
        assertTrue(pending.contains("approved"));
        assertFalse(pending.contains("Not retrying"), "waiting for approval is not giving up");
    }

    @Test
    void anOrchestratorsOwnMessageIsCarriedThroughToTheLog() {
        String explained = LinkProtocol.explain(LinkProtocol.REASON_REVOKED, "revoked by alex at 14:02");

        assertTrue(explained.contains("revoked by alex at 14:02"));
    }

    @Test
    void anUnknownReasonStillProducesAUsableSentence() {
        String explained = LinkProtocol.explain("some-future-reason", "details here");

        assertTrue(explained.contains("some-future-reason"));
        assertTrue(explained.contains("details here"));
    }
}
