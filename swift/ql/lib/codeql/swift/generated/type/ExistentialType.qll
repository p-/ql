// generated by codegen/codegen.py
/**
 * This module provides the generated definition of `ExistentialType`.
 * INTERNAL: Do not import directly.
 */

private import codeql.swift.generated.Synth
private import codeql.swift.generated.Raw
import codeql.swift.elements.type.Type

module Generated {
  /**
   * INTERNAL: Do not reference the `Generated::ExistentialType` class directly.
   * Use the subclass `ExistentialType`, where the following predicates are available.
   */
  class ExistentialType extends Synth::TExistentialType, Type {
    override string getAPrimaryQlClass() { result = "ExistentialType" }

    /**
     * Gets the constraint of this existential type.
     *
     * This includes nodes from the "hidden" AST. It can be overridden in subclasses to change the
     * behavior of both the `Immediate` and non-`Immediate` versions.
     */
    Type getImmediateConstraint() {
      result =
        Synth::convertTypeFromRaw(Synth::convertExistentialTypeToRaw(this)
              .(Raw::ExistentialType)
              .getConstraint())
    }

    /**
     * Gets the constraint of this existential type.
     */
    final Type getConstraint() {
      exists(Type immediate |
        immediate = this.getImmediateConstraint() and
        if exists(this.getResolveStep()) then result = immediate else result = immediate.resolve()
      )
    }
  }
}
