(ns re-frame-pair2-mcp.elision-test
  "Unit tests for the size-elision wire-marker integration (rf2-urjnc).

  Per `tools/pair2-mcp/spec/Principles.md` §\"Size-elision wire markers\",
  the `snapshot` and `get-path` tools call
  `re-frame.core/elide-wire-value` (rf2-v9tw2) server-side inside the
  CLJS eval form before any payload crosses the wire. A declared
  `:large?` slot or an over-threshold leaf is substituted with a
  `{:rf.size/large-elided {:path [...] :handle [:rf.elision/at <path>]
  ...}}` marker; the agent re-fetches via `get-path` with the handle's
  path.

  Tests pin `elision-opts-edn` directly from
  `re-frame-pair2-mcp.tools.elision`. The downstream eval-form
  composers (`build-snapshot-form` / `build-get-path-form`) remain
  local fixtures because their source counterparts live inlined in
  the per-tool namespaces (`tools.snapshot` / `tools.get-path`) and
  aren't surfaced as standalone public fns.

  `:elision` MCP-arg normalisation lives on the shared table-driven
  parser (`re-frame-pair2-mcp.tools.args/parse-bool-arg`, rf2-c4fmh);
  see `re-frame-pair2-mcp.args-test` for the coverage.

  Live end-to-end coverage runs against a real shadow-cljs build via
  the existing `test/stdio-roundtrip.js` harness — that's where the
  walker actually fires and we verify the marker comes back as EDN.
  The CLJS layer here just pins the wiring."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [cljs.reader]
            [re-frame-pair2-mcp.tools.elision :as elision]))

;; ---------------------------------------------------------------------------
;; elision-opts-edn — EDN-render the walker's opts map for inlining.
;; ---------------------------------------------------------------------------

(deftest elision-opts-edn-enabled-has-include-large-false
  ;; When elision is on, we want the walker to ACTUALLY elide — so
  ;; `:rf.size/include-large?` is `false`. The "include-large?" name
  ;; is the walker's own switch (a `true` means pass through, a
  ;; `false` means emit the marker — the keyword surfaces from the
  ;; walker's API, not from our boolean).
  (let [edn (elision/elision-opts-edn true)
        parsed (cljs.reader/read-string edn)]
    (is (false? (:rf.size/include-large? parsed)))))

(deftest elision-opts-edn-disabled-has-include-large-true
  ;; When elision is disabled, `:rf.size/include-large?` is true so
  ;; the walker passes the raw value through. (We also short-circuit
  ;; the walk entirely in the eval form when disabled — see the
  ;; snapshot-eval-form test below.)
  (let [edn (elision/elision-opts-edn false)
        parsed (cljs.reader/read-string edn)]
    (is (true? (:rf.size/include-large? parsed)))))

(deftest elision-opts-edn-round-trips
  ;; The EDN we ship over nREPL must be readable on the other side.
  ;; pr-str + read-string round-trips for the structure we emit.
  (doseq [enabled? [true false]]
    (let [edn (elision/elision-opts-edn enabled?)
          parsed (cljs.reader/read-string edn)]
      (is (map? parsed))
      (is (contains? parsed :rf.size/include-large?)))))

;; ---------------------------------------------------------------------------
;; Eval-form composition for snapshot-tool (rf2-urjnc).
;;
;; The snapshot-tool builds a CLJS eval form sent over nREPL. With
;; elision enabled, the form wraps `(re-frame-pair2.runtime/snapshot-state
;; ...)` with a `reduce-kv` that walks each frame's :app-db slice through
;; `re-frame.core/elide-wire-value`. With elision disabled, the form is
;; the plain snapshot call.
;;
;; We can't `(require '[re-frame-pair2-mcp.tools])` from a node-test
;; build (the namespace `:require`s `re-frame-pair2-mcp.nrepl`, which
;; opens a TCP socket on load via shadow-cljs's preload contract) so we
;; mirror the form construction here. A rename of `snapshot-state` or
;; `elide-wire-value` breaks here as well as in production.
;; ---------------------------------------------------------------------------

(defn- build-snapshot-form
  "Mirror of the snapshot-tool's eval-form composition. Two arms:
  elision on/off. Keep in lockstep with `snapshot-tool` in tools.cljs."
  [opts elision?]
  (let [elision-opts-form (elision/elision-opts-edn elision?)]
    (if elision?
      (str "(let [snap (re-frame-pair2.runtime/snapshot-state "
           (pr-str opts) ")]"
           "  (reduce-kv"
           "    (fn [m fid fmap]"
           "      (assoc m fid"
           "             (if (and (map? fmap) (contains? fmap :app-db))"
           "               (update fmap :app-db"
           "                       (fn [db] (re-frame.core/elide-wire-value db"
           "                                  (merge {:frame fid} "
           elision-opts-form
           "))))"
           "               fmap)))"
           "    {} snap))")
      (str "(re-frame-pair2.runtime/snapshot-state "
           (pr-str opts) ")"))))

(deftest snapshot-form-elision-off-is-plain-call
  ;; Elision off = the original form, no walker wrap. Backwards-
  ;; compatible with the pre-rf2-urjnc shape so a slow runtime that
  ;; doesn't yet ship the walker still answers when the agent opts out.
  (let [form (build-snapshot-form {:frames :all
                                   :include [:app-db]}
                                  false)]
    (is (= "(re-frame-pair2.runtime/snapshot-state {:frames :all, :include [:app-db]})"
           form))))

(deftest snapshot-form-elision-on-wraps-with-walker
  ;; Elision on = the walker wrap. The form should reference both
  ;; `snapshot-state` and `elide-wire-value` so a typo in either name
  ;; breaks here.
  (let [form (build-snapshot-form {:frames :all
                                   :include [:app-db]}
                                  true)]
    (is (re-find #"re-frame-pair2\.runtime/snapshot-state" form))
    (is (re-find #"re-frame\.core/elide-wire-value" form))
    ;; Per-frame walking, not whole-snapshot walking — the walker is
    ;; applied to each :app-db slice with that frame's id, so the
    ;; `[:rf/elision]` registry lookup hits the right frame.
    (is (re-find #":frame fid" form))
    ;; The walker is invoked with `:rf.size/include-large? false` so
    ;; markers actually fire.
    (is (re-find #":rf\.size/include-large\? false" form))))

(deftest snapshot-form-only-walks-app-db-slice
  ;; The other slices (:sub-cache :machines :epochs :traces) have their
  ;; own wire-protocol mechanisms (dedup, diff-encode). Elision is
  ;; scoped to :app-db — the slice whose payload can blow the cap on a
  ;; single large slot.
  (let [form (build-snapshot-form {:frames :all
                                   :include [:app-db :epochs]}
                                  true)]
    ;; The walker is invoked once, with the :app-db slot.
    (is (= 1 (count (re-seq #"elide-wire-value" form))))
    (is (re-find #"contains\? fmap :app-db" form))))

;; ---------------------------------------------------------------------------
;; Eval-form composition for get-path-tool (rf2-urjnc).
;;
;; The get-path-tool eval form does `(get-in db path)` then passes the
;; resolved value through the walker. The walker's `:path` opt is set
;; to the supplied path so the marker's `:handle` carries
;; `[:rf.elision/at <path>]`.
;; ---------------------------------------------------------------------------

(defn- build-get-path-form
  "Mirror of the get-path-tool's eval-form composition. Keep in
  lockstep with `get-path-tool` in tools.cljs."
  [path frame elision?]
  (let [path-edn      (pr-str path)
        snapshot-call (if frame
                        (str "(re-frame-pair2.runtime/snapshot " (pr-str frame) ")")
                        "(re-frame-pair2.runtime/snapshot)")
        frame-edn     (if frame (pr-str frame) "(re-frame-pair2.runtime/current-frame)")
        elision-opts  (elision/elision-opts-edn elision?)
        elide-call    (if elision?
                        (str "(re-frame.core/elide-wire-value v"
                             "  (merge {:path path :frame " frame-edn "}"
                             "         " elision-opts "))")
                        "v")]
    (str "(let [db " snapshot-call
         "      path " path-edn
         "      missing #js {}"
         "      v (get-in db path missing)]"
         "  (if (identical? v missing)"
         "    {:ok? false :reason :path-not-found"
         "     :path path"
         "     :deepest-valid-prefix"
         "     (loop [acc [] cur db rem path]"
         "       (cond"
         "         (empty? rem) acc"
         "         (and (map? cur) (contains? cur (first rem)))"
         "         (recur (conj acc (first rem)) (get cur (first rem)) (rest rem))"
         "         (and (sequential? cur) (integer? (first rem))"
         "              (<= 0 (first rem) (dec (count cur))))"
         "         (recur (conj acc (first rem)) (nth (vec cur) (first rem)) (rest rem))"
         "         :else acc))}"
         "    {:ok? true :exists? true :path path :value " elide-call "}))")))

(deftest get-path-form-elision-off-bypasses-walker
  ;; Elision off = the raw value rides the wire. Backwards-compatible
  ;; with the pre-rf2-urjnc shape.
  (let [form (build-get-path-form [:user :uploaded-pdf] :rf/default false)]
    (is (not (re-find #"elide-wire-value" form)))
    (is (re-find #":value v" form))))

(deftest get-path-form-elision-on-wraps-value
  ;; Elision on = the value is walked. The walker call inherits the
  ;; `:path` so the marker's `:handle` slot is `[:rf.elision/at
  ;; [:user :uploaded-pdf]]`.
  (let [form (build-get-path-form [:user :uploaded-pdf] :rf/default true)]
    (is (re-find #"re-frame\.core/elide-wire-value v" form))
    ;; The walker's `:path` opt is the supplied path so the marker's
    ;; handle carries `[:rf.elision/at <path>]`.
    (is (re-find #":path path" form))
    ;; Frame is explicit when supplied.
    (is (re-find #":frame :rf/default" form))
    ;; Markers actually fire (include-large? is false).
    (is (re-find #":rf\.size/include-large\? false" form))))

(deftest get-path-form-defaults-to-current-frame
  ;; No `:frame` arg = the walker uses
  ;; `(re-frame-pair2.runtime/current-frame)` so the registry lookup
  ;; hits the operating frame.
  (let [form (build-get-path-form [:cart :items] nil true)]
    (is (re-find #":frame \(re-frame-pair2\.runtime/current-frame\)" form))))

(deftest get-path-form-path-edn-quotes-correctly
  ;; The path is pr-str'd into the form. Mixed key types (keywords,
  ;; integers, strings) must round-trip through the EDN reader on the
  ;; runtime side. We just check that the EDN-rendered path appears
  ;; as a substring of the form — the regex-escape song-and-dance for
  ;; literal `[ ] : "` chars isn't worth the cost; substring suffices.
  (let [path  [:cart "items" 3 :sku]
        form  (build-get-path-form path nil true)
        edn   (pr-str path)]
    (is (not= -1 (.indexOf form edn)))))

;; ---------------------------------------------------------------------------
;; Composition × wire-cap (rf2-rvyzy fallback).
;;
;; Elision runs FIRST (server-side, inside the eval form). When elision
;; is on and a `:large?` path matches, the response shrinks to the
;; marker and the wire-cap stays a backstop. When elision is OFF, the
;; raw payload rides and the wire-cap may still trip — that fallback
;; is the existing rf2-rvyzy mechanism.
;;
;; The cap check itself is a pure function over the assembled MCP
;; result envelope; we test that elision-off still produces a payload
;; the cap can measure (no shape weirdness from a missing wrap).
;; ---------------------------------------------------------------------------

(deftest wire-cap-composes-with-elision-off
  ;; Sanity: the snapshot form with elision off is plain EDN — it has
  ;; no marker shape on the way out, so when the runtime returns a
  ;; raw value the cap measures the raw bytes (the rf2-rvyzy fallback).
  (let [form (build-snapshot-form {:frames :all :include [:app-db]} false)]
    ;; No `:rf.size/*` shapes in the form when elision is off; the
    ;; marker emission is entirely the walker's job.
    (is (not (re-find #":rf\.size/large-elided" form)))
    (is (not (re-find #"elide-wire-value" form)))))

(deftest wire-cap-composes-with-elision-on
  ;; With elision on, the walker substitutes the large slot BEFORE the
  ;; payload crosses the wire — the cap then measures the
  ;; already-shrunk payload. The form references the walker; the
  ;; marker emission happens runtime-side.
  (let [form (build-snapshot-form {:frames :all :include [:app-db]} true)]
    (is (re-find #"elide-wire-value" form))))

;; ---------------------------------------------------------------------------
;; Cross-MCP vocabulary pin.
;;
;; The cross-MCP vocabulary for the wire marker (`:rf.size/large-elided`
;; / `[:rf.elision/at <path>]`) is reserved per Conventions §Reserved
;; namespaces / app-db keys / fx-ids and Spec 009 §Size elision in
;; traces. Pin the literals so a vocabulary drift surfaces here.
;; ---------------------------------------------------------------------------

(deftest cross-mcp-vocabulary-rf-size-include-large
  ;; The walker's switch keyword is `:rf.size/include-large?`. We
  ;; render this verbatim into the EDN sent over nREPL. The kw shape
  ;; is normative per the walker's docstring; a rename in the framework
  ;; surface needs a co-ordinated update here.
  (is (= "{:rf.size/include-large? false}"
         (elision/elision-opts-edn true)))
  (is (= "{:rf.size/include-large? true}"
         (elision/elision-opts-edn false))))
