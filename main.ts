/** dot: sync config files across hosts through a git repository. */

import { Task } from "./src/task.ts";
import { type DotError, renderError, usageError } from "./src/errors.ts";
import { bind } from "./src/commands/bind.ts";
import { sync } from "./src/commands/sync.ts";
import { add } from "./src/commands/add.ts";
import { remove } from "./src/commands/remove.ts";

const VERSION = "0.1.0";

const HELP = `dot ${VERSION} — sync config files across hosts through a git repo

usage:
  dot bind <repo>     set the git remote that stores the config files
  dot sync            pull, reconcile every tracked file with this host, push
  dot add <path>      track a file, or every file inside a directory
  dot remove <path>   stop tracking a file or directory (host copies stay)
  dot help            show this message

data lives in ~/.dot (override with DOT_HOME)`;

const dispatch = (): Task<string, DotError> => {
  const [command, arg] = Deno.args;
  const extra = Deno.args.length > 2 ||
    (Deno.args.length > 1 && (command === "sync" || command === "help"));
  if (extra) {
    return Task.fail(usageError(`too many arguments — try: dot help`));
  }
  switch (command) {
    case "bind":
      return arg !== undefined ? bind(arg) : Task.fail(
        usageError("bind needs a repository URL — dot bind <repo>"),
      );
    case "sync":
      return sync();
    case "add":
      return arg !== undefined
        ? add(arg)
        : Task.fail(usageError("add needs a path — dot add <path>"));
    case "remove":
      return arg !== undefined
        ? remove(arg)
        : Task.fail(usageError("remove needs a path — dot remove <path>"));
    case undefined:
    case "help":
    case "--help":
    case "-h":
      return Task.of(HELP);
    case "version":
    case "--version":
      return Task.of(`dot ${VERSION}`);
    default:
      return Task.fail(
        usageError(
          `unknown command ${JSON.stringify(command)} — try: dot help`,
        ),
      );
  }
};

const result = await dispatch().run();
if (result.ok) {
  if (result.value !== "") console.log(result.value);
} else {
  console.error(renderError(result.error));
  Deno.exit(1);
}
