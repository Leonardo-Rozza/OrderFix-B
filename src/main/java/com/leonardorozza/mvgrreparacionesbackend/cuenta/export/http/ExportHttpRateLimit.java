package com.leonardorozza.mvgrreparacionesbackend.cuenta.export.http;

import java.time.Duration;
import java.util.*;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.export.http.ExportHttpRequests.Operation;

/** Local monotonic quotas keyed by authenticated actor; a full map never evicts live limits. */
final class ExportHttpRateLimit {
    private final int maxActors;
    private final Map<Long,EnumMap<Operation,ArrayDeque<Long>>> actors=new HashMap<>();
    ExportHttpRateLimit(int maxActors) { if(maxActors<1) throw new IllegalArgumentException(); this.maxActors=maxActors; }
    synchronized void acquire(long actor,Operation operation,long now) {
        if(operation==Operation.RETIRED) return;
        var windows=actors.get(actor);
        if(windows==null) {
            if(actors.size()>=maxActors) {
                actors.entrySet().removeIf(entry->{ prune(entry.getValue(),now); return entry.getValue().isEmpty(); });
                if(actors.size()>=maxActors) throw ExportHttpException.limited(60);
            }
            windows=new EnumMap<>(Operation.class); actors.put(actor,windows);
        }
        prune(windows,now);
        var timestamps=windows.computeIfAbsent(operation,ignored->new ArrayDeque<>());
        if(timestamps.size()>=limit(operation)) {
            long wait=timestamps.getFirst()+window(operation)-now;
            throw ExportHttpException.limited(Math.max(1,(wait+999_999_999L)/1_000_000_000L));
        }
        timestamps.addLast(now);
    }
    private static void prune(EnumMap<Operation,ArrayDeque<Long>> windows,long now) {
        windows.entrySet().removeIf(entry->{
            var values=entry.getValue(); long cutoff=now-window(entry.getKey());
            while(!values.isEmpty() && values.getFirst()<=cutoff) values.removeFirst();
            return values.isEmpty();
        });
    }
    private static long window(Operation op) { return Duration.ofMinutes(op==Operation.READ?1:15).toNanos(); }
    private static int limit(Operation op) { return switch(op) {case ISSUE->5;case REQUEST,DOWNLOAD->3;case READ->30;case RETIRED->0;}; }
}
