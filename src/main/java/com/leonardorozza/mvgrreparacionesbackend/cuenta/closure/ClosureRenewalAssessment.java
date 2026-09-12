package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import java.util.List;

/**
 * Conservative local evidence classification, not confirmation that a provider stopped billing.
 * The caller supplies normalized metadata from one consistent snapshot, including historical links.
 * No provider identifier, credential, URL, email or enablement flag enters this contract.
 */
public final class ClosureRenewalAssessment {
    private static final int MAX_LINKS = 1_000;

    private ClosureRenewalAssessment() { }

    public enum Result {
        /** No local indication was found. This does not assert absence of a remote subscription. */
        NO_LOCAL_EVIDENCE,
        /** Local provider history exists, including canceled history; coordinate its disposition. */
        PROVIDER_COORDINATION_REQUIRED,
        /** Incomplete, unresolved or inconsistent evidence requires review before taking an effect. */
        UNCERTAIN
    }

    public enum Plan { FREE, PRO, UNKNOWN }

    /** The SQL mapper normalizes canceled/cancelled to CANCELED and absent/blank status to NONE. */
    public enum ProviderState { NONE, AUTHORIZED, PENDING, PAUSED, CANCELED, CREATING, RETRYABLE, UNKNOWN }

    /** Other evidence includes references, checkout/payer metadata and known billing dates/identifiers. */
    public record SubscriptionEvidence(boolean present, Plan plan, ProviderState state,
                                       boolean hasExternalSubscriptionId, boolean hasOtherProviderEvidence) { }

    /** An existing link row is itself evidence, even when none of its identifying fields is populated. */
    public record LinkEvidence(boolean current, ProviderState state,
                               boolean hasExternalSubscriptionId, boolean hasOtherProviderEvidence) { }

    public static Result assess(SubscriptionEvidence subscription, List<LinkEvidence> links) {
        if (subscription == null || !subscription.present() || subscription.plan() == null
                || subscription.plan() == Plan.UNKNOWN || subscription.state() == null
                || links == null || links.size() > MAX_LINKS) {
            return Result.UNCERTAIN;
        }
        if (unresolved(subscription.state())) return Result.UNCERTAIN;
        if (subscription.state() == ProviderState.NONE
                && (subscription.hasExternalSubscriptionId() || subscription.hasOtherProviderEvidence())) {
            return Result.UNCERTAIN;
        }
        if (requiresRemoteIdentity(subscription.state()) && !subscription.hasExternalSubscriptionId()) {
            return Result.UNCERTAIN;
        }
        // Canceled history cannot explain a PRO entitlement whose current commercial state is absent.
        if (subscription.plan() == Plan.PRO && (links.isEmpty() || subscription.state() == ProviderState.NONE)) {
            return Result.UNCERTAIN;
        }

        int current = 0;
        for (LinkEvidence link : links) {
            if (link == null || link.state() == null || unresolved(link.state()) || link.state() == ProviderState.NONE) {
                return Result.UNCERTAIN;
            }
            if (link.current() && ++current > 1) return Result.UNCERTAIN;
            // An old active/pending/paused link cannot be dismissed merely because it is not current locally.
            if (!link.current() && link.state() != ProviderState.CANCELED) return Result.UNCERTAIN;
            if (requiresRemoteIdentity(link.state()) && !link.hasExternalSubscriptionId()) return Result.UNCERTAIN;
        }

        if (!links.isEmpty() || subscription.state() != ProviderState.NONE
                || subscription.hasExternalSubscriptionId() || subscription.hasOtherProviderEvidence()) {
            return Result.PROVIDER_COORDINATION_REQUIRED;
        }
        return Result.NO_LOCAL_EVIDENCE;
    }

    private static boolean unresolved(ProviderState state) {
        return state == ProviderState.UNKNOWN || state == ProviderState.CREATING || state == ProviderState.RETRYABLE;
    }

    private static boolean requiresRemoteIdentity(ProviderState state) {
        return state == ProviderState.AUTHORIZED || state == ProviderState.PENDING || state == ProviderState.PAUSED;
    }
}
