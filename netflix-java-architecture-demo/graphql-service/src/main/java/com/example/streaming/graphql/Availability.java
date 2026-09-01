package com.example.streaming.graphql;

import java.util.List;

public record Availability(List<String> regions, String streamingSince) {}
