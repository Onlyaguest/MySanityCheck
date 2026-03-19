(ns ems.engine.complaint
  (:require [clojure.string :as str]))

(def complaint-keywords
  #{"累" "烦" "没睡好" "不想" "头疼" "难受" "焦虑" "失眠" "不舒服" "差" "困" "崩溃" "烦躁"})

(defn complaint?
  "Returns true if text contains any complaint keyword. Deterministic, no LLM."
  [text]
  (when (and text (not (str/blank? text)))
    (boolean (some #(str/includes? text %) complaint-keywords))))
