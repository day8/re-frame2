(ns re-frame.ssr.constants
  "Reserved string constants the SSR runtime, host adapters, and CLJS
  bootstrap all reference. Pinning these in one ns (rather than scattered
  literal strings) makes the convention discoverable and renameable —
  tooling that searches for the convention reads one var, not a regex
  pattern across the codebase. Per Spec 011 §Hydration payload script id.")

(def ^:const payload-script-id
  "The literal `id` the host-adapter HTML shell stamps onto the
  hydration-payload `<script>` tag, AND the literal the client-side
  bootstrap reads via `document.getElementById(...)`.

  Spec 011 §Hydration payload script id pins this string as the
  contract between server-shell-emit and client-bootstrap-read. A user
  who overrides `:html-shell` MUST emit the payload `<script>` with
  this id (or substitute their own bootstrap that reads a custom id;
  the framework's bundled bootstrap reads only this id)."
  "__rf_payload")

(def ^:const root-manifest-marker-attribute
  "The BARE attribute marking a `<script type=\"application/edn\">` as a
  Root Manifest v1 element (Spec 011 §Root Manifest v1 — the wire form).

  It is a marker, not a carrier: it holds NO value and NO identity. A
  root's identity is read from the manifest's CONTENT (`:root-id`), and
  the manifest is bound to its root by ADJACENCY — it is the container's
  immediately following element sibling. Spelling the root-id into this
  attribute too would put identity on the wire twice and tempt a reader
  to trust the cheaper copy.

  It exists so an ordinary `application/edn` script — the `__rf_payload`
  hydration payload above, or a data island — is never mistaken for a
  manifest by the adjacency scan."
  "data-rf-root")
