(ns re-frame.util-json
  "Tiny shared JSON helpers.

  Extracted from `re-frame.http-managed` per rf2-p7da. The decode path
  in `re-frame.http-encoding` needs a JSON reader; on CLJS that is the
  browser's `js/JSON.parse`, on the JVM it is Cheshire (a hard dep
  since rf2-dgsu1 — see the artefact's `deps.edn` for the rationale).

  ## Why this lives in the http artefact for now

  The reader is only used by the decode pipeline. Re-frame core has no
  business reading JSON. Keeping it in the http artefact (rather than
  promoting it to `day8/re-frame2`) avoids dragging the
  `json-stringify` / `json-parse` codepaths onto core consumers that
  never issue an HTTP request. If a second consumer needs JSON down the
  track, lift this namespace to core (the ns name is already neutral —
  `re-frame.util-json`, not `re-frame.http.util-json`).

  ## API

  - `json-stringify` — Clojure → JSON string. Uses Cheshire's
    `generate-string` on JVM; CLJS uses `js/JSON.stringify`.
  - `json-parse`     — string → Clojure data with keyword keys for
    objects. Uses Cheshire's `parse-string` on JVM; CLJS uses
    `js/JSON.parse` + `js->clj :keywordize-keys true`. Accepts an
    optional `opts` map (`:max-decoded-keys` — per rf2-wu1n5).

  ## Keyword-interning cap (rf2-wu1n5)

  Per security audit 2026-05-14 §P1.4: JVM keywords are interned and
  never GC'd. An attacker-controlled JSON response with N unique keys
  permanently burns N keyword slots — a long-running SSR JVM is the
  worst case. Both readers enforce a per-call cap on the number of
  unique keys decoded (default `default-max-decoded-keys` = 10000;
  overridable per request via `(json-parse s {:max-decoded-keys N})`).
  Overflow throws `:rf.error/id :rf.error/malformed-json` with
  `:cause :too-many-keys` — the `:rf.http/managed` cascade classifies
  this as `:rf.http/decode-failure`."
  #?(:clj  (:require [cheshire.core :as cheshire])))

(def ^:const default-max-decoded-keys
  "Default cap on the number of unique object keys a single `json-parse`
  call may decode. Per rf2-wu1n5 — a defensive ceiling against the
  keyword-interning DoS surface on long-running JVMs. Generous enough
  not to false-positive on legitimate large responses while finite
  enough to bound an attacker-controlled payload."
  10000)

(defn json-stringify
  "Clojure value → JSON string. JVM uses Cheshire (`generate-string`);
  CLJS uses `js/JSON.stringify`."
  [v]
  #?(:clj  (cheshire/generate-string v)
     :cljs (js/JSON.stringify (clj->js v))))

(defn json-parse
  "JSON string → Clojure data with keyword keys for object keys. JVM
  uses Cheshire's `parse-string`; CLJS uses `js/JSON.parse` +
  `js->clj :keywordize-keys true`.

  `opts` (optional) — currently a single key:
   - `:max-decoded-keys` — cap on unique object keys (rf2-wu1n5).
     Default: `default-max-decoded-keys`. Overflow throws
     `:rf.error/id :rf.error/malformed-json` with `:cause :too-many-keys`.

  Per rf2-wu1n5, both branches enforce the keyword-cap. The JVM
  Cheshire branch installs a `:key-fn` callback that counts distinct
  keys in a HashSet and throws on overflow; the CLJS branch walks the
  parsed JS tree counting unique object-keys BEFORE
  `js->clj :keywordize-keys true` interns them."
  ([s] (json-parse s nil))
  ([s opts]
   (let [max-keys (long (or (:max-decoded-keys opts) default-max-decoded-keys))]
     #?(:clj  (when (string? s)
                (let [seen   ^java.util.HashSet (java.util.HashSet.)
                      key-fn (fn [k]
                               (when-not (.contains seen k)
                                 (.add seen k)
                                 (when (> (.size seen) max-keys)
                                   (throw (ex-info ":rf.error/malformed-json"
                                                   {:rf.error/id :rf.error/malformed-json
                                                    :where       'rf.http/json-parse
                                                    :recovery    :no-recovery
                                                    :reason      (str "JSON payload exceeded the per-call unique-key cap (" max-keys ") — a keyword-interning DoS guard")
                                                    :cause       :too-many-keys
                                                    :limit       max-keys}))))
                               (keyword k))]
                  (cheshire/parse-string s key-fn)))
        :cljs (when (string? s)
                ;; rf2-x1uhu — mirror the JVM Cheshire branch's
                ;; `(when (string? s) ...)` guard so both hosts return nil
                ;; (rather than CLJS throwing inside `js/JSON.parse`) on a
                ;; non-string input. `util-json` is documented as a
                ;; shared/promotable helper; the two readers must behave
                ;; identically.
                (let [parsed (js/JSON.parse s)
                    ;; rf2-wu1n5 — CLJS path: walk the parsed JS object
                    ;; tree counting unique object-keys BEFORE
                    ;; `js->clj :keywordize-keys true` interns them.
                    ;; The browser JS engine GCs unreferenced symbols
                    ;; (unlike the JVM), so the DoS surface is much
                    ;; smaller — but a 10000-key payload still gives
                    ;; the runtime garbage to clean up, so we cap
                    ;; consistently across hosts.
                    seen   (volatile! #{})
                    _      (letfn [(walk [v]
                                     (cond
                                       (array? v)
                                       (doseq [x v] (walk x))

                                       (and (object? v) (not (nil? v)))
                                       (let [ks (js-keys v)]
                                         (doseq [k ks]
                                           (when-not (contains? @seen k)
                                             (vswap! seen conj k)
                                             (when (> (count @seen) max-keys)
                                               (throw (ex-info ":rf.error/malformed-json"
                                                               {:rf.error/id :rf.error/malformed-json
                                                                :where       'rf.http/json-parse
                                                                :recovery    :no-recovery
                                                                :reason      (str "JSON payload exceeded the per-call unique-key cap (" max-keys ") — a keyword-interning DoS guard")
                                                                :cause       :too-many-keys
                                                                :limit       max-keys})))))
                                         (doseq [k ks] (walk (aget v k))))))]
                             (walk parsed))]
                  (js->clj parsed :keywordize-keys true)))))))
