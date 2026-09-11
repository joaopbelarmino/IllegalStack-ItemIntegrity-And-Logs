package main.java.me.dniym.identity.presence;

import main.java.me.dniym.identity.ItemIdentity;
import java.util.Optional;

public final class DestinationTrackingStore implements PresenceStore {
    private final PresenceStore delegate;
    private final DestinationWindow destinations;
    public DestinationTrackingStore(PresenceStore delegate, DestinationWindow destinations) {
        this.delegate = delegate; this.destinations = destinations;
    }
    public DestinationWindow destinations() { return destinations; }
    @Override public Optional<PresenceRecord> getCanonical(ItemIdentity id) { return delegate.getCanonical(id); }
    @Override public Optional<PresenceRecord> getCanonical(String id) { return delegate.getCanonical(id); }
    @Override public Optional<PresenceRecord> getLastDivergentObservation(ItemIdentity id) { return delegate.getLastDivergentObservation(id); }
    @Override public Optional<PresenceRecord> getLastDivergentObservation(String id) { return delegate.getLastDivergentObservation(id); }
    @Override public PresenceObservation observe(ItemIdentity id, HolderRef holder, PresenceState state) {
        destinations.observe(id.id(), holder);
        return delegate.observe(id, holder, state);
    }
    @Override public PresenceRecord commitHandoff(ItemIdentity id, HolderRef holder, PresenceState state) {
        destinations.observe(id.id(), holder);
        return delegate.commitHandoff(id, holder, state);
    }
}
