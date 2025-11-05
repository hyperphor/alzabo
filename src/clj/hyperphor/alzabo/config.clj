(ns hyperphor.alzabo.config
  (:require [clojure.edn :as edn]
            [aero.core :as aero]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [clojure.string :as str]
            [hyperphor.multitool.core :as u])
  )

;;; See resources/default-config.edn

(def the-config (atom nil))
(def config-path (atom nil))

(defmethod aero/reader 'split
  [_ _ [s]]
  (and s (str/split s #",")))

;;; TODO wanted this to be in a resource, but then its hard to get it working when alz is a library...jfc
(def default-config
  {
   :edge-labels? true
   :width 100
   :height 64
   :orientation :horizontal
   :categories {:default {:color "#a9cce3"}}
   })

(defn set-config!
  [config]                              ;filename or map
  (let [config (if (string? config)
                 (aero/read-config config)
                 config)
        defaulted (merge default-config config)] ; shouldn't Aero be able to do th
    (reset! the-config defaulted)))

(defn set!
  [att value]
  (swap! the-config assoc att value))

(defn config
  [& keys]
  (assert @the-config "Config not set")
  (get-in @the-config keys))

(defn output-path
  [filename]
  (str
   ;; This rigamarole lets you use relative paths and fs/with-cwd
   (fs/file
    (str (u/expand-template (config :output-path) (config))
         filename))))

;;; This bit of hackery seems to come and go
;; TODO → multitool (with some cleanup)
(defn realize-rel-path
  [base path]
  (str (.getParentFile (fs/file base))
       "/"
       path))

(defn realize-path
  [path]
  (realize-rel-path @config-path path))

