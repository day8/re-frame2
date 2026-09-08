(ns re-frame2-pair-mcp.epoch-egress-guard-test
  "GUARD G3 — an epoch record must arrive at the egress door STAMPED.

  ## What is guarded, and why it is worth a file of its own

  `re-frame.core/project-egress` is the ONE record-level egress door
  since rf2-bv1p (ruling rf2-kuky.9 option A retired the standalone
  `projected-record` door), and it recognises an epoch record SOLELY by
  its stamped `:kind :rf/epoch-record`. There is no shape test and no
  second name to call.

  An UNRECOGNISED input is not refused by the door — it falls through to
  the KINDLESS BARE-VALUE WALK. That is the correct answer for the
  direct-read path and a LEAK for an epoch record: the bare walk starts
  at `:path []`, so a frame that declares `[:auth :token]` sensitive
  cannot match the record's `[:db-after :auth :token]` slot, NOTHING
  redacts, and the whole `:db-before` / `:db-after` snapshot crosses the
  MCP wire RAW to an off-box agent with `--allow-sensitive-reads` OFF.

  The trigger is real rather than hypothetical: an app running a
  pre-rf2-kuky.92 `re-frame.epoch.assembly` stamps no `:kind` at all, so
  a version-skewed pair session takes exactly that path. Every eval form
  this server emits therefore checks the stamp BEFORE the door call and
  THROWS otherwise. Silently shipping raw state is far worse than an
  error.

  ## How these tests pin it — three layers, and the middle one matters

  1. STRING level — the rendered source carries the `:kind` check, the
     `throw`, and `:rf.error/pair-mcp-unstamped-epoch-record`.
  2. READ level — the rendered source is READ back with
     `cljs.reader/read-string`. This is not ceremony: the guard is built
     by string concatenation with a nested string literal inside it, so
     a mis-escaped quote or a dropped paren would emit source that
     LOOKS right to a substring assertion and fails to compile app-side.
     Reading it proves the emitted text is well-formed CLJS data, and
     gives the structural assertions below something better than
     substring luck to stand on.
  3. DECISION level — the guard's PREDICATE is extracted from the read
     form and run against a stamped record and an unstamped one, so both
     directions are exercised on the rendered artefact rather than on a
     hand-copy of it. `eval-kind-pred` interprets exactly the two node
     shapes the guard renders and throws on anything else, so a
     rewritten guard fails here rather than being waved through.

  This suite is a Node build with no live app and no CLJS compiler, so
  it cannot EVAL the whole emitted form (that is the cross-server
  conformance harness's job — `tools/mcp-conformance`). Layer 3 is the
  closest this harness gets: the rendered decision, executed, both ways."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.tools.epoch-egress :as egress]))

;; ---------------------------------------------------------------------------
;; Fixtures + structural helpers
;; ---------------------------------------------------------------------------

(def ^:private stamped
  "What `re-frame.epoch.assembly` produces post-rf2-kuky.92 — the `:kind`
  stamp is the ONLY thing `project-egress` routes on."
  {:kind      :rf/epoch-record
   :frame     :app/main
   :db-after  {:auth {:token "s3cr3t"}}
   :db-before {:auth {:token "old"}}})

(def ^:private unstamped
  "The same record from an app whose `re-frame.epoch.assembly` predates
  rf2-kuky.92 — byte-identical payload, no `:kind`. This is the record
  that would be bare-walked from `:path []` and shipped RAW."
  (dissoc stamped :kind))

(defn- find-form
  "Depth-first: the first sub-form of `form` whose head is `head`.
  Searched structurally rather than by index so the guard stays findable
  when the surrounding `let` / `mapv` nesting changes."
  [head form]
  (cond
    (and (seq? form) (= head (first form))) form
    (coll? form)                            (some #(find-form head %) (seq form))
    :else                                   nil))

(defn- eval-kind-pred
  "Interpret the guard predicate READ out of the rendered source against
  `record`.

  Handles exactly the node shapes the guard renders — `(= …)`,
  `(:kind …)`, a keyword literal, and the record symbol — and THROWS on
  anything else. That refusal is the point: a guard rewritten into a
  shape this cannot read fails the test loudly instead of quietly
  passing on an unexercised predicate."
  [form record]
  (cond
    (seq? form)
    (let [op   (first form)
          args (rest form)]
      (cond
        (= '= op)    (apply = (map #(eval-kind-pred % record) args))
        (= :kind op) (:kind (eval-kind-pred (first args) record))
        :else        (throw (ex-info "unhandled op in rendered guard predicate"
                                     {:op op :form form}))))
    (keyword? form) form
    (symbol? form)  record
    :else           (throw (ex-info "unhandled node in rendered guard predicate"
                                    {:form form}))))

(defn- guard-of
  "Read `src` and return its `when-not` guard form."
  [src]
  (find-form 'when-not (reader/read-string src)))

;; ---------------------------------------------------------------------------
;; Layer 1 — the rendered SOURCE carries the check and the throw
;; ---------------------------------------------------------------------------

(deftest page-src-renders-the-kind-check-and-the-throw
  (testing "project-page-src guards EVERY record in the mapv before the door call"
    (doseq [incl? [false true]]
      (let [src (egress/project-page-src "page" incl?)]
        (is (str/includes? src "(when-not (= :rf/epoch-record (:kind r#))")
            "the `:kind` stamp is checked before anything else happens to the record")
        (is (str/includes? src "(throw (ex-info")
            "an unstamped record THROWS — it is never handed to the door")
        (is (str/includes? src ":rf.error/pair-mcp-unstamped-epoch-record")
            "the machine-readable discriminator rides in ex-data")
        (is (str/includes? src "(re-frame.core/project-egress r# {:rf.egress/profile")
            "a STAMPED record still reaches the door with the egress opts threaded in")
        (is (not (str/includes? src "projected-record"))
            "the retired door is gone (rf2-bv1p)")
        ;; The throw must sit BEFORE the door call, not after it — a guard
        ;; that fires only once the record has already been projected has
        ;; already leaked.
        (is (< (.indexOf src "(throw (ex-info")
               (.indexOf src "(re-frame.core/project-egress"))
            "the guard is sequenced BEFORE the door call")))))

(deftest dispatch-result-src-renders-the-kind-check-and-the-throw
  (testing "project-dispatch-result-src guards the :epoch slot before the door call"
    (doseq [incl? [false true]]
      (let [src (egress/project-dispatch-result-src "(rf/dispatch-and-collect)" incl?)]
        (is (str/includes? src "(when-not (= :rf/epoch-record (:kind e#))")
            "the `:kind` stamp is checked on the bound :epoch slot")
        (is (str/includes? src ":rf.error/pair-mcp-unstamped-epoch-record")
            "the machine-readable discriminator rides in ex-data")
        (is (str/includes? src "(re-frame.core/project-egress e# {:rf.egress/profile")
            "a STAMPED epoch still reaches the door with the egress opts threaded in")
        (is (not (str/includes? src "projected-record"))
            "the retired door is gone (rf2-bv1p)")
        (is (< (.indexOf src "(throw (ex-info")
               (.indexOf src "(re-frame.core/project-egress"))
            "the guard is sequenced BEFORE the door call")))))

(deftest an-absent-epoch-slot-stays-legitimate
  ;; The guard fires on a PRESENT epoch that is not stamped. ABSENT is a
  ;; normal, supported shape — a degraded runtime and the `:ok? false`
  ;; frame-untargetable envelope both carry no `:epoch` — so the
  ;; presence check must still govern BOTH the projection and the guard.
  ;; Without it, every degraded dispatch would start throwing.
  (testing "the (contains? r# :epoch) presence check still gates the guarded projection"
    (let [src  (egress/project-dispatch-result-src "(rf/dispatch-and-collect)" false)
          form (reader/read-string src)
          when-form (find-form 'when form)]
      (is (str/includes? src "(when (contains? r# :epoch)")
          "an absent :epoch short-circuits before the guard is reached")
      (is (some? when-form) "the presence check survives as a `when` in the read form")
      (is (= '(contains? r# :epoch) (second when-form))
          "and it is the epoch-presence test, not some other condition")
      (is (some? (find-form 'when-not when-form))
          "the guard lives INSIDE the presence check, so absent never throws"))))

;; ---------------------------------------------------------------------------
;; Layer 2 — the rendered source is well-formed CLJS
;; ---------------------------------------------------------------------------

(deftest rendered-sources-are-well-formed
  ;; The guard is assembled by string concatenation and embeds a nested
  ;; string literal (the error message). A mis-escaped quote or a
  ;; dropped paren survives every substring assertion above and then
  ;; fails to compile app-side, where the failure reads as a runtime
  ;; problem rather than a rendering bug. Reading the source is the
  ;; cheapest way to refuse that class outright.
  (testing "both render sites emit readable CLJS on both incl? postures"
    (doseq [incl? [false true]]
      (is (some? (reader/read-string (egress/project-page-src "page" incl?)))
          "project-page-src emits one well-formed form")
      (is (some? (reader/read-string
                  (egress/project-dispatch-result-src "(rf/dispatch-and-collect)" incl?)))
          "project-dispatch-result-src emits one well-formed form"))))

(deftest the-thrown-ex-info-carries-the-discriminator
  (testing "ex-data names the skew in BOTH slots the error envelope reads"
    (doseq [src [(egress/project-page-src "page" false)
                 (egress/project-dispatch-result-src "(rf/dispatch-and-collect)" false)]]
      (let [guard  (guard-of src)
            body   (nth guard 2)
            ex     (second body)
            ex-msg (second ex)
            data   (nth ex 2)]
        (is (= 'throw (first body))   "the guard's body is a throw")
        (is (= 'ex-info (first ex))   "and what it throws is an ex-info")
        (is (= 'str (first ex-msg))
            "the message is built with `str` so it can name the OBSERVED :kind")
        (is (some #(and (string? %) (str/includes? % ":rf/epoch-record")) ex-msg)
            "the message names the stamp that was expected")
        (is (some #(and (string? %) (str/includes? % "rf2-kuky.92")) ex-msg)
            "and names the cause — an app too old to stamp it")
        (is (map? data) "ex-data is a map literal")
        (is (= egress/unstamped-epoch-record-error-id (:rf.error/id data))
            ":rf.error/id carries the discriminator")
        (is (= egress/unstamped-epoch-record-error-id (:reason data))
            ":reason carries it too — server.cljs reads that slot into the envelope")))))

;; ---------------------------------------------------------------------------
;; Layer 3 — the rendered DECISION, executed, both ways
;; ---------------------------------------------------------------------------

(deftest the-rendered-guard-passes-a-stamped-record-and-throws-on-an-unstamped-one
  ;; `when-not` fires its body when the predicate is FALSE. So:
  ;;   stamped   ⇒ predicate TRUE  ⇒ no throw ⇒ the door is reached;
  ;;   unstamped ⇒ predicate FALSE ⇒ throw    ⇒ the door is NEVER reached.
  ;; Both directions are run against the predicate as RENDERED, not a
  ;; hand-copy of it, so a typo in the emitted spelling fails here.
  (testing "both render sites, both postures, both directions"
    (doseq [src [(egress/project-page-src "page" false)
                 (egress/project-page-src "page" true)
                 (egress/project-dispatch-result-src "(rf/dispatch-and-collect)" false)
                 (egress/project-dispatch-result-src "(rf/dispatch-and-collect)" true)]]
      (let [pred (second (guard-of src))]
        (is (true? (eval-kind-pred pred stamped))
            "a STAMPED record satisfies the guard — it proceeds to the door")
        (is (false? (eval-kind-pred pred unstamped))
            "an UNSTAMPED record does NOT — the guard throws instead of bare-walking it")
        (is (false? (eval-kind-pred pred {}))
            "and so does an empty map (no :kind at all)")
        (is (false? (eval-kind-pred pred {:kind :rf.observe/handled-event}))
            "a record of ANOTHER recognised kind is refused too — this door call is for epochs")))))
