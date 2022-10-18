// generated by codegen/codegen.py
private import codeql.swift.generated.Synth
private import codeql.swift.generated.Raw
import codeql.swift.elements.stmt.BraceStmt
import codeql.swift.elements.Element
import codeql.swift.elements.decl.ParamDecl

module Generated {
  class Callable extends Synth::TCallable, Element {
    /**
     * Gets the self param, if it exists.
     * This is taken from the "hidden" AST and should only be used to be overridden by classes.
     */
    ParamDecl getImmediateSelfParam() {
      result =
        Synth::convertParamDeclFromRaw(Synth::convertCallableToRaw(this)
              .(Raw::Callable)
              .getSelfParam())
    }

    /**
     * Gets the self param, if it exists.
     */
    final ParamDecl getSelfParam() { result = getImmediateSelfParam().resolve() }

    /**
     * Holds if `getSelfParam()` exists.
     */
    final predicate hasSelfParam() { exists(getSelfParam()) }

    /**
     * Gets the `index`th param.
     * This is taken from the "hidden" AST and should only be used to be overridden by classes.
     */
    ParamDecl getImmediateParam(int index) {
      result =
        Synth::convertParamDeclFromRaw(Synth::convertCallableToRaw(this)
              .(Raw::Callable)
              .getParam(index))
    }

    /**
     * Gets the `index`th param.
     */
    final ParamDecl getParam(int index) { result = getImmediateParam(index).resolve() }

    /**
     * Gets any of the params.
     */
    final ParamDecl getAParam() { result = getParam(_) }

    /**
     * Gets the number of params.
     */
    final int getNumberOfParams() { result = count(getAParam()) }

    /**
     * Gets the body, if it exists.
     * This is taken from the "hidden" AST and should only be used to be overridden by classes.
     */
    BraceStmt getImmediateBody() {
      result =
        Synth::convertBraceStmtFromRaw(Synth::convertCallableToRaw(this).(Raw::Callable).getBody())
    }

    /**
     * Gets the body, if it exists.
     *
     * The body is absent within protocol declarations.
     */
    final BraceStmt getBody() { result = getImmediateBody().resolve() }

    /**
     * Holds if `getBody()` exists.
     */
    final predicate hasBody() { exists(getBody()) }
  }
}
