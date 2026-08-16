package com.streamflix.core;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/alarms")
public class AlarmResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, AlarmStore.Alarm> getAlarms() {
        return AlarmStore.INSTANCE.getActiveAlarms();
    }
}
