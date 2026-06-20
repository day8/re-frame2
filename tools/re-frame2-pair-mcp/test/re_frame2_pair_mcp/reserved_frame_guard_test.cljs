(ns re-frame2-pair-mcp.reserved-frame-guard-test
  "Tests for the wholesale-read backstop.

  A wholesale read of the `:rf/xray` frame can blow a single read past
  126K tokens. The SKILL steers the agent to orient first + never
  wholesale-read a tool frame; this guard adds the SERVER backstop so a
  stray full read can't blow the context window regardless of agent
  judgment. Skill steers, MCP guards.

  Three surfaces are pinned:

    1. The reserved-frame predicate + refusal-builder (pure, no socket).
    2. End-to-end refusal: a `path: []` / `mode :full` (snapshot) or
       `path: []` (get-path) read of a reserved `:rf/*` frame is REFUSED
       with a redirect-to-orient error BEFORE the eval round-trip — the
       runtime is never contacted, so a huge payload never crosses.
    3. The negative guards: a SLICED read of `:rf/xray`, and a ROOT read
       of a NON-reserved user frame, both reach the eval and SUCCEED."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.reserved-frame-guard :as guard]
            [re-frame2-pair-mcp.tools.snapshot :as snapshot]
            [re-frame2-pair-mcp.tools.get-path :as get-path]
            [re-frame2-pair-mcp.tools.operating-frame :as op-frame]))

;; ---------------------------------------------------------------------------
;; Pure: the reserved-frame predicate (server-side mirror of the runtime's).
;; ---------------------------------------------------------------------------

(deftest reserved-tool-frame?-keys-off-the-reserved-namespace-rule
  (testing ":rf/* tool frames are reserved"
    (is (true? (guard/reserved-tool-frame? :rf/xray)))
    (is (true? (guard/reserved-tool-frame? :rf/some-ssr-slot)))
    (is (true? (guard/reserved-tool-frame? :rf/story))))
  (testing ":rf/default is the carve-out — the canonical APP frame"
    (is (false? (guard/reserved-tool-frame? :rf/default))))
  (testing "user frames (un-namespaced / other ns) are not reserved"
    (is (false? (guard/reserved-tool-frame? :stories)))
    (is (false? (guard/reserved-tool-frame? :sandbox)))
    (is (false? (guard/reserved-tool-frame? :my-app/main))))
  (testing "non-keywords are not reserved"
    (is (false? (guard/reserved-tool-frame? "rf/xray")))
    (is (false? (guard/reserved-tool-frame? nil)))))

;; ---------------------------------------------------------------------------
;; Pure: the refusal-builder shape — names the frame, redirects to orient
;; + a targeted slice (echoes the skill's ops.md wording).
;; ---------------------------------------------------------------------------

(defn- refusal-edn [^js result] (tu/extract-edn result))

(deftest snapshot-refusal-shape-redirects-to-orient-and-a-slice
  (testing "frames \"all\" + path [] is refused"
    (let [r (guard/snapshot-refusal :all [] :summary)]
      (is (some? r))
      (is (tu/error? r))
      (let [edn (refusal-edn r)]
        (is (false? (:ok? edn)))
        (is (= :wholesale-read-of-reserved-frame (:reason edn)))
        (is (re-find #"orient" (:hint edn)) "redirects to orient")
        (is (or (re-find #"get-path" (:hint edn))
                (re-find #"snapshot \{path" (:hint edn))
                (re-find #"read-sub" (:hint edn)))
            "redirects to a targeted slice"))))
  (testing "frames [:rf/xray] + path [] is refused and names the frame"
    (let [r   (guard/snapshot-refusal [:rf/xray] [] :summary)
          edn (refusal-edn r)]
      (is (some? r))
      (is (re-find #":rf/xray" (:frame edn)) "names the refused frame")
      (is (re-find #"orient" (:hint edn)))))
  (testing "frames [:rf/xray] + mode :full (no path) is refused"
    (is (some? (guard/snapshot-refusal [:rf/xray] nil :full)))))

(deftest snapshot-refusal-allows-sliced-and-app-scope
  (testing "a SLICED read of :rf/xray is allowed (negative guard)"
    (is (nil? (guard/snapshot-refusal [:rf/xray] [:rf.xray/epochs 0] :summary))))
  (testing "the default :app scope is allowed even at path [] — reserved frames already excluded"
    (is (nil? (guard/snapshot-refusal :app [] :summary)))
    (is (nil? (guard/snapshot-refusal :app nil :full))))
  (testing "an explicit user frame at path [] is allowed"
    (is (nil? (guard/snapshot-refusal [:stories] [] :summary)))
    (is (nil? (guard/snapshot-refusal [:rf/default] [] :summary))
        ":rf/default is an app frame — not refused"))
  (testing "summary-mode + nil path (the default) is never wholesale"
    (is (nil? (guard/snapshot-refusal :all nil :summary)))
    (is (nil? (guard/snapshot-refusal [:rf/xray] nil :summary)))))

(deftest get-path-refusal-shape-and-negative-guards
  (testing ":rf/xray + path [] is refused"
    (let [r   (guard/get-path-refusal :rf/xray [] nil)
          edn (refusal-edn r)]
      (is (some? r))
      (is (tu/error? r))
      (is (= :wholesale-read-of-reserved-frame (:reason edn)))
      (is (re-find #":rf/xray" (:frame edn)))
      (is (re-find #"orient" (:hint edn)))))
  (testing ":rf/xray + a batch paths containing [] is refused"
    (is (some? (guard/get-path-refusal :rf/xray nil [[:rf.xray/state] []]))))
  (testing "a SLICED read of :rf/xray is allowed (negative guard)"
    (is (nil? (guard/get-path-refusal :rf/xray [:rf.xray/state] nil)))
    (is (nil? (guard/get-path-refusal :rf/xray nil [[:rf.xray/state]]))))
  (testing "a user frame / :rf/default at path [] is allowed"
    (is (nil? (guard/get-path-refusal :stories [] nil)))
    (is (nil? (guard/get-path-refusal :rf/default [] nil))))
  (testing "a nil frame (defaulted operating frame) does not fire this client-side guard"
    ;; This is NOT an escape hatch. The client guard can't see the
    ;; runtime-resolved frame for an omitted-:frame call, but
    ;; set-operating-frame refuses to PIN a reserved :rf/* frame, so the
    ;; runtime's current-frame resolver can never return one at tier 2 — an
    ;; omitted-:frame read resolves to an APP frame (or refuses
    ;; :ambiguous-frame), never a tool frame. See
    ;; set-operating-frame-refuses-reserved-tool-frame below.
    (is (nil? (guard/get-path-refusal nil [] nil)))))

;; ---------------------------------------------------------------------------
;; End-to-end: invoke the real tool bodies with a stubbed nREPL conn.
;;
;; Stub lifetime is fixture-scoped — bare `set!` per test, `:after`
;; restores the pristine original captured at ns-load.
;; ---------------------------------------------------------------------------

(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:after (fn [] (set! nrepl/cljs-eval-value pristine-eval))})

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    ;; Pretend the preload is confirmed so probe resolves synchronously
    ;; and we exercise the tool body without a socket.
    (swap! conn assoc :probed-builds #{:app})
    conn))

(defn- stub-eval!
  "Install a `cljs-eval-value` stub. Answers the preload probe with `true`
  and the raw-state signal with `nil` (prelude evals), records into
  `seen*` every NON-prelude form it is handed (i.e. the actual snapshot /
  get-path eval), and resolves those with `canned`. `seen*` staying empty
  proves the guard short-circuited BEFORE the read round-trip."
  [seen* canned]
  (let [respond (fn [form]
                  (cond
                    (and (string? form) (re-find #"__re_frame2_pair_runtime" form))
                    (js/Promise.resolve true)

                    (and (string? form) (re-find #"configure-raw-state!" form))
                    (js/Promise.resolve nil)

                    :else
                    (do (when seen* (swap! seen* conj form))
                        (js/Promise.resolve canned))))]
    (set! nrepl/cljs-eval-value
          (fn
            ([_c _b form] (respond form))
            ([_c _b form _o] (respond form))))))

;; A deliberately HUGE canned payload — the shape the runtime would
;; return for a wholesale :rf/xray read (the 126K-token overflow path).
;; If the guard fails to short-circuit, this is what would cross the wire.
(def ^:private huge-xray-snapshot
  {:rf/xray {:app-db (zipmap (map #(keyword "rf.xray" (str "k" %)) (range 20000))
                             (repeat (apply str (repeat 64 "x"))))}})

(deftest snapshot-wholesale-rf-xray-is-refused-before-the-eval
  ;; Reproduce the overflow path: a `path: []` read of :rf/xray would
  ;; return huge-xray-snapshot. Assert it is now REFUSED and the runtime
  ;; eval was never reached (so the huge payload never crosses).
  (async done
    (let [seen (atom [])]
      (stub-eval! seen {:value huge-xray-snapshot :elided-count 0 :tool-frames-excluded []})
      (-> (snapshot/snapshot-tool (fresh-conn) (tu/args->js {:frames #js [":rf/xray"] :path "[]"}))
          (.then (fn [r]
                   (is (tu/error? r) "wholesale :rf/xray read is refused")
                   (let [edn (tu/extract-edn r)]
                     (is (= :wholesale-read-of-reserved-frame (:reason edn)))
                     (is (re-find #":rf/xray" (:frame edn)))
                     (is (re-find #"orient" (:hint edn)) "redirects to orient"))
                   (is (empty? @seen)
                       "the snapshot read eval was NEVER reached — refused before the round-trip")
                   (done)))))))

(deftest snapshot-wholesale-frames-all-is-refused
  ;; `frames "all"` sweeps in reserved tool frames; a wholesale read of
  ;; that scope is refused.
  (async done
    (let [seen (atom [])]
      (stub-eval! seen {:value huge-xray-snapshot :elided-count 0 :tool-frames-excluded []})
      (-> (snapshot/snapshot-tool (fresh-conn) (tu/args->js {:frames "all" :path "[]"}))
          (.then (fn [r]
                   (is (tu/error? r))
                   (is (= :wholesale-read-of-reserved-frame (:reason (tu/extract-edn r))))
                   (is (empty? @seen))
                   (done)))))))

(deftest snapshot-sliced-rf-xray-succeeds
  ;; Negative guard: a SLICED read of :rf/xray reaches the eval and
  ;; returns ok — don't over-block.
  (async done
    (let [seen (atom [])]
      (stub-eval! seen {:value {:rf/xray {:app-db {:rf.xray/epochs [1 2 3]}}}
                        :elided-count 0 :tool-frames-excluded []})
      (-> (snapshot/snapshot-tool (fresh-conn)
                                  (tu/args->js {:frames #js [":rf/xray"] :path "[:rf.xray/epochs]"}))
          (.then (fn [r]
                   (is (not (tu/error? r)) "sliced :rf/xray read is allowed")
                   (is (true? (:ok? (tu/extract-edn r))))
                   (is (seq @seen) "the read eval WAS reached for a sliced read")
                   (done)))))))

(deftest snapshot-root-read-of-user-frame-succeeds
  ;; Negative guard: a root read of a NON-reserved user frame is
  ;; unaffected.
  (async done
    (let [seen (atom [])]
      (stub-eval! seen {:value {:stories {:app-db {:story-id 7}}}
                        :elided-count 0 :tool-frames-excluded []})
      (-> (snapshot/snapshot-tool (fresh-conn)
                                  (tu/args->js {:frames #js [":stories"] :path "[]"}))
          (.then (fn [r]
                   (is (not (tu/error? r)) "root read of a user frame is allowed")
                   (is (true? (:ok? (tu/extract-edn r))))
                   (is (seq @seen) "the read eval WAS reached")
                   (done)))))))

(deftest get-path-wholesale-rf-xray-is-refused-before-the-eval
  (async done
    (let [seen (atom [])]
      (stub-eval! seen {:ok? true :exists? true :value huge-xray-snapshot :elided-count 0})
      (-> (get-path/get-path-tool (fresh-conn) (tu/args->js {:frame ":rf/xray" :path "[]"}))
          (.then (fn [r]
                   (is (tu/error? r) "wholesale :rf/xray get-path is refused")
                   (let [edn (tu/extract-edn r)]
                     (is (= :wholesale-read-of-reserved-frame (:reason edn)))
                     (is (re-find #"orient" (:hint edn))))
                   (is (empty? @seen)
                       "the get-path read eval was NEVER reached")
                   (done)))))))

(deftest get-path-sliced-rf-xray-succeeds
  ;; Negative guard: a sliced get-path against :rf/xray is allowed.
  (async done
    (let [seen (atom [])]
      (stub-eval! seen {:ok? true :exists? true :value [1 2 3] :elided-count 0})
      (-> (get-path/get-path-tool (fresh-conn)
                                  (tu/args->js {:frame ":rf/xray" :path "[:rf.xray/epochs]"}))
          (.then (fn [r]
                   (is (not (tu/error? r)) "sliced :rf/xray get-path is allowed")
                   (is (true? (:ok? (tu/extract-edn r))))
                   (is (seq @seen) "the read eval WAS reached")
                   (done)))))))

(deftest get-path-root-read-of-user-frame-succeeds
  (async done
    (let [seen (atom [])]
      (stub-eval! seen {:ok? true :exists? true :value {:story-id 7} :elided-count 0})
      (-> (get-path/get-path-tool (fresh-conn)
                                  (tu/args->js {:frame ":stories" :path "[]"}))
          (.then (fn [r]
                   (is (not (tu/error? r)) "root get-path of a user frame is allowed")
                   (is (true? (:ok? (tu/extract-edn r))))
                   (is (seq @seen) "the read eval WAS reached")
                   (done)))))))

;; ---------------------------------------------------------------------------
;; The operating-frame bypass. The client-side get-path guard fires on
;; the explicit `:frame` arg; an omitted `:frame` resolves runtime-side
;; via `current-frame`, which returns the session pin at tier 2. If
;; `set-operating-frame` let a reserved `:rf/*` TOOL frame be pinned, a
;; later `get-path {path "[]"}` with no `:frame` would resolve the
;; wholesale read through it — re-opening the overflow the guard closes.
;; The reserved-frame PIN is refused at its source, so the resolver can
;; never return a tool frame for an omitted-:frame call.
;; ---------------------------------------------------------------------------

(deftest set-operating-frame-refuses-reserved-tool-frame
  ;; The bypass entry point: pinning :rf/xray as the operating frame is now
  ;; refused BEFORE any nREPL round-trip (a reserved :rf/* frame is a
  ;; devtool surface, never the app the operator pairs against). With the
  ;; pin closed, an omitted-:frame read can never resolve to :rf/xray.
  (async done
    (let [seen (atom [])]
      ;; `select-frame!`'s validate-then-pin form is the only non-prelude
      ;; eval set-operating-frame would issue; `seen` staying empty proves
      ;; the refusal short-circuited before the round-trip (no pin written).
      (stub-eval! seen {:ok? true :frames [:rf/default :rf/xray]
                        :selected :rf/xray :operating :rf/xray})
      (-> (op-frame/set-operating-frame-tool (fresh-conn)
                                             (tu/args->js {:frame ":rf/xray"}))
          (.then (fn [r]
                   (is (tu/error? r) "pinning a reserved :rf/* tool frame is refused")
                   (let [edn (tu/extract-edn r)]
                     (is (false? (:ok? edn)))
                     (is (= :reserved-tool-frame (:reason edn)))
                     (is (= :rf/xray (:frame edn)))
                     (is (re-find #"get-operating-frame|app" (:hint edn))
                         "redirects to an app frame / sliced :frame read"))
                   (is (empty? @seen)
                       "no select-frame! eval — the reserved pin was never written")
                   (done)))))))

(deftest set-operating-frame-allows-rf-default-and-app-frames
  ;; Negative guard: :rf/default is an APP frame (the predicate's carve-out)
  ;; and an ordinary user frame both pin normally — the refusal is scoped to
  ;; reserved :rf/* TOOL frames only.
  (async done
    (let [seen (atom [])]
      (stub-eval! seen {:ok? true :frames [:rf/default :stories]
                        :selected :stories :operating :stories})
      (-> (op-frame/set-operating-frame-tool (fresh-conn)
                                             (tu/args->js {:frame ":stories"}))
          (.then (fn [r]
                   (is (not (tu/error? r)) "pinning an app frame succeeds")
                   (is (true? (:ok? (tu/extract-edn r))))
                   (is (seq @seen) "the validate-then-pin eval WAS reached")
                   (done)))))))

(deftest sliced-reserved-frame-read-still-succeeds-via-explicit-frame
  ;; With the reserved-frame PIN closed, a TARGETED (sliced) read of a tool
  ;; frame stays available through the explicit per-call :frame arg — the
  ;; "preserve allowed targeted reads of reserved frames" acceptance
  ;; criterion. (The "pinned :rf/xray + sliced read" example is moot: a
  ;; reserved frame is never the operating frame.)
  (async done
    (let [seen (atom [])]
      (stub-eval! seen {:ok? true :exists? true :value [1 2 3] :elided-count 0})
      (-> (get-path/get-path-tool (fresh-conn)
                                  (tu/args->js {:frame ":rf/xray" :path "[:rf.xray/epochs]"}))
          (.then (fn [r]
                   (is (not (tu/error? r)) "explicit :frame :rf/xray + non-root path is allowed")
                   (is (true? (:ok? (tu/extract-edn r))))
                   (is (seq @seen) "the sliced read eval WAS reached")
                   (done)))))))
