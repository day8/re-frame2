#!/usr/bin/env node
'use strict';
// THE LAUNCHER — the process an operator runs, and the one a JVM host spawns.
//
//     re-frame2-ssr-node --module /srv/my-app/out/server-bundle.cjs
//     node bin/serve.cjs --module ./out/server-bundle.cjs --port 0
//
// Everything that decides an outcome lives in `src/`. This file turns flags
// into `createService` and `serve` arguments, says where it is listening,
// and stops cleanly when asked. It is deliberately the least interesting
// file in the package.
//
// ## THE READY LINE
//
// Once the socket is listening it writes ONE line to stdout, and nothing
// else, ever:
//
//     {"rf.ssr-node":"ready","url":"http://127.0.0.1:8148","host":"127.0.0.1","port":8148,"buildId":"…","protocol":1}
//
// A supervisor that asked for `--port 0` reads the port it actually got
// from there. It is JSON on one line so a host in any language — the JVM
// witness in `re-frame.ssr.ring.node` is one — parses it without a
// grammar, and it carries the discriminator key so a reader that scans
// stdout line by line can pick it out even if the application bundle
// logged something at boot. `host` and `port` are the address the socket
// is BOUND to, as the OS reports it; `url` is that address spelled for a
// dialler. Diagnostics go to stderr.
//
// ## EXIT CODES
//
//     0  stopped by SIGTERM or SIGINT after a graceful close
//     1  the service could not start: the module refused, or the port is taken
//     2  the command line was wrong
//
// Node on Windows has no graceful signal — a `kill` there terminates the
// process outright, which loses nothing but in-flight renders, and a
// second signal on any platform does the same.

const path = require('node:path');
const { parseArgs } = require('node:util');
const { createService } = require('../src/service.cjs');
const { serve } = require('../src/http.cjs');

/** Every flag, its default, and the one line of help that describes it. */
const FLAGS = [
  ['module', undefined, '<path>  the application server bundle (required; resolved against the working directory)'],
  ['port', '8148', '<n>     TCP port to listen on; 0 picks a free one'],
  ['host', '127.0.0.1', '<name>  interface to bind'],
  ['isolates', '2', '<n>     worker threads; each renders one request at a time'],
  ['timeout-ms', '1000', '<n>     render deadline when the request names none'],
  ['max-timeout-ms', '5000', '<n>     ceiling on the deadline a request may ask for'],
  ['admission-ms', '250', '<n>     how long a request waits for a free isolate before 503'],
  ['max-request-bytes', String(1 << 20), '<n>     ceiling on the body, and on state + runtime together'],
];

const OPTIONS = Object.fromEntries(
  FLAGS.map(([name, defaultValue]) => [
    name,
    {
      type: 'string',
      ...(defaultValue === undefined ? {} : { default: defaultValue }),
    },
  ]),
);
OPTIONS.help = { type: 'boolean', short: 'h', default: false };

const USAGE = [
  'usage: re-frame2-ssr-node --module <path> [flags]',
  '',
  ...FLAGS.map(([name, defaultValue, description]) => {
    const flag = `  --${name} ${description}`;
    return defaultValue === undefined ? flag : `${flag}  [${defaultValue}]`;
  }),
  '  -h, --help',
  '',
].join('\n');

const log = (line) => process.stderr.write(`[rf.ssr-node] ${line}\n`);

/** A non-negative integer flag, or a usage error naming the flag. */
function parseIntegerFlag(flags, name, minimum) {
  const rawValue = flags[name];
  const value = Number(rawValue);
  if (!/^\d+$/.test(rawValue) || !Number.isSafeInteger(value) || value < minimum) {
    throw new UsageError(
      `--${name} must be an integer of at least ${minimum}; got ${JSON.stringify(rawValue)}`,
    );
  }
  return value;
}

class UsageError extends Error {}

const describeError = (error) =>
  error && error.code
    ? `${error.code} — ${error.message}`
    : String(error && error.message ? error.message : error);

/** The bound address, spelled for a dialler: IPv6 gets its brackets. */
const urlOf = (host, port) => `http://${host.includes(':') ? `[${host}]` : host}:${port}`;

async function main(argv) {
  let flags;
  try {
    ({ values: flags } = parseArgs({ args: argv, options: OPTIONS, strict: true, allowPositionals: false }));
    if (flags.help) {
      process.stdout.write(USAGE);
      return 0;
    }
    if (!flags.module) throw new UsageError('--module is required');
  } catch (err) {
    process.stderr.write(`${USAGE}\n[rf.ssr-node] ${err.message}\n`);
    return 2;
  }

  let numericOptions;
  try {
    numericOptions = {
      port: parseIntegerFlag(flags, 'port', 0),
      isolates: parseIntegerFlag(flags, 'isolates', 1),
      defaultTimeoutMs: parseIntegerFlag(flags, 'timeout-ms', 1),
      maxTimeoutMs: parseIntegerFlag(flags, 'max-timeout-ms', 1),
      admissionTimeoutMs: parseIntegerFlag(flags, 'admission-ms', 0),
      maxRequestBytes: parseIntegerFlag(flags, 'max-request-bytes', 1),
    };
  } catch (err) {
    process.stderr.write(`${USAGE}\n[rf.ssr-node] ${err.message}\n`);
    return 2;
  }

  const modulePath = path.resolve(flags.module);
  let service;
  try {
    service = await createService({ modulePath, ...numericOptions });
  } catch (err) {
    log(`could not start: ${describeError(err)}`);
    return 1;
  }

  let transport;
  try {
    transport = await serve({
      service,
      port: numericOptions.port,
      host: flags.host,
      maxRequestBytes: numericOptions.maxRequestBytes,
    });
  } catch (err) {
    log(`could not listen on ${flags.host}:${numericOptions.port}: ${describeError(err)}`);
    await service.close();
    return 1;
  }

  const boundAddress = transport.server.address();
  process.stdout.write(
    `${JSON.stringify({
      'rf.ssr-node': 'ready',
      url: urlOf(boundAddress.address, boundAddress.port),
      host: boundAddress.address,
      port: boundAddress.port,
      buildId: service.buildId,
      protocol: service.protocol,
    })}\n`,
  );

  const signal = await new Promise((resolve) => {
    for (const sig of ['SIGTERM', 'SIGINT']) process.once(sig, () => resolve(sig));
  });
  log(`${signal}: closing`);
  await transport.close();
  await service.close();
  return 0;
}

main(process.argv.slice(2)).then(
  (exitCode) => process.exit(exitCode),
  (err) => {
    log(describeError(err));
    process.exit(1);
  },
);
