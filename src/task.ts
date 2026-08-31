/**
 * Task<T, E>: a lazy, referentially transparent description of an async
 * computation resolving to Result<T, E>. Constructing or combining tasks
 * performs no effects; only `run()` executes the description.
 */

import { err, ok, type Result } from "./result.ts";

export class Task<T, E> {
  private constructor(
    private readonly thunk: () => Promise<Result<T, E>>,
  ) {}

  static of<T, E = never>(value: T): Task<T, E> {
    return new Task(() => Promise.resolve(ok(value)));
  }

  static fail<E, T = never>(error: E): Task<T, E> {
    return new Task(() => Promise.resolve(err(error)));
  }

  static fromResult<T, E>(r: Result<T, E>): Task<T, E> {
    return new Task(() => Promise.resolve(r));
  }

  /** Wraps an effect that may throw; the thrown value is mapped to a typed error. */
  static attempt<T, E>(
    effect: () => Promise<T>,
    onThrow: (u: unknown) => E,
  ): Task<T, E> {
    return new Task(async () => {
      try {
        return ok(await effect());
      } catch (u) {
        return err(onThrow(u));
      }
    });
  }

  /** Maps each item to a task and runs them in order, short-circuiting on the first error. */
  static traverse<A, T, E>(
    items: readonly A[],
    f: (a: A) => Task<T, E>,
  ): Task<T[], E> {
    return new Task(async () => {
      const out: T[] = [];
      for (const a of items) {
        const r = await f(a).run();
        if (!r.ok) return r;
        out.push(r.value);
      }
      return ok(out);
    });
  }

  map<U>(f: (t: T) => U): Task<U, E> {
    return new Task(async () => {
      const r = await this.thunk();
      return r.ok ? ok(f(r.value)) : r;
    });
  }

  mapErr<F>(f: (e: E) => F): Task<T, F> {
    return new Task(async () => {
      const r = await this.thunk();
      return r.ok ? r : err(f(r.error));
    });
  }

  andThen<U>(f: (t: T) => Task<U, E>): Task<U, E> {
    return new Task(async () => {
      const r = await this.thunk();
      return r.ok ? f(r.value).run() : r;
    });
  }

  orElse(f: (e: E) => Task<T, E>): Task<T, E> {
    return new Task(async () => {
      const r = await this.thunk();
      return r.ok ? r : f(r.error).run();
    });
  }

  run(): Promise<Result<T, E>> {
    return this.thunk();
  }
}
