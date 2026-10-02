package com.conversive.aep.definition;

import java.util.Optional;

/** Tool classification the validator and freezer need. P5 replaces the in-memory default with a DB-backed one. */
public interface ToolCatalog {

    Optional<ToolInfo> find(String name);
}
