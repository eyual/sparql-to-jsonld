(ns sparql-to-jsonld.core
  (:gen-class)
  (:require [sparql-to-jsonld.util :as util]
            [sparql-to-jsonld.sparql :as sparql]
            [sparql-to-jsonld.jsonld :as jsonld]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.io :as io :refer [as-file]]
            [clojure.edn :as edn]
            [schema.core :as s]
            [mount.core :as mount]
            [sparclj.core :as sparclj]
            [slingshot.slingshot :refer [try+]])
  (:import (java.io PrintWriter)
           (org.apache.commons.validator.routines UrlValidator)))

; ----- Schemata -----

(def ^:private positive-integer (s/constrained s/Int pos? 'pos?))

(def ^:private non-negative-number (s/constrained s/Num (complement neg?) 'not-neg?))

(def ^:private non-negative-integer (s/constrained s/Int (complement neg?) 'not-neg?))

(def ^:private http? (partial re-matches #"^https?:\/\/.*$"))

(def ^:private valid-url?
  "Test if `url` is valid."
  (let [validator (UrlValidator. UrlValidator/ALLOW_LOCAL_URLS)]
    (fn [url]
      (.isValid validator url))))

(def ^:private url
  (s/pred valid-url? 'valid-url?))

(def ^:private Config
  {::sparclj/url (s/conditional http? url) ; The URL of the SPARQL endpoint.
   (s/optional-key ::sparclj/page-size) positive-integer
   (s/optional-key :sleep) non-negative-number
   (s/optional-key ::sparclj/start-from) non-negative-integer
   (s/optional-key ::sparclj/retries) positive-integer})

; ----- Private functions -----

(defn- error-msg
  [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (util/join-lines errors)))

(defn- exit
  "Exit with @status and message `msg`.
  `status` 0 is OK, `status` 1 indicates error."
  [^Integer status
   ^String msg]
  {:pre [(#{0 1} status)]}
  (binding [*out* (if (pos? status) *err* *out*)]
    (println msg))
  (System/exit status))

(def ^:private die
  (partial exit 1))

(def ^:private info
  (partial exit 0))

(defn- file-exists?
  "Test if file at `path` exists and is a file."
  [path]
  (let [file (as-file path)]
    (and (.exists file) (.isFile file))))

(defn- usage
  [summary]
  (util/join-lines ["Serializes RDF resources from a SPARQL endpoint to JSON-LD documents."
                    "Options:\n"
                    summary]))

(def ^:private validate-config
  "Validate configuration `config` according to its schema."
  (let [expected-structure (s/explain Config)]
    (fn [config]
      (try (s/validate Config config) nil
           (catch RuntimeException e (util/join-lines ["Invalid configuration:"
                                                       (.getMessage e)
                                                       "The expected structure of configuration is:"
                                                       expected-structure]))))))

(defn- main
  [{:keys [sleep]
    :as config}
   {:keys [select describe frame output remove-jsonld-context]}]
  (try+
    (mount/start-with-args config)
    (catch [:type ::sparclj/connect-exception] {:keys [message]}
      (die message)))
  (try (let [select-query (slurp select)
             describe-query (slurp describe)
             frame' (jsonld/string->jsonld (slurp frame))
             describe (fn [resource]
                        (when-not (zero? sleep)
                          (Thread/sleep (* sleep 1000)))
                        (sparql/describe-query describe-query resource))
             frame-fn (partial jsonld/frame-jsonld frame')
             compact-fn (partial jsonld/compact-jsonld frame')
             serialize-fn (fn [data] (jsonld/jsonld->string data :remove-jsonld-context? remove-jsonld-context))
             convert-fn (comp compact-fn frame-fn jsonld/model->jsonld)
             print-fn (fn [description] (println description) (flush))
             descriptions (->> select-query
                               sparql/select-query-unlimited
                               (pmap (comp describe :resource))
                               (remove (memfn isEmpty))
                               (pmap convert-fn)
                               (remove empty?)
                               (map (comp print-fn serialize-fn)))]
         (if (= output *out*)
           (dorun descriptions)
           (with-open [writer (io/writer output)]
             (binding [*out* writer]
               (dorun descriptions)))))
       (finally (shutdown-agents))))

; ----- Private vars -----

(def ^:private cli-options
  [["-c" "--config CONFIG" "Path to configuration file in EDN"
    :parse-fn (comp edn/read-string slurp)]
   ["-s" "--select SELECT" "Path to SPARQL file to select resources"
    :validate [file-exists? "The SPARQL file to select resources doesn't exist!"]]
   ["-d" "--describe DESCRIBE" "Path to SPARQL file to describe resources"
    :validate [file-exists? "The SPARQL file to describe resources doesn't exist!"]]
   ["-f" "--frame FRAME" "Path to a JSON-LD frame to format data"
    :validate [file-exists? "The JSON-LD frame doesn't exist!"]]
   ["-o" "--output OUTPUT" "Path to the output file"
    :default *out*]
   [nil "--remove-jsonld-context" "Remove JSON-LD context from the output"
    :default false]
   ["-h" "--help" "Display help message"]])

; ----- Public functions -----

(defn -main
  [& args]
  (let [{{:keys [config help]
          :as options} :options
         :keys [errors summary]} (parse-opts args cli-options)
        ; Merge defaults
        config' (merge {::sparclj/retries 5
                        ::sparclj/page-size 1000
                        :sleep 0
                        ::sparclj/start-from 0}
                       config)]
    (cond help (info (usage summary))
          errors (die (error-msg errors))
          :else (if-let [error (validate-config config')]
                  (die error)
                  (main config' options)))))
