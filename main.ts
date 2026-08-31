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
  dot sync [-f]       pull, reconcile every tracked file with this host, push;
                        -f / --force keeps the host copy when both sides changed
  dot add <path>      track a file, or every file inside a directory
  dot remove <path>   stop tracking a file or directory (host copies stay)
  dot help            show this message

data lives in ~/.dot (override with DOT_HOME)`;

type Handler = (arg: string | undefined) => Task<string, DotError>;

const requireArg = (
  missing: string,
  run: (arg: string) => Task<string, DotError>,
): Handler =>
(arg) =>
  arg === undefined
    ? Task.fail<DotError, string>(usageError(missing))
    : run(arg);

const showHelp: Handler = () => Task.of(HELP);
const showVersion: Handler = () => Task.of(`dot ${VERSION}`);

/** Alias keys share one handler per command. */
const commands: Readonly<Record<string, Handler>> = {
  bind: requireArg("bind needs a repository URL — dot bind <repo>", bind),
  sync: (arg) =>
    arg === undefined
      ? sync(false)
      : arg === "-f" || arg === "--force"
      ? sync(true)
      : Task.fail(usageError("sync accepts only -f / --force — dot sync [-f]")),
  add: requireArg("add needs a path — dot add <path>", add),
  remove: requireArg("remove needs a path — dot remove <path>", remove),
  help: showHelp,
  "--help": showHelp,
  "-h": showHelp,
  version: showVersion,
  "--version": showVersion,
};

const dispatch = (): Task<string, DotError> => {
  const [command, arg] = Deno.args;
  const extra = Deno.args.length > 2 ||
    (Deno.args.length > 1 && command === "help");
  if (extra) {
    return Task.fail(usageError("too many arguments — try: dot help"));
  }
  if (command === undefined) return showHelp(undefined);
  const handler = commands[command];
  return handler === undefined
    ? Task.fail(
      usageError(`unknown command ${JSON.stringify(command)} — try: dot help`),
    )
    : handler(arg);
};

const result = await dispatch().run();
if (result.ok) {
  if (result.value !== "") console.log(result.value);
} else {
  console.error(renderError(result.error));
  Deno.exit(1);
}
