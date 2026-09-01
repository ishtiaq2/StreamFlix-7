package com.example.streaming.graphql;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * DGS's own test framework — @EnableDgsTest wires up DgsQueryExecutor,
 * which runs a real query through the real schema and real data
 * fetchers, exactly as a real client's request would. Only the gRPC
 * stub itself is mocked here, since standing up a real catalog-service
 * for a unit test is exactly the kind of thing an integration test
 * (against a real, separately-run instance, or gRPC's in-process
 * transport as catalog-service's own test does) should cover instead —
 * matching the layering this whole project has used throughout:
 * mock only the genuinely external boundary, exercise everything else
 * for real.
 */
@SpringBootTest(classes = GraphqlServiceApplication.class)
@EnableDgsTest
class ShowsDataFetcherTest {

  @Autowired
  DgsQueryExecutor queryExecutor;

  @MockBean
  private com.example.streaming.catalog.grpc.CatalogServiceGrpc.CatalogServiceBlockingStub catalogStub;

  @Test
  void showQuery_resolvesTitleFromSchema() {
    when(catalogStub.getShow(com.example.streaming.catalog.grpc.GetShowRequest.newBuilder()
            .setShowId("show-1").build()))
        .thenReturn(com.example.streaming.catalog.grpc.ShowResponse.newBuilder()
            .setId("show-1").setTitle("Signal Loss").setReleaseYear(2025).build());

    String title = queryExecutor.executeAndExtractJsonPath(
        "{ show(id: \"show-1\") { title } }", "data.show.title");

    assertThat(title).isEqualTo("Signal Loss");
  }

  @Test
  void showQuery_withNoAvailabilityFieldRequested_neverCallsAvailabilityRpc() {
    when(catalogStub.getShow(com.example.streaming.catalog.grpc.GetShowRequest.newBuilder()
            .setShowId("show-1").build()))
        .thenReturn(com.example.streaming.catalog.grpc.ShowResponse.newBuilder()
            .setId("show-1").setTitle("Signal Loss").setReleaseYear(2025).build());

    queryExecutor.executeAndExtractJsonPath("{ show(id: \"show-1\") { title } }", "data.show.title");

    // The actual point of this test: confirm the over-fetching-avoidance
    // claim in ShowsDataFetcher's own comment is real, not aspirational.
    org.mockito.Mockito.verify(catalogStub, org.mockito.Mockito.never())
        .getAvailability(org.mockito.ArgumentMatchers.any());
  }
}
