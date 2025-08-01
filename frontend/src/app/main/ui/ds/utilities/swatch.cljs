
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.utilities.swatch
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.json :as json]
   [app.common.schema :as sm]
   [app.common.types.color :as ct]
   [app.config :as cfg]
   [app.util.color :as uc]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- color-title
  [color-item]
  (let [{:keys [name path]} (meta color-item)

        path-and-name
        (if (and path (not (str/empty? path)))
          (str path " / " name)
          name)

        gradient (:gradient color-item)
        image    (:image color-item)
        color    (:color color-item)]

    (if (some? name)
      (cond
        (some? color)
        (str/ffmt "% (%)" path-and-name color)

        (some? gradient)
        (str/ffmt "% (%)" path-and-name (uc/gradient-type->string (:type gradient)))

        (some? image)
        (str/ffmt "% (%)" path-and-name (tr "media.image"))

        :else
        path-and-name)

      (cond
        (some? color)
        color

        (some? gradient)
        (uc/gradient-type->string (:type gradient))

        (some? image)
        (tr "media.image")))))

(def ^:private schema:swatch
  [:map {:title "SchemaSwatch"}
   [:background {:optional true} ct/schema:color]
   [:class {:optional true} :string]
   [:size {:optional true} [:enum "small" "medium" "large"]]
   [:active {:optional true} ::sm/boolean]
   [:on-click {:optional true} ::sm/fn]])

(mf/defc swatch*
  {::mf/schema (sm/schema schema:swatch)}
  [{:keys [background on-click size active class]
    :rest props}]
  (let [;; NOTE: this code is only relevant for storybook, because
        ;; storybook is unable to pass in a comfortable way a complex
        ;; object; the "interactive" way of storybook only allows
        ;; plain object. So for this case we accept them and
        ;; automatically convert them to clojure map (which is exactly
        ;; what this component expects). On normal usage of this
        ;; component this code should be always fallback to else case.
        background     (if (object? background)
                         (json/->clj background)
                         background)
        read-only?     (nil? on-click)
        id?            (some? (:ref-id background))
        element-type   (if read-only? "div" "button")
        button-type    (if (not read-only?) "button" nil)
        size           (or size "small")
        active         (or active false)
        gradient-type  (-> background :gradient :type)
        gradient-stops (-> background :gradient :stops)
        gradient-data  {:type gradient-type
                        :stops gradient-stops}
        image          (:image background)
        format         (if id? "rounded" "square")

        class
        (dm/str class " " (stl/css-case
                           :swatch true
                           :small (= size "small")
                           :medium (= size "medium")
                           :large (= size "large")
                           :square (= format "square")
                           :active (= active true)
                           :interactive (= element-type "button")
                           :rounded (= format "rounded")))

        props
        (mf/spread-props props {:class class
                                :on-click on-click
                                :type button-type
                                :title (color-title background)})]

    [:> element-type props
     (cond

       (some? gradient-type)
       [:span {:class (stl/css :swatch-gradient)
               :style {:background-image (str (uc/gradient->css gradient-data) ", repeating-conic-gradient(lightgray 0% 25%, white 0% 50%)")}}]

       (some? image)
       (let [uri (cfg/resolve-file-media image)]
         [:span {:class (stl/css :swatch-image)
                 :style {:background-image (str/ffmt "url(%)" uri)}}])

       :else
       [:span {:class (stl/css :swatch-opacity)}
        [:span {:class (stl/css :swatch-solid-side)
                :style {:background (uc/color->background (assoc background :opacity 1))}}]
        [:span {:class (stl/css :swatch-opacity-side)
                :style {:background (uc/color->background background)}}]])]))
