#!/usr/bin/env nbb
(ns statement-fetch
  "CLI for kotoba-lang/statement-fetch.

    nbb bin/statement_fetch.cljs list
    nbb bin/statement_fetch.cljs verify <flow>
    nbb bin/statement_fetch.cljs plan   <flow> --from 2026-04-26 --to 2026-07-25 --out ./s.pdf
    nbb bin/statement_fetch.cljs fetch  <flow> --from … --to … --out … [--profile Default]
    nbb bin/statement_fetch.cljs discover [--profile Default]"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [kotoba.statement-fetch :as sf]
            [kotoba.statement-fetch.agent-browser :as ab]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(defn- institutions-dir []
  (loop [dir (js/process.cwd) depth 0]
    (let [candidate (path/join dir "resources" "institutions")]
      (cond
        (fs/existsSync candidate) candidate
        (> depth 5) (throw (ex-info "resources/institutions not found" {:from (js/process.cwd)}))
        :else (recur (path/dirname dir) (inc depth))))))

(defn- flow-files []
  (->> (fs/readdirSync (institutions-dir))
       (filter #(str/ends-with? % ".edn"))
       sort
       vec))

(defn- load-flow [name']
  (let [dir  (institutions-dir)
        file (if (str/ends-with? name' ".edn") name' (str name' ".edn"))
        p    (path/join dir file)]
    (when-not (fs/existsSync p)
      (throw (ex-info (str "no such flow: " file) {:available (flow-files)})))
    (edn/read-string (fs/readFileSync p "utf8"))))

(defn- parse-opts [args]
  (into {} (for [[k v] (partition 2 args)
                 :when (str/starts-with? k "--")]
             [(keyword (subs k 2)) v])))

;; ---------------------------------------------------------------------------

(defn- pad [s n]
  (let [s (str s)]
    (str s (str/join (repeat (max 0 (- n (count s))) " ")))))

(defn cmd-list []
  (doseq [f (flow-files)]
    (let [flow (load-flow f)
          unv  (count (sf/unverified-steps flow))]
      (println (str (pad (str/replace f ".edn" "") 46)
                    (pad (name (:document/language flow)) 8)
                    (if (zero? unv) "verified" (str unv " unverified step(s)")))))))

(defn cmd-verify [name']
  (let [flow   (load-flow name')
        errors (sf/validate-flow flow)
        unv    (sf/unverified-steps flow)]
    (if (seq errors)
      (do (println "✗ invalid flow:") (pp/pprint errors) (js/process.exit 1))
      (do (println "✓ flow is structurally valid (no credential-bearing step)")
          (when (seq unv)
            (println (str "⚠ " (count unv) " step(s) still carry unverified selectors:"))
            (doseq [s unv]
              (println "   -" (:step/index s) (name (:step/type s)) (:step/selector s))))))))

(defn cmd-plan [name' opts]
  (pp/pprint (sf/plan (load-flow name') opts)))

(defn cmd-fetch [name' opts]
  (let [flow (load-flow name')
        unv  (sf/unverified-steps flow)]
    (when (and (seq unv) (not (:force opts)))
      (println (str "✗ refusing to run: " (count unv) " unverified selector(s)."))
      (println "  Authenticate, run `discover`, encode the real selectors, then retry.")
      (println "  Override with --force true only to probe.")
      (js/process.exit 1))
    (let [plan (sf/plan flow opts)
          res  (ab/run-plan plan opts)]
      (if (:ok? res)
        (println "✓ done →" (:out opts))
        (do (println "✗ failed at" (name (:stage res)))
            (pp/pprint (or (:failed res) (:result res)))
            (js/process.exit 1))))))

(defn cmd-discover [opts]
  (let [{:keys [url snap]} (ab/discover opts)]
    (println ";; url:" url)
    (println snap)))

(let [[cmd & args] (vec *command-line-args*)
      opts (parse-opts (if (and (seq args) (not (str/starts-with? (first args) "--")))
                         (rest args) args))
      target (when (and (seq args) (not (str/starts-with? (first args) "--"))) (first args))]
  (case cmd
    "list"     (cmd-list)
    "verify"   (cmd-verify target)
    "plan"     (cmd-plan target opts)
    "fetch"    (cmd-fetch target opts)
    "discover" (cmd-discover opts)
    (do (println "usage: list | verify <flow> | plan <flow> | fetch <flow> | discover")
        (js/process.exit 1))))
