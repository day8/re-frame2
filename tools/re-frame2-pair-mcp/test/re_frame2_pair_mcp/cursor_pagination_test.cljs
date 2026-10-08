(ns re-frame2-pair-mcp.cursor-pagination-test
  "The pair's cursor adapter (`tools/cursor.cljs`) over the shared
  `re-frame.mcp-base.cursor` codec: the `:limit` default and clamp, the
  cursor payload shape, the sticky `:pred` a watch-epochs cursor carries,
  and the handler's rejection of a noncanonical cursor."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.cursor :as cursor]
            [re-frame2-pair-mcp.tools.watch-epochs :as we]
            [re-frame.mcp-base.cursor :as rf.mcp-base.cursor]
            [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]))

(deftest parse-limit-arg-resolution
  (doseq [[input expected note]
          [[nil cursor/default-limit "absent ⇒ the default"]
           [1 1 "the smallest positive integer passes through"]
           [0 1 "zero clamps to one"]
           [1000 1000 "the ceiling passes through"]
           ["25" 25 "a numeric string parses"]
           ["bogus" cursor/default-limit "a non-numeric string falls back to the default"]]]
    (is (= expected (cursor/parse-limit-arg input)) note)))

(deftest encode-cursor-nil-when-no-after-id
  (is (nil? (cursor/encode-cursor {:v 1 :after-id nil}))))

(deftest cursor-round-trips-every-after-id-shape
  ;; The reference epoch runtime emits INTEGER epoch-ids, and the
  ;; payload check is `some?`, not `string?` or truthiness — integer 0
  ;; included. Equality also proves an integer id is not coerced.
  (doseq [payload [{:v 1 :after-id 0}
                   {:v 1 :after-id :ev/login :ms nil :until-ms nil :frame nil}]]
    (is (= payload (cursor/decode-cursor (cursor/encode-cursor payload)))
        (str "after-id " (pr-str (:after-id payload)) " round-trips losslessly"))))

(deftest decode-cursor-blank-is-absent
  (is (nil? (cursor/decode-cursor ""))))

(deftest decode-cursor-malformed-on-missing-after-id
  (let [bogus (.toString (js/Buffer.from "{:v 1}" "utf8") "base64")]
    (is (= ::cursor/malformed (cursor/decode-cursor bogus)))))

;; ---------------------------------------------------------------------------
;; watch-epochs carries the sticky :pred in the continuation cursor, so an
;; agent paging with JUST `:cursor` keeps the filter. Losing it degrades
;; page 2+ to match-all `{}`, with an envelope the agent cannot tell from
;; a correctly-filtered page.
;; ---------------------------------------------------------------------------

(defn- with-form-capture!
  "Stub `nrepl/cljs-eval-value`: the runtime preload probe answers `true`;
  every other form is captured into `forms-atom` and answered `canned`."
  [forms-atom canned body-fn]
  (let [orig  nrepl/cljs-eval-value
        answer (fn [form-str]
                 (if (and (string? form-str) (re-find #"__re_frame2_pair_runtime" form-str))
                   true
                   (do (swap! forms-atom conj form-str)
                       canned)))
        stub  (fn
                ([_conn _build-id form-str]
                 (js/Promise.resolve (answer form-str)))
                ([_conn _build-id form-str _opts]
                 (js/Promise.resolve (answer form-str))))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

(deftest watch-epochs-next-cursor-carries-pred
  (async done
    (let [canned {:matches       [{:epoch-id :e1}]
                  :id-aged-out?  false
                  :requested-id  nil
                  :head-id       :e2
                  :next-id       :e1
                  :history-count 5
                  :since-count   5
                  :remaining     4}]
      (-> (with-form-capture! (atom []) canned
            (fn []
              (-> (we/watch-epochs-tool
                    nil
                    (tu/args->js {:pred #js {:event-id ":ev/login"} :limit 1}))
                  (.then (fn [result]
                           ;; The pred VALUE rides as the JSON string it
                           ;; arrived as; the runtime side coerces it.
                           (is (= {:after-id :e1 :pred {:event-id ":ev/login"}}
                                  (-> (tu/extract-edn result) :next-cursor cursor/decode-cursor
                                      (select-keys [:after-id :pred]))))
                           (done))))))))))

(deftest watch-epochs-page-2-with-only-cursor-still-filters
  (async done
    (let [forms (atom [])
          page-1-cursor (cursor/encode-cursor
                          {:v 1 :after-id :e1 :ms nil :until-ms nil
                           :frame :rf/default
                           :pred {:event-id :ev/login}})
          canned {:matches       []
                  :id-aged-out?  false
                  :requested-id  :e1
                  :head-id       :e9
                  :next-id       nil
                  :history-count 9
                  :since-count   4
                  :remaining     0}]
      (-> (with-form-capture! forms canned
            (fn []
              (-> (we/watch-epochs-tool nil (tu/args->js {:cursor page-1-cursor}))
                  (.then (fn [_result]
                           ;; QUOTED, since a cursor is caller data.
                           (is (re-find #"epoch-matches\? \(quote \{:event-id :ev/login\}\)"
                                        (str (first @forms)))
                               "page-2 form filters by the sticky pred from the cursor")
                           (done))))))))))

;; A noncanonical Base64 alias decodes, on the host-lenient decoder, to
;; the SAME logical cursor under a different wire string. mcp-base's codec
;; rejects it; the handler must surface that as `:rf.mcp/cursor-stale`
;; rather than resume pagination from the alias's decoded position.
(deftest handler-rejects-inserted-char-cursor-alias-watch-epochs
  (async done
    (let [canonical (cursor/encode-cursor {:v 1 :after-id 9 :ms 1000 :until-ms 1234567890
                                           :frame :rf/default})
          ;; `js/Buffer` drops the non-alphabet chars on decode.
          alias     (str (subs canonical 0 2) "!!" (subs canonical 2))]
      (is (= (rf.mcp-base.cursor/b64-decode alias) (rf.mcp-base.cursor/b64-decode canonical))
          "precondition: the alias decodes to the SAME bytes, so it is a true alias")
      (-> (we/watch-epochs-tool nil (tu/args->js {:cursor alias}))
          (.then (fn [result]
                   (is (tu/error? result))
                   (is (= {:ok? false :reason rf.mcp-base.vocab/cursor-stale-reason :tool "watch-epochs"}
                          (select-keys (tu/extract-edn result) [:ok? :reason :tool])))
                   (done)))))))
