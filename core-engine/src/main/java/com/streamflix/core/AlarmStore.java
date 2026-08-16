package com.streamflix.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AlarmStore {
    public static final AlarmStore INSTANCE = new AlarmStore();

    // key = reduction key (uei + streamId) -- same concept as a monitoring
    // system's reduction-key: it's how repeated events collapse into one alarm.
    private final Map<String, Alarm> alarms = new ConcurrentHashMap<>();

    public void recordEvent(String uei, String streamId, String message) {
        String reductionKey = uei + ":" + streamId;

        alarms.compute(reductionKey, (key, existing) -> {
            if (uei.endsWith("Recovered")) {
                // "recovered" events clear the matching "down" alarm --
                // same pairing mechanic as a clear-key.
                if (existing != null) {
                    existing.cleared = true;
                }
                return existing;
            }
            if (existing == null || existing.cleared) {
                return new Alarm(reductionKey, uei, streamId, message);
            }
            existing.occurrenceCount++;
            existing.lastMessage = message;
            return existing;
        });
    }

    public Map<String, Alarm> getActiveAlarms() {
        Map<String, Alarm> active = new ConcurrentHashMap<>();
        alarms.forEach((k, v) -> { if (!v.cleared) active.put(k, v); });
        return active;
    }

    public static class Alarm {
        public String reductionKey;
        public String uei;
        public String streamId;
        public String lastMessage;
        public int occurrenceCount = 1;
        public boolean cleared = false;

        public Alarm(String reductionKey, String uei, String streamId, String message) {
            this.reductionKey = reductionKey;
            this.uei = uei;
            this.streamId = streamId;
            this.lastMessage = message;
        }
    }
}
