/**
 * Provides a taint-tracking configuration for reasoning about cleartext
 * preferences storage vulnerabilities.
 */

import swift
import codeql.swift.security.SensitiveExprs
import codeql.swift.dataflow.DataFlow
import codeql.swift.dataflow.TaintTracking
import codeql.swift.security.CleartextStoragePreferencesExtensions

/**
 * A taint configuration from sensitive information to expressions that are
 * stored as preferences.
 */
class CleartextStorageConfig extends TaintTracking::Configuration {
  CleartextStorageConfig() { this = "CleartextStorageConfig" }

  override predicate isSource(DataFlow::Node node) { node.asExpr() instanceof SensitiveExpr }

  override predicate isSink(DataFlow::Node node) { node instanceof Stored }

  override predicate isSanitizerIn(DataFlow::Node node) {
    // make sources barriers so that we only report the closest instance
    this.isSource(node)
  }

  override predicate isSanitizer(DataFlow::Node node) {
    // encryption barrier
    node.asExpr() instanceof EncryptedExpr
  }
}
