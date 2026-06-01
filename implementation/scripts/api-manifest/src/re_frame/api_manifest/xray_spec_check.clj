(ns re-frame.api-manifest.xray-spec-check
  "Xray API-spec projection check (rf2-gkp0t).

  `tools/xray/spec/API.md` enumerates Xray's public CLJS surface. This
  check parses the references the spec spells AS public-var symbols — the
  fully-qualified `day8.re-frame2-xray.*/<var>` Panel reg-views and the
  `mount-<panel>!` aggregator family — and reconciles each against the
  manifest's Xray rows (the `:cljs-only` Xray surface, namespace prefix
  `day8.re-frame2-xray`).

  SCOPE (deliberately narrow). The Xray spec is prose-heavy and names
  dozens of keywords, config keys, and CSS vars; a bare-token sweep would
  be all false-positive. This check scopes to two ACTUAL public-var
  reference shapes: (1) fully-namespace-qualified `day8.re-frame2-xray.*/`
  symbols (the canonical `panels.<area>/Panel` inventory), and (2) the
  `mount-<panel>!` family the doc names as the mountable-panel contract.

  REPORT-ONLY POSTURE (rf2-gkp0t). The Xray spec FILE is owned by a
  concurrent worker; this check does not edit it. The manifest's Xray
  coverage today is the single `mounted?` read, so the live panel + mount
  surface is carried on the `:xray-spec-known-unmanifested` allowlist in
  the sidecar — REPORTING that the manifest does not yet carry the Xray
  public surface (a manifest-expansion follow-on owns that). The check is
  green today but goes RED the moment a NEW Xray panel/mount symbol enters
  the spec or an enumerated one is renamed — the keystone allowlist
  discipline applied to a not-yet-manifested surface."
  (:require [re-frame.api-manifest.gen :as gen]
            [re-frame.api-manifest.projection :as proj]))

(def ^:private xray-ns-prefix "day8.re-frame2-xray.")

(def ^:private mount-fn-re
  "The `mount-<panel>!` aggregator family the spec names as the mountable-
   panel contract (007-UX-IA §Mountable panel contract)."
  #"\bmount-[a-z][a-z-]*!")

(defn- mount-references
  "Extract `mount-<panel>!` references from numbered `lines`."
  [lines]
  (for [[n text] lines
        m         (re-seq mount-fn-re text)]
    {:var m :line n :raw m}))

(defn check!
  []
  (let [rows      (proj/manifest-rows)
        ;; Manifest Xray rows, indexed by bare var (the only one today is
        ;; `mounted?`); a fully-qualified panel symbol resolves if its bare
        ;; var is carried for ANY Xray namespace.
        xray-vars (set (map :var (filter #(.startsWith ^String (:namespace %) xray-ns-prefix) rows)))
        allow     (set (:xray-spec-known-unmanifested (gen/read-sidecar)))
        file      (proj/repo-file "tools" "xray" "spec" "API.md")
        rel       (proj/repo-relative file)
        lines     (proj/numbered-lines file)
        refs      (concat (proj/qualified-symbol-references xray-ns-prefix lines)
                          (mount-references lines))
        ;; De-dup by [var line] so a symbol named twice on one line counts once.
        refs      (distinct refs)
        problems  (keep (fn [{:keys [var line raw]}]
                          (when-not (or (contains? xray-vars var)
                                        (contains? allow var))
                            {:file rel :line line :raw raw
                             :detail "no day8.re-frame2-xray manifest row"}))
                        refs)]
    (proj/report-result! "tools/xray/spec/API.md" (count refs) problems)))

(defn -main [& _]
  (System/exit (if (check!) 0 1)))
