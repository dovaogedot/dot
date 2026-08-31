/** Result: the outcome of a computation that can fail with a typed error. */

export class Ok<T> {
  readonly ok = true;
  constructor(readonly value: T) {}
}

export class Err<E> {
  readonly ok = false;
  constructor(readonly error: E) {}
}

export type Result<T, E> = Ok<T> | Err<E>;
