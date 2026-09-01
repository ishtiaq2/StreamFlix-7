package com.example.streaming.catalog;

import com.example.streaming.catalog.grpc.*;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.testing.GrpcCleanupRule;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * gRPC's own in-process transport for tests — a real network stack
 * (framing, serialization) minus a real socket, the same "genuinely
 * exercise the real code path, fake only what's expensive" instinct as
 * every other test in this whole project (mock-server for HTTP,
 * SerialPortMock for serial, fake-podman for process spawning).
 */
public class CatalogServiceImplTest {

  @Rule
  public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private CatalogServiceGrpc.CatalogServiceBlockingStub startServerAndGetStub() throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    grpcCleanup.register(
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(new CatalogServiceImpl())
            .build()
            .start());
    return CatalogServiceGrpc.newBlockingStub(
        grpcCleanup.register(InProcessChannelBuilder.forName(serverName).directExecutor().build()));
  }

  @Test
  public void getShow_returnsKnownShow() throws Exception {
    var stub = startServerAndGetStub();
    ShowResponse response = stub.getShow(GetShowRequest.newBuilder().setShowId("show-1").build());
    assertEquals("Signal Loss", response.getTitle());
    assertEquals(2025, response.getReleaseYear());
  }

  @Test
  public void getShow_unknownId_raisesNotFound() throws Exception {
    var stub = startServerAndGetStub();
    StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
        () -> stub.getShow(GetShowRequest.newBuilder().setShowId("does-not-exist").build()));
    assertEquals(io.grpc.Status.Code.NOT_FOUND, ex.getStatus().getCode());
  }

  @Test
  public void getAvailability_unknownShow_returnsEmptyNotError() throws Exception {
    // Deliberately not an error — "not currently available anywhere" is
    // a valid, expected response shape, not an exceptional one. Worth a
    // dedicated test precisely because it's the kind of distinction easy
    // to get backwards under time pressure (see the command-injection
    // scenario's Q4 for a related "error vs. valid empty response"
    // design point in the earlier gauntlet repo).
    var stub = startServerAndGetStub();
    AvailabilityResponse response =
        stub.getAvailability(GetAvailabilityRequest.newBuilder().setShowId("unknown").build());
    assertEquals(0, response.getRegionsCount());
  }
}
