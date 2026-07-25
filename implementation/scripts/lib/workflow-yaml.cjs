#!/usr/bin/env node
'use strict';

/*
 * Dependency-free object-model reader for GitHub Actions workflow YAML
 * (rf2-p4a93).
 *
 * # Why this exists
 *
 * Workflow policy in this repo has so far been asserted with regexes over
 * workflow TEXT (`_lint-workflow-policy.test.cjs`,
 * `_docs-workflow-policy.test.cjs`, the deploy-leaf matrix scrape in
 * `_rewrite-local-root-coord.test.cjs`). That is fine for "does this string
 * appear in this section", but it cannot answer a question about the JOB
 * GRAPH — "can job A start before job B succeeded?" — which is what the
 * release DAG's irreversible-publish invariants are made of. A `needs:` edge
 * is structure, not text, and `needs: [a, b]`, a block list, and a single
 * scalar are three spellings of the same structure.
 *
 * So: a real parse into a real object model, then assertions over the model.
 * The suites that consume it live in `_release-dag-policy.test.cjs`.
 *
 * # Why not a YAML library
 *
 * The CI job that runs these suites (`js-harness-self-tests` in
 * .github/workflows/test.yml) does no `npm ci` — it sets up Node and runs
 * `npm run test:script-policy` straight away. Every suite it runs is
 * therefore Node-stdlib-only, and a `require('yaml')` here would go red on
 * the runner while passing locally. The Python checkers have the same
 * constraint from the other side ("pure Python stdlib, no setup beyond
 * actions/setup-python") so PyYAML is not available either.
 *
 * # Scope, honestly stated
 *
 * This is NOT a YAML implementation. It reads the strict subset the 13
 * workflow files in .github/workflows/ are written in:
 *
 *   - block mappings and block sequences, nested by indentation;
 *   - sequence items that open with an inline mapping pair (`- leaf: ssr`);
 *   - block scalars (`|`, `|-`, `|+`, `>`, `>-`, `>+`);
 *   - flow sequences (`needs: [a, b]`) of scalars;
 *   - single/double-quoted and plain scalars, with trailing `#` comments
 *     stripped only where a comment can legally start.
 *
 * Everything outside that subset — tabs for indentation, flow MAPPINGS,
 * anchors/aliases, tags, multiple documents, `? ` complex keys — raises
 * `WorkflowYamlError`. Failing loudly on an unsupported construct is the
 * point: a gate that silently mis-parses is worse than no gate, and the
 * corpus is checked wholesale by `parses every workflow in
 * .github/workflows/` so an unsupported construct arriving in ANY workflow
 * surfaces immediately rather than as a quiet false green somewhere else.
 *
 * Scalars stay STRINGS. `fail-fast: false` reads as the string 'false', not
 * a boolean; `fetch-depth: 0` as '0'. Workflow semantics are string-ish
 * anyway (`${{ }}` expressions are strings), and no type coercion means no
 * YAML-1.1-versus-1.2 truthiness trap (`on:`, `no:`, `y:` stay keys, which
 * is what a workflow author means and what PyYAML gets wrong).
 */

class WorkflowYamlError extends Error {}

const KEY_RE = /^("(?:[^"\\]|\\.)*"|'(?:[^']|'')*'|[A-Za-z0-9_][A-Za-z0-9_.\-/ ]*?):(?:\s+(.*))?$/;
const BLOCK_SCALAR_RE = /^([|>])([-+]?)(\d*)$/;

// A significant line: indentation width plus the content after it. Blank lines
// and whole-line comments are dropped by `Cursor`, but block scalars need the
// raw text, so the raw line is carried along.
function scan(text) {
  const lines = [];
  const raws = text.split(/\r?\n/);
  for (let i = 0; i < raws.length; i += 1) {
    const raw = raws[i];
    if (/^\s*\t/.test(raw) || /^\t/.test(raw)) {
      throw new WorkflowYamlError(`line ${i + 1}: tab indentation is not supported`);
    }
    const indent = /^ */.exec(raw)[0].length;
    lines.push({ lineNo: i + 1, indent, raw, content: raw.slice(indent) });
  }
  return lines;
}

function isBlank(line) {
  return line.content.length === 0;
}

function isComment(line) {
  return line.content.startsWith('#');
}

// Strip a trailing `# comment` from a plain scalar. A `#` only starts a
// comment at the start of the value or after whitespace, and never inside a
// quoted run — which is what keeps `uses: foo@sha  # v6.0.2` and
// `run: "echo '#1'"` both correct.
function stripInlineComment(value) {
  let quote = null;
  for (let i = 0; i < value.length; i += 1) {
    const ch = value[i];
    if (quote) {
      if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'") {
      quote = ch;
      continue;
    }
    if (ch === '#' && (i === 0 || /\s/.test(value[i - 1]))) {
      return value.slice(0, i);
    }
  }
  return value;
}

function unquote(token, lineNo) {
  if (token.startsWith("'") && token.endsWith("'") && token.length >= 2) {
    return token.slice(1, -1).replace(/''/g, "'");
  }
  if (token.startsWith('"') && token.endsWith('"') && token.length >= 2) {
    return token.slice(1, -1).replace(/\\(.)/g, (_m, c) => {
      if (c === 'n') return '\n';
      if (c === 't') return '\t';
      return c;
    });
  }
  if (token.includes('{') && !token.includes('${{')) {
    throw new WorkflowYamlError(`line ${lineNo}: flow mappings are not supported`);
  }
  if (token.startsWith('&') || token.startsWith('*') || token.startsWith('!')) {
    throw new WorkflowYamlError(`line ${lineNo}: anchors/aliases/tags are not supported`);
  }
  return token;
}

// Split a flow sequence body on top-level commas (no nesting in the corpus,
// but brackets are tracked so `[a, [b, c]]` cannot silently mis-split).
function splitFlow(body, lineNo) {
  const parts = [];
  let depth = 0;
  let quote = null;
  let current = '';
  for (const ch of body) {
    if (quote) {
      current += ch;
      if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'") {
      quote = ch;
      current += ch;
      continue;
    }
    if (ch === '[') depth += 1;
    if (ch === ']') depth -= 1;
    if (ch === ',' && depth === 0) {
      parts.push(current);
      current = '';
      continue;
    }
    current += ch;
  }
  parts.push(current);
  if (depth !== 0) throw new WorkflowYamlError(`line ${lineNo}: unbalanced flow sequence`);
  return parts.map((p) => p.trim()).filter((p) => p.length > 0);
}

function parseScalar(rawValue, lineNo) {
  const value = stripInlineComment(rawValue).trim();
  if (value.length === 0) return null;
  if (value.startsWith('[')) {
    if (!value.endsWith(']')) {
      throw new WorkflowYamlError(`line ${lineNo}: multi-line flow sequences are not supported`);
    }
    return splitFlow(value.slice(1, -1), lineNo).map((p) => unquote(p, lineNo));
  }
  return unquote(value, lineNo);
}

class Cursor {
  constructor(lines) {
    this.lines = lines;
    this.i = 0;
  }

  // Next line that carries structure. Blank lines and whole-line comments are
  // skipped — EXCEPT that callers reading a block scalar consume raw lines
  // directly, so comment-looking content inside a `run: |` body is preserved.
  peek() {
    while (this.i < this.lines.length) {
      const line = this.lines[this.i];
      if (isBlank(line) || isComment(line)) {
        this.i += 1;
        continue;
      }
      return line;
    }
    return null;
  }

  take() {
    const line = this.peek();
    if (line === null) return null;
    this.i += 1;
    return line;
  }

  // Consume the body of a block scalar introduced on `headerLine`. The body is
  // every following line indented deeper than `parentIndent` (blank lines
  // included), dedented to the first body line's indentation.
  blockScalar(header, parentIndent, headerLine) {
    const m = BLOCK_SCALAR_RE.exec(header);
    if (!m) throw new WorkflowYamlError(`line ${headerLine}: unsupported block scalar '${header}'`);
    const [, style, chomp, explicitIndent] = m;
    if (explicitIndent) {
      throw new WorkflowYamlError(
        `line ${headerLine}: explicit block-scalar indentation is not supported`,
      );
    }
    const body = [];
    let bodyIndent = null;
    while (this.i < this.lines.length) {
      const line = this.lines[this.i];
      if (isBlank(line)) {
        // A blank line belongs to the block only if the block continues after
        // it; look ahead for a deeper-indented non-blank line.
        let j = this.i + 1;
        while (j < this.lines.length && isBlank(this.lines[j])) j += 1;
        if (j < this.lines.length && this.lines[j].indent > parentIndent) {
          body.push('');
          this.i += 1;
          continue;
        }
        break;
      }
      if (line.indent <= parentIndent) break;
      if (bodyIndent === null) bodyIndent = line.indent;
      body.push(line.raw.slice(Math.min(bodyIndent, line.indent)));
      this.i += 1;
    }
    let text;
    if (style === '|') {
      text = body.join('\n');
    } else {
      // Folded: a single newline between two non-empty lines becomes a space;
      // a run of blank lines keeps one newline per extra blank.
      text = '';
      for (let k = 0; k < body.length; k += 1) {
        if (k === 0) {
          text += body[k];
          continue;
        }
        if (body[k] === '' || body[k - 1] === '') text += '\n';
        else text += ' ';
        text += body[k];
      }
    }
    if (chomp === '-') return text.replace(/\n+$/, '');
    if (chomp === '+') return `${text}\n`;
    return text.length === 0 ? '' : `${text.replace(/\n+$/, '')}\n`;
  }

  // A mapping whose keys all sit at column `indent`.
  mapping(indent) {
    const out = {};
    for (;;) {
      const line = this.peek();
      if (line === null || line.indent < indent) break;
      if (line.indent > indent) {
        throw new WorkflowYamlError(`line ${line.lineNo}: unexpected indentation in mapping`);
      }
      if (line.content.startsWith('- ') || line.content === '-') break;
      const m = KEY_RE.exec(line.content);
      if (!m) {
        throw new WorkflowYamlError(`line ${line.lineNo}: not a mapping entry: '${line.content}'`);
      }
      this.take();
      const key = unquote(m[1].trim(), line.lineNo);
      const rest = m[2] === undefined ? '' : m[2];
      const restTrimmed = stripInlineComment(rest).trim();
      if (Object.prototype.hasOwnProperty.call(out, key)) {
        throw new WorkflowYamlError(`line ${line.lineNo}: duplicate key '${key}'`);
      }
      if (BLOCK_SCALAR_RE.test(restTrimmed)) {
        out[key] = this.blockScalar(restTrimmed, indent, line.lineNo);
      } else if (restTrimmed.length > 0) {
        out[key] = parseScalar(rest, line.lineNo);
      } else {
        out[key] = this.child(indent);
      }
    }
    return out;
  }

  // A sequence whose `-` markers all sit at column `indent`.
  sequence(indent) {
    const out = [];
    for (;;) {
      const line = this.peek();
      if (line === null || line.indent !== indent) break;
      if (!(line.content.startsWith('- ') || line.content === '-')) break;
      if (line.content === '-') {
        this.take();
        out.push(this.child(indent));
        continue;
      }
      const rest = line.content.slice(1);
      const leading = /^ */.exec(rest)[0].length;
      const itemIndent = indent + 1 + leading;
      const itemContent = rest.slice(leading);
      if (KEY_RE.test(itemContent)) {
        // Rewrite `- key: v` into `  key: v` at the item's own column, then let
        // `mapping()` read the first pair and every sibling pair below it.
        this.lines[this.i] = {
          ...line,
          indent: itemIndent,
          raw: ' '.repeat(itemIndent) + itemContent,
          content: itemContent,
        };
        out.push(this.mapping(itemIndent));
        continue;
      }
      this.take();
      if (BLOCK_SCALAR_RE.test(itemContent)) {
        out.push(this.blockScalar(itemContent, indent, line.lineNo));
      } else {
        out.push(parseScalar(itemContent, line.lineNo));
      }
    }
    return out;
  }

  // The value of a key that had nothing after its colon: a nested block at a
  // deeper indent, or null when the key is empty (`workflow_dispatch:`).
  child(parentIndent) {
    const line = this.peek();
    if (line === null || line.indent <= parentIndent) return null;
    if (line.content.startsWith('- ') || line.content === '-') {
      return this.sequence(line.indent);
    }
    return this.mapping(line.indent);
  }
}

// Parse workflow YAML text into a plain-object model.
function parseWorkflowYaml(text) {
  const cursor = new Cursor(scan(text));
  const first = cursor.peek();
  if (first === null) throw new WorkflowYamlError('empty document');
  if (first.content.startsWith('---')) {
    throw new WorkflowYamlError('explicit document markers are not supported');
  }
  const doc = first.content.startsWith('- ')
    ? cursor.sequence(first.indent)
    : cursor.mapping(first.indent);
  const trailing = cursor.peek();
  if (trailing !== null) {
    throw new WorkflowYamlError(`line ${trailing.lineNo}: unconsumed content`);
  }
  return doc;
}

// `needs:` in any of its three spellings, as an array of job ids.
function needsOf(job) {
  const needs = job && job.needs;
  if (needs === null || needs === undefined) return [];
  return Array.isArray(needs) ? needs.slice() : [needs];
}

// Every job id that must SUCCEED before `jobId` may start — the transitive
// closure of `needs`. A `needs` edge onto a matrix job covers all of its
// values, so this closure is exactly "what has already gone green".
function transitiveNeeds(jobs, jobId) {
  const seen = new Set();
  const walk = (id) => {
    for (const dep of needsOf(jobs[id])) {
      if (seen.has(dep)) continue;
      seen.add(dep);
      if (jobs[dep] !== undefined) walk(dep);
    }
  };
  walk(jobId);
  return seen;
}

// The `include:` values of a job's matrix, or [] when the job has none.
function matrixInclude(job) {
  const include =
    job && job.strategy && job.strategy.matrix && job.strategy.matrix.include;
  return Array.isArray(include) ? include : [];
}

module.exports = {
  WorkflowYamlError,
  parseWorkflowYaml,
  needsOf,
  transitiveNeeds,
  matrixInclude,
};
