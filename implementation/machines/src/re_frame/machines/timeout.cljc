(ns re-frame.machines.timeout
  "State-level + spawn-level `:timeout` / `:on-timeout` grammar (EP-0029 A4).

  ## What `:timeout` is

  A state may declare a `:timeout` duration plus an `:on-timeout` transition
  that fires when the machine sits in that state past the duration. A
  `:spawn`-bearing state may declare `:timeout` / `:on-timeout` ON the spawn
  spec — a wall-clock guard on the child's completion, anchored to the
  spawn-bearing state's entry.

      {:waiting {:timeout \"PT5S\"
                 :on-timeout {:target :timed-out}}

       :loading {:spawn {:machine-id :fetch-user
                         :timeout \"PT10S\"
                         :on-timeout {:target :timed-out}}}}

  ## Relationship to `:after` — distinct intent, ONE mechanism

  Per EP-0029 A4: `:timeout` and the existing `:after` express DIFFERENT
  intent and MAY coexist on the same state node. `:after` is the general
  delayed-transition table (`{ms -> transition}`, possibly several entries
  with dynamic / sub-vector / fn delays); `:timeout` names the single
  \"this state (or child) must finish before this time\" fact more clearly.

  re-frame2 keeps ONE timer mechanism. `:timeout` / `:on-timeout`
  DESUGARS — at registration / transition normalisation time — into an
  `:after` entry keyed by the resolved-ms duration, mapping to the
  `:on-timeout` transition. The whole downstream `:after` machinery
  (scheduling, per-decl-path epoch staleness, cancel-on-exit, the
  `:rf.machine.timer/*` traces) then drives it unchanged — so leaving the
  state cancels the timeout, and (for the spawn case) child completion
  exits the spawn-bearing state and cancels it, with no duplicate timer
  code. The grammar is distinct; the runtime is reused.

  Spawn-level `:timeout` desugars onto the SPAWN-BEARING STATE's `:after`
  (not the spawn spec) — the timer is anchored to that state's entry, so
  the child's whole lifetime (spanning any internal retries) is bounded,
  exactly the wall-clock semantics A4 asks for; the standard exit cascade
  tears the child down when the timeout fires.

  ## Duration grammar (DIVERGENCE from XState — operator-ruled, EP-0029 A4)

  XState v6 accepts readable duration shorthand such as `\"10ms\"` / `\"5s\"`
  AND ISO-8601 durations such as `\"PT2M\"`. re-frame2 REJECTS the
  `\"10ms\"` / `\"5s\"` shorthand. A `:timeout` duration is ONE of:

    - a POSITIVE INTEGER — literal milliseconds (`5000`);
    - an ISO-8601 duration STRING — `\"PT5S\"`, `\"PT2M\"`, `\"PT1H30M\"`,
      `\"PT0.5S\"`, … (the `PnYnMnDTnHnMnS` form, time component only for
      sub-day durations; the date component `Y/M/D/W` is also parsed).

  Anything else — a `\"5s\"` / `\"10ms\"` shorthand string, a non-positive
  integer, a malformed ISO string, a fn / vector — fails LOUD at
  registration with `:rf.error/machine-bad-timeout-duration`. (Unlike
  `:after`, `:timeout` does NOT admit sub-vector / fn dynamic delays — a
  timeout is a fixed wall-clock deadline.)"
  (:require [clojure.string :as str]
            [re-frame.error :as error]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- ISO-8601 duration parsing --------------------------------------------
;;
;; A minimal, host-independent ISO-8601 duration parser. The grammar:
;;
;;   PnYnMnDTnHnMnS   (a leading `P`; an optional date part Y/M/D; an
;;                     optional `T` then a time part H/M/S; `W` weeks is a
;;                     standalone alternative date part — `P2W`)
;;
;; We deliberately implement our own (rather than `java.time.Duration`) so
;; the parser is IDENTICAL on the JVM and in CLJS — the conformance corpus
;; pins ms results cross-host, and `java.time.Duration` has no CLJS analogue.
;; Year / month are calendar-relative; a `:timeout` is a wall-clock window,
;; so we use fixed conventional lengths (365-day year, 30-day month) — the
;; same fixed-length convention used for the rare Y/M timeout (a sub-second
;; / minute / hour / day timeout, the overwhelmingly common case, is exact).

(def ^:private iso-duration-re
  ;; PnYnMnDTnHnMnS, or PnW. Each numeric group is optional; the `T`
  ;; separator is required iff a time component (H/M/S) is present. Seconds
  ;; may be fractional. Captures: 1=Y 2=M(onth) 3=W 4=D 5=H 6=M(inute)
  ;; 7=S(econds, fractional ok).
  #"(?i)^P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$")

(def ^:private ms-per
  {:year   (* 365 24 60 60 1000)
   :month  (* 30 24 60 60 1000)
   :week   (* 7 24 60 60 1000)
   :day    (* 24 60 60 1000)
   :hour   (* 60 60 1000)
   :minute (* 60 1000)})

(defn- parse-long*
  "Parse a decimal integer string to a long; nil-safe (nil → 0)."
  [s]
  (if (nil? s) 0 #?(:clj (Long/parseLong s) :cljs (js/parseInt s 10))))

(defn- parse-double*
  "Parse a (possibly fractional) seconds string to a double; nil-safe
  (nil → 0.0)."
  [s]
  (if (nil? s) 0.0 #?(:clj (Double/parseDouble s) :cljs (js/parseFloat s))))

(defn parse-iso-8601-ms
  "Parse an ISO-8601 duration STRING to a positive-integer millisecond
  count, or nil if `s` is not a well-formed ISO-8601 duration (or resolves
  to a non-positive ms).

  Accepts the `PnYnMnWnDTnHnMnS` form (any component optional; fractional
  seconds allowed; `P0D` / a zero total resolves to nil since a zero
  timeout is meaningless). Year / month use the fixed 365-day / 30-day
  convention (see ns docstring). Rejects the bare `\"P\"`, empty strings,
  the XState `\"5s\"` / `\"10ms\"` shorthand (no leading `P`), and anything
  the regex does not match."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (when-let [m (re-matches iso-duration-re s)]
      (let [[_ y mo w d h mi sec] m
            ;; The bare `P` (or `PT`) with NO numeric component is a
            ;; degenerate match — every capture is nil. Reject it: a
            ;; duration must name at least one component.
            any? (some some? [y mo w d h mi sec])]
        (when any?
          (let [ms (+ (* (parse-long* y)  (:year ms-per))
                      (* (parse-long* mo) (:month ms-per))
                      (* (parse-long* w)  (:week ms-per))
                      (* (parse-long* d)  (:day ms-per))
                      (* (parse-long* h)  (:hour ms-per))
                      (* (parse-long* mi) (:minute ms-per))
                      ;; seconds may be fractional → ms is rounded to the
                      ;; nearest integer ms.
                      (Math/round (* (parse-double* sec) 1000.0)))]
            (when (pos? ms)
              (long ms))))))))

;; ---- duration grammar (integer-ms OR ISO-8601 only) -----------------------

(defn resolve-duration-ms
  "Resolve a `:timeout` duration to a positive-integer ms, or nil if it is
  not a valid duration. A valid duration (EP-0029 A4, operator-ruled
  DIVERGENCE) is EXACTLY one of:

    - a POSITIVE INTEGER — literal ms;
    - an ISO-8601 duration STRING (`\"PT5S\"`, `\"PT2M\"`, …).

  Everything else — the XState `\"5s\"` / `\"10ms\"` shorthand, a
  non-positive / non-integer number, a fn, a vector, nil — resolves to nil
  (the caller fails loud). Unlike `:after`, NO sub-vector / fn dynamic
  forms: a timeout is a fixed wall-clock deadline."
  [duration]
  (cond
    (and (integer? duration) (pos? duration)) (long duration)
    (string? duration)                        (parse-iso-8601-ms duration)
    :else                                     nil))

(defn valid-duration?
  "True iff `duration` is a valid `:timeout` duration per
  `resolve-duration-ms` (a positive integer or a well-formed ISO-8601
  string)."
  [duration]
  (some? (resolve-duration-ms duration)))

;; ---- desugaring `:timeout` / `:on-timeout` → `:after` ---------------------
;;
;; `:timeout` / `:on-timeout` is a DISTINCT authoring concept that LOWERS
;; onto the existing `:after` timer mechanism (EP-0029 A4 — "distinct
;; intent, one mechanism"). The desugar runs at registration / transition
;; normalisation time so the runtime never sees `:timeout` / `:on-timeout`
;; directly — it sees the equivalent `:after` entry. The grammar (separate
;; keys, separate validation, separate duration rules) is what the AUTHOR
;; writes; the `:after` table is what the ENGINE drives.

(defn- desugar-state-timeout
  "Desugar a single state-node's `:timeout` / `:on-timeout` into an `:after`
  entry keyed by the resolved ms, then drop the `:timeout` / `:on-timeout`
  keys. A node with no `:timeout` is returned unchanged. The synthetic
  `:after` entry is MERGED into any existing `:after` map — `:timeout` and
  `:after` coexist (A4). When the resolved ms collides with an existing
  `:after` delay-key, the existing `:after` entry WINS (it was authored
  explicitly at that delay); validation rejects that collision loudly
  before this runs, so in practice the merge never silently drops a
  timeout."
  [node]
  (if-not (contains? node :timeout)
    node
    (let [ms (resolve-duration-ms (:timeout node))]
      ;; `ms` is guaranteed non-nil here — `validate-timeouts!` ran first
      ;; and rejected an unresolvable duration. The `(when ms …)` guard is
      ;; belt-and-braces for a direct (unvalidated) caller.
      (cond-> (dissoc node :timeout :on-timeout)
        ms (update :after (fn [a] (merge {ms (:on-timeout node)} a)))))))

(defn- desugar-spawn-timeout
  "Desugar a `:spawn`-bearing state-node's spawn-level `:timeout` /
  `:on-timeout` onto the STATE's `:after` (anchored to the state's entry,
  per the ns docstring), then drop the spawn-spec's `:timeout` /
  `:on-timeout` keys. A spawn-spec with no `:timeout` leaves the node
  unchanged."
  [node]
  (let [spawn (:spawn node)]
    (if-not (and (map? spawn) (contains? spawn :timeout))
      node
      (let [ms (resolve-duration-ms (:timeout spawn))]
        (cond-> (assoc node :spawn (dissoc spawn :timeout :on-timeout))
          ms (update :after (fn [a] (merge {ms (:on-timeout spawn)} a))))))))

(defn desugar-node-timeouts
  "Desugar BOTH the state-level `:timeout` / `:on-timeout` and the
  spawn-level `:timeout` / `:on-timeout` on one state-node into `:after`
  entries on the node, dropping the timeout keys. The single per-node
  transform the tree-walker applies. A node with neither timeout form is
  returned unchanged (identity-preserving)."
  [node]
  (-> node desugar-state-timeout desugar-spawn-timeout))

(defn- walk-states
  "Recursively desugar timeouts on every node under a `:states` map (and
  the spawn specs nested within), returning the rewritten `:states` map.
  Nil-safe."
  [states]
  (when states
    (reduce-kv
      (fn [acc k node]
        (assoc acc k
               (let [node' (desugar-node-timeouts node)]
                 (cond-> node'
                   (:states node') (update :states walk-states)))))
      {}
      states)))

(defn- node-has-timeout?
  "True iff `node` (a state-node map) carries a state-level OR spawn-level
  `:timeout` / `:on-timeout` key. The leaf check the fast-path scan uses."
  [node]
  (and (map? node)
       (or (contains? node :timeout)
           (contains? node :on-timeout)
           (let [spawn (:spawn node)]
             (and (map? spawn)
                  (or (contains? spawn :timeout)
                      (contains? spawn :on-timeout)))))))

(defn- any-timeout?
  "True iff ANY node in the machine (root, every state under `:states`,
  every region root + its states) declares a `:timeout` / `:on-timeout`.
  A cheap-as-possible structural scan — short-circuits on the first hit —
  that lets `desugar-timeouts` return the input UNCHANGED (no map rebuild)
  for the overwhelmingly common timeout-free machine on the hot
  per-transition path."
  [machine]
  (letfn [(scan-states [states]
            (some (fn [[_ n]]
                    (or (node-has-timeout? n)
                        (scan-states (:states n))))
                  states))]
    (or (node-has-timeout? machine)
        (scan-states (:states machine))
        (some (fn [[_ body]]
                (or (node-has-timeout? body)
                    (scan-states (:states body))))
              (:regions machine)))))

(defn desugar-timeouts
  "Rewrite a whole machine spec so every state-level and spawn-level
  `:timeout` / `:on-timeout` is lowered into the equivalent `:after`
  entry, leaving NO `:timeout` / `:on-timeout` keys for the runtime to
  see. Pure; idempotent. Handles flat / compound machines (`:states`) and
  parallel machines (`:regions` — each region body is a state-node with
  its own `:states`). The machine root itself MAY carry a `:timeout` (a
  whole-machine deadline anchored to birth — symmetric with a root
  `:after`), which is desugared onto the root `:after`.

  Fast-path: a machine with NO `:timeout` / `:on-timeout` anywhere is
  returned UNCHANGED (no map rebuild), so the timeout-free common case on
  the hot per-transition path pays only one short-circuiting scan.

  This is the single normalisation seam the registration parser and the
  pure `machine-transition` entry both apply, so registration validation,
  the transition engine, and the conformance `:machine-transition` op all
  observe the SAME desugared `:after` form (they can never drift)."
  [machine]
  (if-not (any-timeout? machine)
    machine
  (let [;; Root-level whole-machine `:timeout` desugars onto the root
        ;; `:after` (decl-path []). The root is a node-shaped map, so reuse
        ;; the same node transform — but on the root MINUS `:states` /
        ;; `:regions` (those are walked separately below; the node
        ;; transform's `(:states node)` recursion would otherwise
        ;; double-walk). The transform drops the root `:timeout` /
        ;; `:on-timeout` keys and folds them into the root `:after`.
        root'    (desugar-node-timeouts (dissoc machine :states :regions))
        machine' (cond-> (dissoc machine :timeout :on-timeout)
                   (contains? root' :after) (assoc :after (:after root')))]
    (cond-> machine'
      (:states machine')  (update :states walk-states)
      (:regions machine') (update :regions
                                  (fn [regions]
                                    (reduce-kv
                                      (fn [acc rn body]
                                        (assoc acc rn
                                               (cond-> (desugar-node-timeouts
                                                         (dissoc body :states))
                                                 (:states body)
                                                 (assoc :states (walk-states (:states body))))))
                                      {}
                                      regions)))))))

;; ---- registration-time validation -----------------------------------------
;;
;; Validation runs on the RAW (pre-desugar) machine so error messages name
;; the `:timeout` / `:on-timeout` keys the author wrote. It enforces, per
;; EP-0029 A4 + Backwards-Compatibility:
;;
;;   - a `:timeout` REQUIRES an `:on-timeout` (and vice-versa — an
;;     `:on-timeout` with no `:timeout` is meaningless);
;;   - the duration is integer-ms OR ISO-8601 only (reject the `"5s"` /
;;     `"10ms"` shorthand) — `:rf.error/machine-bad-timeout-duration`;
;;   - a `:choice` state must not declare `:timeout` (that constraint is
;;     owned by the `:choice` wave; not enforced here);
;;   - a desugared timeout ms must not collide with an explicit `:after`
;;     delay-key on the same node — `:rf.error/machine-timeout-after-collision`
;;     (a silent merge-drop would lose one of the two authored intents).

(defn- timeout-error
  "Build a `:timeout`-grammar validation ex-info with the canonical
  thrown-error shape (Spec 009). `error-id` is the `:rf.error/id`
  discriminator; `reason` is the human diagnostic; `extra` merges
  per-site slots."
  [error-id reason extra]
  (error/thrown-ex-info error-id 'rf/reg-machine reason
                        {:recovery :fix-registration :extra extra}))

(defn- validate-one-timeout!
  "Validate a single `(timeout, on-timeout)` pair declared at `site` (a
  diagnostic label: `:state` or `:spawn`) on `state-key`. The pair may be
  fully absent (no-op) but if EITHER key is present BOTH must be, and the
  duration must resolve."
  [state-key site has-timeout? timeout has-on-timeout? on-timeout]
  (cond
    ;; Neither key — nothing to validate.
    (and (not has-timeout?) (not has-on-timeout?))
    nil

    ;; `:timeout` without `:on-timeout` — a timeout with no transition is
    ;; meaningless (A4: "`:timeout` requires `:on-timeout`").
    (and has-timeout? (not has-on-timeout?))
    (throw (timeout-error
             :rf.error/machine-timeout-without-on-timeout
             (str (if (= site :spawn) "a :spawn-level" "a state-level")
                  " :timeout on state " state-key
                  " declares no :on-timeout — a :timeout MUST name the"
                  " transition it fires (EP-0029 A4). Add :on-timeout, or"
                  " remove :timeout.")
             {:state state-key :site site :timeout timeout}))

    ;; `:on-timeout` without `:timeout` — symmetric: the transition has no
    ;; deadline that fires it.
    (and has-on-timeout? (not has-timeout?))
    (throw (timeout-error
             :rf.error/machine-on-timeout-without-timeout
             (str (if (= site :spawn) "a :spawn-level" "a state-level")
                  " :on-timeout on state " state-key
                  " declares no :timeout — an :on-timeout transition has no"
                  " deadline to fire it (EP-0029 A4). Add :timeout, or"
                  " remove :on-timeout.")
             {:state state-key :site site :on-timeout on-timeout}))

    ;; Both present — the duration must be integer-ms OR ISO-8601 (reject
    ;; the XState "5s" / "10ms" shorthand and every other malformed form).
    (not (valid-duration? timeout))
    (throw (timeout-error
             :rf.error/machine-bad-timeout-duration
             (str (if (= site :spawn) "a :spawn-level" "a state-level")
                  " :timeout duration " (pr-str timeout) " on state "
                  state-key " is invalid — a :timeout duration must be a"
                  " POSITIVE INTEGER (literal ms, e.g. 5000) or an ISO-8601"
                  " duration STRING (e.g. \"PT5S\", \"PT2M\", \"PT1H30M\")."
                  " The XState \"5s\" / \"10ms\" shorthand is REJECTED"
                  " (EP-0029 A4 operator-ruled divergence).")
             {:state state-key :site site :timeout timeout}))

    :else nil))

(defn- validate-node-timeouts!
  "Validate one state-node's state-level AND spawn-level timeout pairs, and
  reject a desugared-timeout ms that collides with an explicit `:after`
  delay-key on the same node."
  [state-key node]
  (let [has-st?  (contains? node :timeout)
        has-sot? (contains? node :on-timeout)
        spawn    (:spawn node)
        has-sp?  (and (map? spawn) (contains? spawn :timeout))
        has-spo? (and (map? spawn) (contains? spawn :on-timeout))]
    (validate-one-timeout! state-key :state has-st? (:timeout node)
                           has-sot? (:on-timeout node))
    (validate-one-timeout! state-key :spawn has-sp? (when (map? spawn) (:timeout spawn))
                           has-spo? (when (map? spawn) (:on-timeout spawn)))
    ;; Collision check — both timeouts are now known well-formed, so their
    ;; ms is resolvable. A resolved ms equal to an explicit `:after`
    ;; delay-key would have the explicit `:after` shadow it in the desugar
    ;; merge (the explicit entry wins), silently dropping the timeout. Fail
    ;; loud instead.
    (let [after-keys (set (keys (:after node)))
          collide!   (fn [ms which]
                       (when (and ms (contains? after-keys ms))
                         (throw (timeout-error
                                  :rf.error/machine-timeout-after-collision
                                  (str "the " which " :timeout on state " state-key
                                       " resolves to " ms "ms, which is ALSO an"
                                       " explicit :after delay-key on the same"
                                       " state. The two would collide when the"
                                       " timeout desugars onto :after. Give the"
                                       " timeout a distinct duration, or fold it"
                                       " into the :after entry directly.")
                                  {:state state-key :ms ms :after-keys after-keys}))))
          st-ms (when has-st? (resolve-duration-ms (:timeout node)))
          sp-ms (when has-sp? (resolve-duration-ms (:timeout spawn)))]
      (when st-ms (collide! st-ms "state-level"))
      (when sp-ms (collide! sp-ms ":spawn-level"))
      ;; A state-level AND a spawn-level timeout on the SAME node that resolve
      ;; to the SAME ms would both desugar onto `:after {ms …}` and one would
      ;; silently overwrite the other in the merge. Fail loud — the author
      ;; must give them distinct durations.
      (when (and st-ms sp-ms (= st-ms sp-ms))
        (throw (timeout-error
                 :rf.error/machine-timeout-after-collision
                 (str "the state-level :timeout and the :spawn-level :timeout"
                      " on state " state-key " both resolve to " st-ms "ms —"
                      " they would collide when desugared onto :after,"
                      " dropping one. Give them distinct durations.")
                 {:state state-key :ms st-ms :after-keys after-keys}))))))

(defn- walk-nodes
  "Yield every `[state-key state-node]` pair under a `:states` map,
  recursing through nested `:states`. Nil-safe (nil → empty)."
  [states]
  (when states
    (mapcat (fn [[k node]]
              (cons [k node]
                    (walk-nodes (:states node))))
            states)))

(defn validate-timeouts!
  "Registration-time validator for the `:timeout` / `:on-timeout` grammar
  (EP-0029 A4). Walks every state-node (flat / compound / parallel-region)
  PLUS the machine root and each parallel-region root, and enforces:

    - a `:timeout` requires an `:on-timeout` and vice-versa
      (`:rf.error/machine-timeout-without-on-timeout` /
      `:rf.error/machine-on-timeout-without-timeout`);
    - the duration is integer-ms OR ISO-8601 only — the XState `\"5s\"` /
      `\"10ms\"` shorthand is rejected
      (`:rf.error/machine-bad-timeout-duration`);
    - a desugared-timeout ms must not collide with an explicit `:after`
      delay-key on the same node
      (`:rf.error/machine-timeout-after-collision`).

  Runs on the RAW machine (before desugaring) so diagnostics name the
  `:timeout` / `:on-timeout` keys the author wrote. Throws on the first
  violation; returns nil when every timeout is well-formed."
  [machine]
  ;; Root + region roots (a root `:timeout` is a whole-machine deadline).
  (validate-node-timeouts! :rf/root (dissoc machine :states :regions))
  (doseq [[rn body] (:regions machine)]
    (validate-node-timeouts! rn (dissoc body :states)))
  ;; Every per-region / flat state node.
  (let [node-pairs (if (seq (:regions machine))
                     (mapcat (fn [[_rn body]] (walk-nodes (:states body)))
                             (:regions machine))
                     (walk-nodes (:states machine)))]
    (doseq [[state-key node] node-pairs]
      (validate-node-timeouts! state-key node)))
  nil)
