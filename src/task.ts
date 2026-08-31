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

  /** Maps each item to a task and chains them in order, short-circuiting on the first error. */
  static traverse<A, T, E>(
    items: readonly A[],
    f: (a: A) => Task<T, E>,
  ): Task<T[], E> {
    return items.reduce(
      (acc: Task<T[], E>, a) =>
        acc.flatMap((out) => f(a).map((t) => [...out, t])),
      Task.of<T[], E>([]),
    );
  }

  map<U>(f: (t: T) => U): Task<U, E> {
    return new Task(async () => {
      const result = await this.thunk();
      return result.ok ? ok(f(result.value)) : result;
    });
  }

  mapErr<F>(f: (e: E) => F): Task<T, F> {
    return new Task(async () => {
      const result = await this.thunk();
      return result.ok ? result : err(f(result.error));
    });
  }

  /** Chains a dependent task; the error type widens to cover both steps. */
  flatMap<U, F>(f: (t: T) => Task<U, F>): Task<U, E | F> {
    return new Task<U, E | F>(async (): Promise<Result<U, E | F>> => {
      const result = await this.thunk();
      return result.ok ? f(result.value).run() : result;
    });
  }

  /** Recovers from a failure; the error type narrows to what recovery can produce. */
  orElse<F>(f: (e: E) => Task<T, F>): Task<T, F> {
    return new Task<T, F>(async (): Promise<Result<T, F>> => {
      const result = await this.thunk();
      return result.ok ? result : f(result.error).run();
    });
  }

  run(): Promise<Result<T, E>> {
    return this.thunk();
  }
}
