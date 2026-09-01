package com.example.streaming.graphql;

import java.util.List;

/**
 * In a real DGS setup this would be generated automatically from
 * schema.graphqls by the Gradle Code Generation plugin (real, official
 * DGS tooling, confirmed in Netflix's own docs) — hand-written here for
 * clarity in a small demo repo rather than wiring up codegen for two
 * tiny types, but codegen is the real, standard practice worth knowing
 * about before hand-writing this pattern at any real scale.
 */
public record Show(String id, String title, Integer releaseYear, List<String> genres) {
  public String getId() {
    return id;
  } // DGS's data-fetching environment (dfe.getSource()) expects bean-style accessors
}
