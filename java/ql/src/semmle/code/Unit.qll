/** Provides the `Unit` class. */

/** The unit type. */
private newtype TUnit = TMkUnit()

/** The trivial type with a single element. */
class Unit extends TUnit {
  string toString() { result = "unit" }
}
