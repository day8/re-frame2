(ns re-frame.reg-meta
  "Registration-metadata KEY classification — the no-silent-swallow guard for
  the `reg-*` middle slot (rf2-x68lzo).

  Per [Conventions §No silent swallow](../../../../spec/Conventions.md#no-silent-swallow--recognised-input-must-signal):
  a user-supplied value recognised as input but not honourable MUST signal;
  silent ignore is allowed ONLY for explicitly NAMESPACED extension keys in an
  open map. Every core `reg-*` registrar accepts an optional metadata map in its
  middle slot (Spec 001 §Registration grammar; Spec-Schemas §`:rf/registration-metadata`).
  The map is OPEN — but open for NAMESPACED extension keys ONLY. A BARE
  (unqualified) key the framework does not recognise reads as a typo of a real
  key and MUST signal.

  This is the registration-time (off the hot path) THREE-WAY classifier every
  affected registrar funnels its user metadata through:

    known bare key   -> parse (accepted; the kind reads it)
    namespaced key   -> open extension, ignored (`:myapp/*`, `:rf.cofx/requires`, …)
    unknown bare key -> `:rf.warning/unknown-registration-key` dev warning naming
                        the key and the kind's valid vocabulary. A WARNING, not an
                        error: the cascade continues safely — the key is stored on
                        the registrar entry but unread (per Conventions: warn when
                        the cascade can continue, error when it cannot).
    retired bare key -> `:rf.error/retired-registration-key` HARD ERROR naming the
                        canonical replacement. The canonical case is the v1 `:spec`
                        key (renamed to `:schema` per MIGRATION §M-54): silently
                        swallowing it DISABLES the registration's payload validation
                        — a soft-pass hiding bugs — so the swallow is dangerous, not
                        merely wasteful, and must fail loud in dev AND prod.

  Precedent: `re-frame.routing.registry/validate-route-metadata!` (rf2-45b95)
  applies the same rule to `reg-route`; the retired-key redirect mirrors
  `re-frame.image/check-retired-keys!` (EP-0026) — a `{retired-key replacement}`
  table driving an actionable migration diagnostic."
  (:require [re-frame.error   :as error]
            [re-frame.interop :as interop]
            [re-frame.trace   :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the per-kind canonical bare-key vocabulary ---------------------------
;;
;; The bare (unqualified) registration-metadata keys each `reg-*` kind reads,
;; per Spec-Schemas §`:rf/registration-metadata` (the shared base) + the per-kind
;; refinements (`:rf/event-handler-meta`, `:rf/sub-meta`, `:rf/fx-meta`,
;; `:rf/cofx-meta`, `:rf/interceptor-meta`). NAMESPACED keys are the open
;; extension surface and are never listed here (they always pass). Widening a
;; kind's vocabulary is a Spec change (the per-kind schema is the source of truth).

(def ^:private base-bare-keys
  "Base `:rf/registration-metadata` bare keys accepted on EVERY `reg-*` kind
  (Spec-Schemas §`:rf/registration-metadata`). `:ns` / `:line` / `:column` /
  `:file` are the macro-supplied source coords — normally merged AFTER this walk,
  but listed so a re-registration composing a stored meta map still passes."
  #{:doc :schema :tags :platforms :ns :line :column :file})

(def ^:private classification-bare-keys
  "The EP-0025 registration-owned data-classification bare keys, accepted on the
  four classification-bearing kinds (`:event` / `:sub` / `:fx` / `:cofx` all run
  `re-frame.classification/validate-classification!`)."
  #{:sensitive :large :large?})

(def known-bare-keys
  "Per-kind canonical set of accepted BARE registration-metadata keys. Any bare
  key outside a kind's set (and not in `retired-bare-keys`) is an unknown key —
  a likely typo — and warns. Namespaced keys are never checked against this set."
  {:event       (into base-bare-keys (conj classification-bare-keys :interceptors))
   :sub         (into base-bare-keys classification-bare-keys)
   :fx          (into base-bare-keys classification-bare-keys)
   :cofx        (into base-bare-keys (into classification-bare-keys [:recordable? :provided?]))
   :interceptor base-bare-keys})

(def retired-bare-keys
  "Retired v1 BARE registration-metadata key -> its canonical v2 replacement.
  A `reg-*` metadata map carrying one of these fails loud with an actionable
  migration diagnostic (mirrors `re-frame.image/check-retired-keys!`), rather
  than silently swallowing it. `:spec` is the v1 payload-schema key renamed to
  `:schema` (MIGRATION §M-54); swallowing it disables payload validation — the
  worst-case silent swallow this guard closes."
  {:spec :schema})

;; ---- the three-way classifier ---------------------------------------------

(defn- kind-display-name
  "The user-facing surface name for `where-sym` (e.g. `rf/reg-event` -> \"reg-event\")
  used to LEAD the diagnostic so the message names the exact registrar."
  [where-sym]
  (name where-sym))

(defn validate-registration-metadata!
  "Classify the bare keys of a `reg-*` registration `metadata` map at
  registration time (rf2-x68lzo). `kind` selects the per-kind vocabulary
  (`known-bare-keys`); `where-sym` is the quoted public surface symbol
  (`'rf/reg-event`, …) stamped on any throw; `id` names the registration.

  - A RETIRED bare key (`retired-bare-keys`, e.g. `:spec`) throws
    `:rf.error/retired-registration-key` naming the canonical replacement — a
    HARD error in dev AND prod (the swallow disables a downstream contract).
  - An UNKNOWN bare key (bare, not known, not retired) emits the dev-gated
    `:rf.warning/unknown-registration-key` naming the key(s) and the kind's valid
    vocabulary — the cascade continues (the key is stored but unread).
  - NAMESPACED keys and known bare keys pass silently.

  A non-map `metadata` is a no-op here: each registrar polices its own middle-slot
  SHAPE separately (e.g. `:rf.error/reg-event-bad-middle-slot`). Returns
  `metadata` unchanged so it composes in a threading position."
  [kind where-sym id metadata]
  (when (map? metadata)
    (let [known   (get known-bare-keys kind base-bare-keys)
          ks      (keys metadata)
          retired (into [] (filter #(contains? retired-bare-keys %)) ks)]
      ;; Retired keys first — the hard error takes precedence over the warning.
      (when (seq retired)
        (let [k           (first retired)
              replacement (get retired-bare-keys k)]
          (error/throw-error!
            :rf.error/retired-registration-key
            where-sym
            (str (kind-display-name where-sym) " for `" id "` declares the RETIRED "
                 "registration-metadata key `" k "` — use `" replacement "` instead "
                 "(the v1 name was renamed; MIGRATION §M-54). A retired bare key is "
                 "rejected rather than silently swallowed (swallowing `:spec` would "
                 "disable this registration's payload validation — a soft-pass hiding "
                 "bugs), per Conventions §No silent swallow.")
            {:recovery :fix-registration
             :extra    {:kind        kind
                        :id          id
                        :retired-key k
                        :replacement replacement}})))
      ;; Unknown bare keys — a dev-gated warning (the cascade continues safely).
      ;; `remove qualified-keyword?` keeps the open namespaced-extension carve-out.
      (when interop/debug-enabled?
        (let [unknown (into []
                            (comp (remove qualified-keyword?)
                                  (remove known)
                                  (remove #(contains? retired-bare-keys %)))
                            ks)]
          (when (seq unknown)
            (trace/emit! :warning :rf.warning/unknown-registration-key
                         {:kind         kind
                          :id           id
                          :unknown-keys unknown
                          :known        (vec (sort known))
                          :recovery     :warned
                          :reason
                          (str (kind-display-name where-sym) " for `" id "` declares "
                               "unknown metadata "
                               (if (= 1 (count unknown)) "key " "keys ")
                               (pr-str unknown)
                               " — bare keys outside the recognised set are likely "
                               "typos and are ignored (never read). Host/app extension "
                               "keys must be NAMESPACED (e.g. `:myapp/analytics-id`). "
                               "Recognised bare keys for this kind: "
                               (pr-str (vec (sort known))) ".")}))))))
  metadata)
