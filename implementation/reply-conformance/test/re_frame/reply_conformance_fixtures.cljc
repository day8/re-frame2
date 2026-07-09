(ns re-frame.reply-conformance-fixtures
  "Shared reply-conformance fixture constants + builders (rf2-b2a3a2 —
  extracted from the reply-conformance tier's three suites:
  `reply-vocab-conformance`, `reply-functor-law`,
  `reply-egress-projection`).

  Two facts were duplicated verbatim across the tier and are owned here:

    - `completion-time-ms` — the durable EP-0017 causal completion time
      (the value the LIVE reply dispatch carries as the flat `:rf.cofx`
      `:rf/time-ms` fact). Three suites each declared the SAME magic
      `1781078400456` (twice as a literal inside the vocab suite's
      resource/mutation success builders). One source of truth here keeps
      the durable-completion-fact value aligned across the tier.

    - `canonical-ok-reply` — the canonical `:ok` reply SHAPE the functor
      (`route-reply`) and egress (`ok-reply`) suites each spelled inline:
      `:status :ok`, a `:value`, a `[:rf.work/* …]` `:work/id` tuple, a
      `:work/kind`, an `:rf.frame/id`, and the durable `:completed-at`
      causal completion fact. The varying slots (value / work-id /
      work-kind / frame-id / optional work-status) are parameters; the
      shape — and its `:completed-at` default — is owned here so a drift
      in the canonical `:ok` envelope shape is made once.

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

;; ---------------------------------------------------------------------------
;; The canonical `:ok` reply SHAPE. The varying slots are parameters; the
;; envelope shape + the durable `:completed-at` default are owned here.
;; ---------------------------------------------------------------------------

(defn canonical-ok-reply
  "Build the canonical `:ok` reply map (Managed-Effects §The uniform reply
  envelope): `:status :ok` + a `:value` + a `[:rf.work/* …]` `:work/id`
  tuple + a `:work/kind` + an `:rf.frame/id` + the durable `:completed-at`
  causal completion fact (defaulting to `completion-time-ms`).

  Opts:
    :value        — the decoded result (required).
    :work/id      — the `[:rf.work/* …]` attempt-identity tuple (required).
    :work/kind    — the family work-kind keyword (required).
    :rf.frame/id  — the owning frame (required).
    :rf.reply/work-status  — OPTIONAL; included only when supplied (the egress suite
                    pins `:completed`; the functor suite omits it).
    :completed-at — OPTIONAL; defaults to `completion-time-ms`."
  [{value        :value
    work-id      :work/id
    work-kind    :work/kind
    frame-id     :rf.frame/id
    work-status  :rf.reply/work-status
    completed-at :completed-at
    :or          {completed-at completion-time-ms}}]
  (cond-> {:status       :ok
           :value        value
           :work/id      work-id
           :work/kind    work-kind
           :rf.frame/id  frame-id
           :completed-at completed-at}
    (some? work-status) (assoc :rf.reply/work-status work-status)))
