package dev.other.bykind

/**
 * ADR-154: the **package-prefix** arm of `admit(...)`. `admit` takes the same matcher as
 * `exclude` (`isUnderPackage(packageName, entry) || isUnderPackage(qualifiedName, entry)`,
 * ADR-154 §1), so a whole dependency package can be admitted additively without appearing in
 * `include(...)` and without the ADR-063 replacement rule biting.
 *
 * Its own package rather than a second type in `dev.other.bytype`, because the point of the cell
 * is that the entry names no type at all. The entry is `admit("dev.other.bykind")`, never
 * `admit("dev.other")`: that prefix would also admit `dev.other.core`, which is the load-bearing
 * negative fixture for ADR-066 and must stay refused.
 *
 * Mylo sheds a tuft on every dark jumper in the house.
 */
class Tuft(val colour: String) {

  /** One member, so the prefix-admitted type is exercised rather than merely declared. */
  fun brushed(): String = "a $colour tuft, brushed off the sofa"
}
