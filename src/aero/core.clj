;; Copyright © 2015, JUXT LTD.

(ns aero.core
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :refer (trim)]
   [clojure.java.shell :as sh]
   [schema.core :as s]))

(defmulti reader (fn [opts tag value] tag))

(defmethod reader 'env
  [opts tag value]
  (cond (vector? value) (or (System/getenv (str (first value))) (second value))
        :otherwise (System/getenv (str value))))

(defmethod reader 'cond
  [{:keys [profile]} tag value]
  (cond (contains? value profile) (clojure.core/get value profile)
        (contains? value :default) (clojure.core/get value :default)
        :otherwise nil))

(defmethod reader 'hostname
  [{:keys [hostname]} tag value]
  (let [hostn (or hostname (-> (sh/sh "hostname") :out trim))]
    (or
     (some (fn [[k v]]
             (when (or (= k hostn)
                       (and (set? k) (contains? k hostn)))
               v))
           value)
     (get value :default))))

(defn read-config
  "Optional second argument is a map. Keys are :profile, indicating the
  profile for use with #cond"
  ([r {:keys [schema] :as opts}]
   (let [config
         (with-open [pr (java.io.PushbackReader. (io/reader r))]
           (edn/read
            {:eof nil
             :default (partial reader (merge {:profile :default} opts))}
            pr))]
     (when schema
       (s/validate schema config))
     config))
  ([r]
   (read-config r {})))
