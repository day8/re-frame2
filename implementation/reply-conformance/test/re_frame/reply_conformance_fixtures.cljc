(ns re-frame.reply-conformance-fixtures
  "Shared reply-conformance fixture constants (rf2-b2a3a2 — extracted from the
  reply-conformance tier's suites: `reply-vocab-conformance`,
  `reply-egress-projection`).

  One fact is duplicated verbatim across the tier and is owned here:

    - `completion-time-ms` — the durable EP-0017 causal completion time
      (the value the LIVE reply dispatch carries as the flat `:rf.cofx`
      `:rf/time-ms` fact). The suites each declared the SAME magic
      `1781078400456` (twice as a literal inside the vocab suite's
      resource/mutation success builders). One source of truth here keeps
      the durable-completion-fact value aligned across the tier.

  (rf2-9dc84j retired the shared `canonical-ok-reply` builder: the synthetic
  functor suite it fed was deleted, and its sole remaining egress use was
  inlined — the reply-conformance tier no longer claims family-level
  relocation conformance.)

  KEPT WITHIN reply-conformance (NOT cross-corpus-shared into
  event-conformance — separate `deps.edn`, higher friction). This ns is
  NOT a test (no `-cljs-test$` suffix), so the `npm run test:cljs`
  node-test gate compiles it as a required namespace but never runs it as
  a deftest; the JVM `clojure -M:test` runner likewise only scans the
  `*-test` namespaces.")

;; ---------------------------------------------------------------------------
;; EP-0017 — the durable causal completion time the reply-conformance tier
;; threads onto a reply as `:completed-at` (the same value the LIVE reply
;; dispatch carries as the flat `:rf.cofx` `:rf/time-ms` fact — the single
;; framework-provided recordable coeffect, stamped at the causal boundary;
;; see `re-frame.router` §`:rf.cofx`).
;; ---------------------------------------------------------------------------

(def completion-time-ms
  "The durable EP-0017 causal completion-time fixture value (ms since
  epoch). Shared by every reply-conformance suite that seeds a
  `:completed-at`."
  1781078400456)

