package com.example.streaming.catalog;

import com.example.streaming.catalog.grpc.*;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.List;
import java.util.Map;

/**
 * The gRPC side of "think methods, not data" — two narrow RPCs
 * (GetShow, GetAvailability) rather than a generic resource-shaped API.
 * graphql-service's data fetchers call into this over gRPC to resolve
 * the fields schema.graphqls declares — the "gRPC behind GraphQL" shape
 * the video describes as their actual internal pattern, as opposed to
 * exposing gRPC directly to UI clients.
 *
 * In-memory data on purpose — this repo demonstrates the wiring/
 * architecture, not a real catalog store.
 */
@GrpcService
public class CatalogServiceImpl extends CatalogServiceGrpc.CatalogServiceImplBase {

  private static final Map<String, ShowResponse> CATALOG = Map.of(
      "show-1", ShowResponse.newBuilder()
          .setId("show-1")
          .setTitle("Signal Loss")
          .setReleaseYear(2025)
          .addAllGenres(List.of("drama", "thriller"))
          .build(),
      "show-2", ShowResponse.newBuilder()
          .setId("show-2")
          .setTitle("The Long Uplink")
          .setReleaseYear(2026)
          .addAllGenres(List.of("sci-fi"))
          .build()
  );

  private static final Map<String, AvailabilityResponse> AVAILABILITY = Map.of(
      "show-1", AvailabilityResponse.newBuilder()
          .addAllRegions(List.of("US", "CA", "UK"))
          .setStreamingSince("2025-11-01")
          .build(),
      "show-2", AvailabilityResponse.newBuilder()
          .addAllRegions(List.of("US"))
          .setStreamingSince("2026-02-14")
          .build()
  );

  @Override
  public void getShow(GetShowRequest request, StreamObserver<ShowResponse> responseObserver) {
    ShowResponse show = CATALOG.get(request.getShowId());
    if (show == null) {
      responseObserver.onError(
          io.grpc.Status.NOT_FOUND.withDescription("no show: " + request.getShowId()).asRuntimeException());
      return;
    }
    responseObserver.onNext(show);
    responseObserver.onCompleted();
  }

  @Override
  public void getAvailability(GetAvailabilityRequest request, StreamObserver<AvailabilityResponse> responseObserver) {
    AvailabilityResponse availability = AVAILABILITY.getOrDefault(
        request.getShowId(),
        AvailabilityResponse.newBuilder().build() // no regions = not currently available, not an error
    );
    responseObserver.onNext(availability);
    responseObserver.onCompleted();
  }

  @Override
  public void listShows(ListShowsRequest request, StreamObserver<ListShowsResponse> responseObserver) {
    String filter = request.getTitleFilter() == null ? "" : request.getTitleFilter().toLowerCase();
    List<ShowResponse> matches = CATALOG.values().stream()
        .filter(show -> filter.isEmpty() || show.getTitle().toLowerCase().contains(filter))
        .toList();
    responseObserver.onNext(ListShowsResponse.newBuilder().addAllShows(matches).build());
    responseObserver.onCompleted();
  }
}
