(ns re-frame2-pair-mcp.reserved-frame-guard-test
  "The wholesale-read backstop. A root `path []` (or `mode :full` with no
  path) read of a reserved `:rf/*` tool frame can blow past 100K tokens,
  so `snapshot` and `get-path` refuse it before the eval round-trip, and
  `set-operating-frame` refuses to pin such a frame, which would otherwise
  route every omitted-`:frame` read through it. `:rf/default` is an app
  frame, and a sliced read of a tool frame is allowed."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.reserved-frame-guard :as guard]
            [re-frame2-pair-mcp.tools.snapshot :as snapshot]
            [re-frame2-pair-mcp.tools.get-path :as get-path]
            [re-frame2-pair-mcp.tools.operating-frame :as op-frame]))

;; ---------------------------------------------------------------------------
;; The refusal predicates.
;; ---------------------------------------------------------------------------

(deftest snapshot-refusal-shape-redirects-to-orient-and-a-slice
  (doseq [[frames path mode] [[:all [] :summary]
                              [[:rf/xray] [] :summary]
                              [[:rf/xray] nil :full]]]
    (is (tu/error? (guard/snapshot-refusal frames path mode)) (pr-str [frames path mode])))
  (is (= {:ok? false :reason :wholesale-read-of-reserved-frame :frame ":rf/xray"}
         (dissoc (tu/extract-edn (guard/snapshot-refusal [:rf/xray] [] :summary)) :hint))
      "names the refused frame"))

(deftest snapshot-refusal-allows-sliced-and-app-scope
  (doseq [[frames path mode] [[[:rf/xray] [:rf.xray/epochs 0] :summary]
                              [:app [] :summary]
                              [[:stories] [] :summary]
                              [[:rf/default] [] :summary]
                              [[:rf/xray] nil :summary]]]
    (is (nil? (guard/snapshot-refusal frames path mode)) (pr-str [frames path mode]))))

(deftest get-path-refusal-shape-and-negative-guards
  (is (= {:ok? false :reason :wholesale-read-of-reserved-frame :frame ":rf/xray"}
         (dissoc (tu/extract-edn (guard/get-path-refusal :rf/xray [] nil)) :hint)))
  (is (tu/error? (guard/get-path-refusal :rf/xray nil [[:rf.xray/state] []]))
      "a batch containing the root path is refused")
  ;; A nil frame is not an escape hatch: the operating frame it resolves
  ;; to can never be a reserved one (set-operating-frame refuses the pin).
  (doseq [[frame path paths] [[:rf/xray [:rf.xray/state] nil]
                              [:rf/xray nil [[:rf.xray/state]]]
                              [:stories [] nil]
                              [:rf/default [] nil]
                              [nil [] nil]]]
    (is (nil? (guard/get-path-refusal frame path paths)) (pr-str [frame path paths]))))

;; ---------------------------------------------------------------------------
;; The real tool bodies, refusing before the eval round-trip.
;; ---------------------------------------------------------------------------

(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:after (fn [] (set! nrepl/cljs-eval-value pristine-eval))})

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(defn- stub-eval!
  "Answer the preload probe true and the raw-state signal nil; record every
  other form into `seen*` and answer it with `canned`. `seen*` staying
  empty proves the tool refused before its read."
  [seen* canned]
  (let [respond (fn [form]
                  (cond
                    (re-find #"__re_frame2_pair_runtime" form) (js/Promise.resolve true)
                    (re-find #"configure-raw-state!" form)     (js/Promise.resolve nil)
                    :else (do (swap! seen* conj form)
                              (js/Promise.resolve canned))))]
    (set! nrepl/cljs-eval-value
          (fn
            ([_c _b form] (respond form))
            ([_c _b form _o] (respond form))))))

(defn- refused-before-eval
  "Run `call`; assert the wholesale refusal and that no read form was sent."
  [call done]
  (let [seen (atom [])]
    (stub-eval! seen {:ok? true :value {} :elided-count 0 :tool-frames-excluded []})
    (-> (call)
        (.then (fn [r]
                 (is (tu/error? r))
                 (is (= :wholesale-read-of-reserved-frame (:reason (tu/extract-edn r))))
                 (is (empty? @seen) "the read eval was never reached")
                 (done))))))

(deftest snapshot-wholesale-rf-xray-is-refused-before-the-eval
  (async done
    (refused-before-eval
      #(snapshot/snapshot-tool (fresh-conn) (tu/args->js {:frames #js [":rf/xray"] :path "[]"}))
      done)))

(deftest snapshot-wholesale-mode-full-no-path-is-refused
  ;; The guard must be fed the slice `:mode`, not `:epochs-mode` (default
  ;; `:diff`), or this shape reaches the eval and ships the unelided frame.
  (async done
    (refused-before-eval
      #(snapshot/snapshot-tool (fresh-conn) (tu/args->js {:frames #js [":rf/xray"] :mode "full"}))
      done)))

(deftest get-path-wholesale-rf-xray-is-refused-before-the-eval
  (async done
    (refused-before-eval
      #(get-path/get-path-tool (fresh-conn) (tu/args->js {:frame ":rf/xray" :path "[]"}))
      done)))

(deftest set-operating-frame-refuses-reserved-tool-frame
  ;; The corpus fixture answers every eval with nil, so only this test can
  ;; see a pin written before the refusal.
  (async done
    (let [seen (atom [])]
      (stub-eval! seen {:ok? true :frames [:rf/default :rf/xray]
                        :selected :rf/xray :operating :rf/xray})
      (-> (op-frame/set-operating-frame-tool (fresh-conn) (tu/args->js {:frame ":rf/xray"}))
          (.then (fn [r]
                   (is (tu/error? r))
                   (is (= {:ok? false :reason :reserved-tool-frame :frame :rf/xray}
                          (dissoc (tu/extract-edn r) :hint)))
                   (is (empty? @seen) "no select-frame! eval: the pin was never written")
                   (done)))))))
