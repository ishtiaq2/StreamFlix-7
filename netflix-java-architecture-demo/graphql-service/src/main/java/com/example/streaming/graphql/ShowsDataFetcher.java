package com.example.streaming.graphql;

import com.example.streaming.catalog.grpc.*;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import net.devh.boot.grpc.client.inject.GrpcClient;

import java.util.List;
import java.util.Map;

/**
 * The pattern the video describes directly: GraphQL as the schema/query
 * layer, gRPC as the actual transport to the owning service — DGS's
 * schema-first model (this class is discovered and wired to
 * schema.graphqls by convention, not manual registration) plus a gRPC
 * client stub injected the same way any other Spring bean would be.
 *
 * @DgsData resolves Show.availability as a SEPARATE downstream call from
 * the one that resolves the Show itself — deliberate, not an oversight:
 * a client that only asks for { title } never triggers the availability
 * RPC at all, which is exactly the over-fetching problem GraphQL exists
 * to solve, and exactly why "one big join at the top" would have thrown
 * that benefit away.
 */
@DgsComponent
public class ShowsDataFetcher {

  @GrpcClient("catalog-service")
  private CatalogServiceGrpc.CatalogServiceBlockingStub catalogStub;

  @DgsQuery
  public Show show(@InputArgument String id) {
    ShowResponse response = catalogStub.getShow(GetShowRequest.newBuilder().setShowId(id).build());
    return toGraphqlShow(response);
  }

  @DgsQuery
  public List<Show> shows(@InputArgument String titleFilter) {
    ListShowsResponse response = catalogStub.listShows(
        ListShowsRequest.newBuilder().setTitleFilter(titleFilter == null ? "" : titleFilter).build());
    return response.getShowsList().stream().map(this::toGraphqlShow).toList();
  }

  @DgsData(parentType = "Show", field = "availability")
  public Availability availability(com.netflix.graphql.dgs.context.DgsDataFetchingEnvironment dfe) {
    Show show = dfe.getSource();
    AvailabilityResponse response =
        catalogStub.getAvailability(GetAvailabilityRequest.newBuilder().setShowId(show.getId()).build());
    if (response.getRegionsCount() == 0) {
      return null; // schema declares this field nullable precisely for this case
    }
    return new Availability(response.getRegionsList(), response.getStreamingSince());
  }

  private Show toGraphqlShow(ShowResponse response) {
    return new Show(response.getId(), response.getTitle(), response.getReleaseYear(), response.getGenresList());
  }
}
