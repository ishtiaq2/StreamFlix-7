package com.streamflix.core;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/events")
public class EventResource {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public String receiveEvent(Map<String, String> event) {
        String uei = event.get("uei");
        String streamId = event.get("streamId");
        String message = event.getOrDefault("message", "");

        AlarmStore.INSTANCE.recordEvent(uei, streamId, message);
        return "{\"status\":\"accepted\"}";
    }
}
