/** Result: the outcome of a computation that can fail with a typed error. */

export type Ok<T> = { readonly ok: true; readonly value: T };
export type Err<E> = { readonly ok: false; readonly error: E };
export type Result<T, E> = Ok<T> | Err<E>;

export const ok = <T>(value: T): Ok<T> => ({ ok: true, value });
export const err = <E>(error: E): Err<E> => ({ ok: false, error });

export const map = <T, U, E>(r: Result<T, E>, f: (t: T) => U): Result<U, E> =>
  r.ok ? ok(f(r.value)) : r;

export const mapErr = <T, E, F>(
  r: Result<T, E>,
  f: (e: E) => F,
): Result<T, F> => r.ok ? r : err(f(r.error));

export const andThen = <T, U, E>(
  r: Result<T, E>,
  f: (t: T) => Result<U, E>,
): Result<U, E> => r.ok ? f(r.value) : r;

export const unwrapOr = <T, E>(r: Result<T, E>, fallback: T): T =>
  r.ok ? r.value : fallback;

/** Collects results in order, short-circuiting on the first error. */
export const all = <T, E>(rs: readonly Result<T, E>[]): Result<T[], E> => {
  const out: T[] = [];
  for (const r of rs) {
    if (!r.ok) return r;
    out.push(r.value);
  }
  return ok(out);
};
