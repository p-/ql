// generated by codegen/codegen.py
/**
 * This module provides the generated definition of `MissingMemberDecl`.
 * INTERNAL: Do not import directly.
 */

private import codeql.swift.generated.Synth
private import codeql.swift.generated.Raw
import codeql.swift.elements.decl.Decl

module Generated {
  /**
   * A placeholder for missing declarations that can arise on object deserialization.
   * INTERNAL: Do not reference the `Generated::MissingMemberDecl` class directly.
   * Use the subclass `MissingMemberDecl`, where the following predicates are available.
   */
  class MissingMemberDecl extends Synth::TMissingMemberDecl, Decl {
    override string getAPrimaryQlClass() { result = "MissingMemberDecl" }

    /**
     * Gets the name of this missing member declaration.
     */
    string getName() {
      result = Synth::convertMissingMemberDeclToRaw(this).(Raw::MissingMemberDecl).getName()
    }
  }
}
