package com.nullguard.multilang;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Boundary between language semantics and orchestration/reporting. */
public interface LanguageAnalyzer {
  ObjectNode analyze(ProjectSpec project) throws Exception;
}
