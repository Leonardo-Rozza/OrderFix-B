package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.http;

import java.time.Duration;
import java.util.*;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.http.WorkshopClosureHttpRequests.Operation;

/** Local actor quotas survive session replacement. A full map never evicts a live restriction. */
final class WorkshopClosureHttpRateLimit {
    private final int capacity;
    private final Map<Long,EnumMap<Operation,ArrayDeque<Long>>> actors=new HashMap<>();
    WorkshopClosureHttpRateLimit(int capacity) { if(capacity<1) throw new IllegalArgumentException(); this.capacity=capacity; }
    synchronized void acquire(long actor,Operation operation,long now) {
        var windows=actors.get(actor);
        if(windows==null) {
            if(actors.size()>=capacity) {
                actors.entrySet().removeIf(entry->{prune(entry.getValue(),now);return entry.getValue().isEmpty();});
                if(actors.size()>=capacity) throw WorkshopClosureHttpException.limited(60);
            }
            windows=new EnumMap<>(Operation.class); actors.put(actor,windows);
        }
        prune(windows,now);
        var timestamps=windows.computeIfAbsent(operation,ignored->new ArrayDeque<>());
        if(timestamps.size()>=(operation==Operation.READ?30:5)) {
            long remaining=window(operation)-(now-timestamps.getFirst());
            throw WorkshopClosureHttpException.limited(Math.max(1,(remaining+999_999_999L)/1_000_000_000L));
        }
        timestamps.addLast(now);
    }
    private static void prune(EnumMap<Operation,ArrayDeque<Long>> windows,long now) {
        windows.entrySet().removeIf(entry->{var timestamps=entry.getValue();
            while(!timestamps.isEmpty() && now-timestamps.getFirst()>=window(entry.getKey())) timestamps.removeFirst();
            return timestamps.isEmpty();});
    }
    private static long window(Operation operation) { return Duration.ofMinutes(operation==Operation.READ?1:15).toNanos(); }
}
