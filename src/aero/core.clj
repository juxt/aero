;; Copyright © 2015, JUXT LTD.

(ns aero.core
  (:require [clojure
             [edn :as edn]
             [string :refer [trim]]
             [walk :refer [walk postwalk]]]
            [clojure.java
             [io :as io]
             [shell :as sh]]
            [schema.core :as s]))

(declare read-config)
(defmulti reader (fn [opts tag value] tag))
(defmulti transform (fn [opts tag config-map] tag))

(defmethod transform :default
  [opts tag config-map]
  config-map)

(defmethod reader :default
  [_ tag value]
  (if tag
    (with-meta value {::tag tag})
    value))

(defmethod reader 'env
  [opts tag value]
  (cond
    (vector? value) (or (System/getenv (str (first value)))
                        (second value))
    :otherwise (System/getenv (str value))))

(defmethod reader 'envf
  [opts tag [fmt & args]]
  (apply format fmt (map (partial reader opts 'env) args)))

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

(defmethod reader 'user
  [{:keys [user]} tag value]
  (let [user (or user (-> (sh/sh "whoami") :out trim))]
    (or
     (some (fn [[k v]]
             (when (or (= k user)
                       (and (set? k) (contains? k user)))
               v))
           value)
     (get value :default))))

(defmethod reader 'file
  [opts tag value]
  (read-config value opts))

(defmethod reader 'path
  [opts tag value]
  (with-meta value {::tag 'path}))

(defmethod transform 'format
  [opts tag config-map]
  (postwalk (fn [v]
              (if (= 'format (::tag (meta v)))
                (apply format (first v) (rest v))
                v))
            config-map))

(defmethod transform 'path
  [opts tag config-map]
  (postwalk (fn [v]
              (if-not (= 'path (::tag (meta v)))
                v
                (recur (get-in config-map v))))
            config-map))

(defmethod transform 'schema
  [opts tag config-map]
  (let [schema (get-in opts [:schema])]
    (if schema
      (s/validate schema config-map)
      (throw (java.lang.IllegalArgumentException.
              ":schema not specified in opts map")))))

(defn read-config
  "Optional second argument is a map. Keys are :profile, indicating the
  profile for use with #cond"
  ([r {:keys [schema transforms] :as opts}]
   (let [config (with-open [pr (java.io.PushbackReader. (io/reader r))]
                  (edn/read
                   {:eof nil
                    :default (partial reader (merge {:profile :default
                                                     :filepath (str r)} opts))}
                   pr))]
     (reduce (fn [acc tag]
               (transform opts
                          ((comp symbol name) tag)
                          acc)) config transforms)))
  ([r] (read-config r {})))
