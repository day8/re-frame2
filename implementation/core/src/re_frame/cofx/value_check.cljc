(ns re-frame.cofx.value-check
  "Shared structural-EDN check for recordable `:rf.cofx` values (rf2-6zfzxy,
  factoring the rf2-rmroo4 slice-A + slice-B clones).

  EP-0017:386 — a recordable coeffect value rides the durable causal record
  (epoch ledger, replay, SSR payload, Xray export) and so MUST be ordinary EDN
  data that reads back unchanged. A host handle (DOM node, `Promise`, function,
  atom, `Date`, any JS / Java object) folded in corrupts durable state SILENTLY:
  the failure surfaces far away, at replay / Xray / SSR time, not at the bad
  coeffect. The structural-EDN floor catches it at the source — ALWAYS-ON, a
  hard error in production as well as dev (rf2-q34j26).

  Two write sites fold recordable `:rf.cofx` values into the in-flight token:

    - SUPPLIED — a caller-supplied `:rf.cofx` override at the dispatch boundary
      (`re-frame.router.diagnostics/validate-supplied-cofx-values!`, slice A);
    - GENERATED — a generator-backed recordable fact minted at processing-start
      (`re-frame.cofx/validate-generated-recordable-value!`, slice B).

  Both emitted the SAME `:rf.error/cofx-value-invalid` (reason
  `:non-edn-recordable-value`) with the SAME path-rooting, the SAME `:preview`-
  only-when-itself-recordable rule, the SAME two-channel fan-out, and the SAME
  `:extra` discriminator shape — differing only by the `:supplied` / `:generated`
  noun in the human message, the throw `where` symbol, and which always-on
  listener positional slot (`event` vs `frame`) the source carries. [[check-edn-value!]]
  is the ONE shared definition both delegate to; behaviour is byte-identical.

  Requires `error` / `interop` / `late-bind` / `recordable` — exactly the four
  both call sites already required; this leaf ns adds no load cycle (nothing
  those four reach requires cofx or router.diagnostics)."
  (:require [re-frame.error      :as error]
            [re-frame.interop    :as interop]
            [re-frame.late-bind  :as late-bind]
            [re-frame.recordable :as recordable]))

#?(:clj (set! *warn-on-reflection* true))

;; The two write-site shapes, table-driven so the only thing that varies is
;; DATA (rf2-6zfzxy). `:noun` opens the human message; `:durable-clause` is the
;; trailing how-and-fix sentence pair (the only prose that genuinely differs);
;; `:where` is the throw site symbol the canonical builder stamps.
(def ^:private kind->shape
  {:supplied  {:noun           "Supplied"
               :where          're-frame.router/build-envelope
               :durable-clause (str "Recordable coeffect "
                                    "values ride the durable causal record "
                                    "(epoch ledger, replay, SSR payload, Xray) "
                                    "and MUST be ordinary EDN data that reads "
                                    "back unchanged (EP-0017:386). Replace the "
                                    "host handle with its recordable "
                                    "projection — the identifier / snapshot / "
                                    "plain data you actually need on replay.")}
   :generated {:noun           "Generated"
               :where          're-frame.cofx/run-generator
               :durable-clause (str "A generator-backed "
                                    "recordable coeffect rides the durable "
                                    "causal record (it is written back into "
                                    "`:rf.cofx`, folded into the epoch ledger, "
                                    "replayed, shipped in the SSR payload, read "
                                    "by Xray) and MUST mint ordinary EDN data "
                                    "that reads back unchanged (EP-0017:386). "
                                    "Have the generator return the recordable "
                                    "projection — the identifier / snapshot / "
                                    "plain data you actually need on replay.")}})

(defn check-edn-value!
  "ALWAYS-ON structural-EDN check of a recordable `:rf.cofx` `value` for
  `cofx-id` (rf2-6zfzxy — the shared slice-A / slice-B floor). When `value`
  carries a non-recordable leaf, fan `:rf.error/cofx-value-invalid` (reason
  `:non-edn-recordable-value`) out through BOTH error channels and throw; an
  all-EDN value passes and returns `value`.

  `kind` is `:supplied` (a caller-supplied override at the dispatch boundary)
  or `:generated` (a generator write-back) — it selects the human-message noun,
  the throw `where` symbol, and the always-on listener's source slot (the
  supplied path carries the triggering `event`; the generated path carries the
  `frame-id`). `failing-id` is the always-on record's event-id (the supplied
  path's triggering event-id, the generated path's failing fact id). `event`
  and `frame-id` are mutually-exclusive source context — pass the one the kind
  carries and `nil` for the other. The reported path is rooted at `cofx-id`
  here, so both call sites hand the bare per-value walk result.

  Byte-identical to the two clones it replaced: same emitted category + reason,
  same `:preview`-only-when-itself-recordable rule, same `cond->` payload, same
  `:extra` discriminator shape, same human-message bytes per kind."
  [kind cofx-id value failing-id event frame-id]
  (when-let [bad (recordable/explain-non-recordable value)]
    (let [{:keys [path bad-type]} (update bad :path #(into [cofx-id] %))
          preview                 (recordable/safe-preview value)
          {:keys [noun where durable-clause]} (kind->shape kind)]
      ;; Both channels via the shared helper (rf2-c4oycd): axis 1 the always-on
      ;; listener (survives prod elision), axis 2 the dev trace (DCEs under
      ;; `:advanced` + `goog.DEBUG=false`). Reached via the
      ;; `:error-emit/emit-error-both` hook (this ns cannot static-require
      ;; error-emit — load cycle). `elapsed-ms 0`.
      (when-let [emit-error-both! (late-bind/get-fn-cached :error-emit/emit-error-both)]
        (emit-error-both! :rf.error/cofx-value-invalid
                          event failing-id frame-id nil 0 (interop/now-ms)
                          (cond-> {:rf.cofx/id        cofx-id
                                   :failing-id        failing-id
                                   :rf.trace/event-id failing-id
                                   :reason            :non-edn-recordable-value
                                   :path              path
                                   :bad-type          bad-type
                                   :recovery          :no-recovery}
                            (some? preview) (assoc :preview preview)
                            frame-id        (assoc :frame frame-id))))
      (error/throw-error!
        :rf.error/cofx-value-invalid where
        (str noun " `:rf.cofx` fact `" cofx-id
             "` is not recordable EDN data: the value "
             "at path " (pr-str path) " is a `"
             bad-type "` (a host object — DOM node, "
             "Promise, function, atom, Date, or other "
             "JS / Java handle). " durable-clause)
        ;; The structured sub-kind that distinguishes a structural-EDN failure
        ;; from a declared-`:schema` miss rides its own `:rf.cofx/value-error`
        ;; slot — `:reason` is reserved for the human sentence per the central
        ;; thrown-error builder (Spec 009 §The thrown-error shape, rf2-vvixub).
        {:extra {:rf.cofx/value-error :non-edn-recordable-value
                 :rf.cofx/id          cofx-id
                 :failing-id          failing-id
                 :path                path
                 :bad-type            bad-type}})))
  value)
