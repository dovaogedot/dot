/**
 * Exhaustive pattern matching on literal unions and kind-tagged unions. The
 * arms record carries one arm per union member, so adding a member turns
 * every match site into a compile error until the new arm exists.
 */

/** Dispatches on a string-literal union. */
export const matchValue = <V extends PropertyKey, R>(
  value: V,
  arms: { readonly [P in V]: () => R },
): R => arms[value]();

/** Dispatches on a union discriminated by "kind", narrowing the arm's argument. */
export const matchTag = <U extends { readonly kind: string }, R>(
  u: U,
  arms: {
    readonly [K in U["kind"]]: (v: Extract<U, { readonly kind: K }>) => R;
  },
): R => (arms[u.kind as U["kind"]] as (v: U) => R)(u);
