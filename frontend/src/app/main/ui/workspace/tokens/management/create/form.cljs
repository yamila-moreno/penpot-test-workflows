;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.create.form
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.tokens :as cft]
   [app.common.types.color :as c]
   [app.common.types.token :as ctt]
   [app.common.types.tokens-lib :as ctob]
   [app.main.constants :refer [max-input-length]]
   [app.main.data.modal :as modal]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.tinycolor :as tinycolor]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.main.data.workspace.tokens.warnings :as wtw]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.controls.utilities.hint-message :refer [hint-message*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.workspace.colorpicker :as colorpicker]
   [app.main.ui.workspace.colorpicker.ramp :refer [ramp-selector*]]
   [app.main.ui.workspace.sidebar.options.menus.typography :refer [font-selector*]]
   [app.main.ui.workspace.tokens.management.create.input-token-color-bullet :refer [input-token-color-bullet*]]
   [app.main.ui.workspace.tokens.management.create.input-tokens-value :refer [input-tokens-value*]]
   [app.util.dom :as dom]
   [app.util.functions :as uf]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as k]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [malli.core :as m]
   [malli.error :as me]
   [rumext.v2 :as mf]))

;; Schemas ---------------------------------------------------------------------

(def valid-token-name-regexp
  "Only allow letters and digits for token names.
  Also allow one `.` for a namespace separator.

  Caution: This will allow a trailing dot like `token-name.`,
  But we will trim that in the `finalize-name`,
  to not throw too many errors while the user is editing."
  #"(?!\$)([a-zA-Z0-9-$_]+\.?)*")

(def valid-token-name-schema
  (m/-simple-schema
   {:type :token/invalid-token-name
    :pred #(re-matches valid-token-name-regexp %)
    :type-properties {:error/fn #(str (:value %) (tr "workspace.tokens.token-name-validation-error"))}}))

(defn token-name-schema
  "Generate a dynamic schema validation to check if a token path derived from the name already exists at `tokens-tree`."
  [{:keys [tokens-tree]}]
  (let [path-exists-schema
        (m/-simple-schema
         {:type :token/name-exists
          :pred #(not (cft/token-name-path-exists? % tokens-tree))
          :type-properties {:error/fn #(str "A token already exists at the path: " (:value %))}})]
    (m/schema
     [:and
      [:string {:min 1 :max 255}]
      valid-token-name-schema
      path-exists-schema])))

(def token-description-schema
  (m/schema
   [:string {:max 2048}]))

;; Helpers ---------------------------------------------------------------------

(defn finalize-name [name]
  (-> (str/trim name)
      ;; Remove trailing dots
      (str/replace #"\.+$" "")))

(defn valid-name? [name]
  (seq (finalize-name (str name))))

(defn finalize-value [value]
  (-> (str value)
      (str/trim)))

(defn valid-value? [value]
  (seq (finalize-value value)))

;; Validation ------------------------------------------------------------------

(defn validate-empty-input [value]
  (if (sequential? value)
    (empty? value)
    (empty? (str/trim value))))

(defn validate-self-reference? [token-name value]
  (if (sequential? value)
    (some #(ctob/token-value-self-reference? token-name %) value)
    (ctob/token-value-self-reference? token-name value)))

(defn validate-token-value
  "Validates token value by resolving the value `input` using `StyleDictionary`.
  Returns a promise of either resolved tokens or rejects with an error state."
  [{:keys [value name-value token tokens]}]
  (let [;; When creating a new token we dont have a token name yet,
        ;; so we use a temporary token name that hopefully doesn't clash with any of the users token names
        token-name (if (str/empty? name-value) "__TOKEN_STUDIO_SYSTEM.TEMP" name-value)]
    (cond
      (validate-empty-input value)
      (rx/throw {:errors [(wte/get-error-code :error.token/empty-input)]})

      (validate-self-reference? token-name value)
      (rx/throw {:errors [(wte/get-error-code :error.token/direct-self-reference)]})

      :else
      (let [tokens' (cond-> tokens
                      ;; Remove previous token when renaming a token
                      (not= name-value (:name token)) (dissoc (:name token))
                      :always (update token-name #(ctob/make-token (merge % {:value (cond
                                                                                      (= (:type token) :font-family) (ctt/split-font-family value)
                                                                                      :else value)
                                                                             :name token-name
                                                                             :type (:type token)}))))]
        (->> tokens'
             (sd/resolve-tokens-interactive)
             (rx/mapcat
              (fn [resolved-tokens]
                (let [{:keys [errors resolved-value] :as resolved-token} (get resolved-tokens token-name)]
                  (cond
                    resolved-value (rx/of resolved-token)
                    :else (rx/throw {:errors (or errors (wte/get-error-code :error/unknown-error))}))))))))))

(defn use-debonced-resolve-callback
  "Resolves a token values using `StyleDictionary`.
  This function is debounced as the resolving might be an expensive calculation.
  Uses a custom debouncing logic, as the resolve function is async."
  [name-ref token tokens callback & {:keys [timeout] :or {timeout 160}}]
  (let [timeout-id-ref (mf/use-ref nil)
        debounced-resolver-callback
        (mf/use-fn
         (mf/deps token callback tokens)
         (fn [value]
           (let [timeout-id (js/Symbol)
                 ;; Dont execute callback when the timout-id-ref is outdated because this function got called again
                 timeout-outdated-cb? #(not= (mf/ref-val timeout-id-ref) timeout-id)]
             (mf/set-ref-val! timeout-id-ref timeout-id)
             (js/setTimeout
              (fn []
                (when (not (timeout-outdated-cb?))
                  (->> (validate-token-value {:value value
                                              :name-value @name-ref
                                              :token token
                                              :tokens tokens})
                       (rx/filter #(not (timeout-outdated-cb?)))
                       (rx/subs!
                        callback
                        callback))))
              timeout))))]
    debounced-resolver-callback))

(defonce form-token-cache-atom (atom nil))

;; Component -------------------------------------------------------------------

(mf/defc token-value-hint
  [{:keys [result]}]
  (let [{:keys [errors warnings resolved-value]} result
        empty-message? (nil? result)

        message (cond
                  empty-message? (tr "workspace.tokens.resolved-value" "-")
                  warnings (wtw/humanize-warnings warnings)
                  errors (->> (wte/humanize-errors errors)
                              (str/join "\n"))
                  :else (tr "workspace.tokens.resolved-value" (or resolved-value result)))
        type (cond
               empty-message? "hint"
               errors "error"
               warnings "warning"
               :else "hint")]
    [:> hint-message*
     {:id "token-value-hint"
      :message message
      :class (stl/css-case :resolved-value (not (or empty-message? (seq warnings) (seq errors))))
      :type type}]))

(mf/defc form*
  [{:keys [token token-type action selected-token-set-name transform-value on-value-resolve custom-input-token-value custom-input-token-value-props]}]
  (let [create? (not (instance? ctob/Token token))
        token (or token {:type token-type})
        token-properties (dwta/get-token-properties token)
        tokens-in-selected-set (mf/deref refs/workspace-all-tokens-in-selected-set)

        active-theme-tokens (cond-> (mf/deref refs/workspace-active-theme-sets-tokens)
                              ;; Ensure that the resolved value uses the currently editing token
                              ;; even if the name has been overriden by a token with the same name
                              ;; in another set below.
                              (and (:name token) (:value token))
                              (assoc (:name token) token)

                              ;; Style dictionary resolver needs font families to be an array of strings
                              (= :font-family (or (:type token) token-type))
                              (update-in [(:name token) :value] ctt/split-font-family))

        resolved-tokens (sd/use-resolved-tokens active-theme-tokens {:cache-atom form-token-cache-atom
                                                                     :interactive? true})
        token-path (mf/use-memo
                    (mf/deps (:name token))
                    #(cft/token-name->path (:name token)))

        tokens-tree-in-selected-set (mf/use-memo
                                     (mf/deps token-path tokens-in-selected-set)
                                     (fn []
                                       (-> (ctob/tokens-tree tokens-in-selected-set)
                                           ;; Allow setting editing token to it's own path
                                           (d/dissoc-in token-path))))
        ;; Name
        touched-name* (mf/use-state false)
        touched-name? (deref touched-name*)
        warning-name-change* (mf/use-state false)
        warning-name-change? (deref warning-name-change*)
        token-name-ref (mf/use-var (:name token))
        name-ref (mf/use-ref nil)
        name-errors (mf/use-state nil)

        validate-name
        (mf/use-fn
         (mf/deps tokens-tree-in-selected-set)
         (fn [value]
           (let [schema (token-name-schema {:token token
                                            :tokens-tree tokens-tree-in-selected-set})]
             (m/explain schema (finalize-name value)))))

        on-blur-name
        (mf/use-fn
         (mf/deps touched-name? warning-name-change?)
         (fn [e]
           (let [value (dom/get-target-val e)
                 errors (validate-name value)]
             (when touched-name?
               (reset! warning-name-change* true))
             (reset! name-errors errors))))

        on-update-name-debounced
        (mf/use-fn
         (mf/deps touched-name? validate-name)
         (uf/debounce (fn [token-name]
                        (let [errors (validate-name token-name)]
                          (when touched-name?
                            (reset! name-errors errors))))
                      300))

        on-update-name
        (mf/use-fn
         (mf/deps on-update-name-debounced name-ref)
         (fn []
           (let [ref (mf/ref-val name-ref)
                 token-name (dom/get-value ref)]
             (reset! touched-name* true)
             (reset! token-name-ref token-name)
             (on-update-name-debounced token-name))))

        valid-name-field? (and
                           (not @name-errors)
                           (valid-name? @token-name-ref))

        ;; Value
        value-input-ref (mf/use-ref nil)
        value-ref (mf/use-ref (:value token))

        token-resolve-result* (mf/use-state (get resolved-tokens (cft/token-identifier token)))
        token-resolve-result (deref token-resolve-result*)

        set-resolve-value
        (mf/use-fn
         (mf/deps on-value-resolve)
         (fn [token-or-err]
           (let [error? (:errors token-or-err)
                 warnings? (:warnings token-or-err)
                 v (cond
                     error?
                     (do
                       (when on-value-resolve (on-value-resolve nil))
                       token-or-err)

                     warnings?
                     (:warnings {:warnings token-or-err})

                     :else
                     (cond-> (:resolved-value token-or-err)
                       on-value-resolve on-value-resolve))]
             (reset! token-resolve-result* v))))

        on-update-value-debounced (use-debonced-resolve-callback token-name-ref token active-theme-tokens set-resolve-value)
        on-update-value
        (mf/use-fn
         (mf/deps on-update-value-debounced transform-value)
         (fn [e]
           (let [value (dom/get-target-val e)
                 value' (if (fn? transform-value)
                          (transform-value value)
                          value)]
             ;; Value got updated in transform, update the dom node
             (when (not= value value')
               (dom/set-value! (mf/ref-val value-input-ref) value'))
             (mf/set-ref-val! value-ref value)
             (on-update-value-debounced value))))

        on-external-update-value
        (mf/use-fn
         (mf/deps on-update-value-debounced)
         (fn [next-value]
           (dom/set-value! (mf/ref-val value-input-ref) next-value)
           (mf/set-ref-val! value-ref next-value)
           (on-update-value-debounced next-value)))

        value-error? (seq (:errors token-resolve-result))

        valid-value-field? (and
                            (not value-error?)
                            (valid-value? token-resolve-result))

        ;; Description
        description-ref (mf/use-var (:description token))
        description-errors* (mf/use-state nil)
        description-errors (deref description-errors*)

        validate-descripion (mf/use-fn #(m/explain token-description-schema %))
        on-update-description-debounced (mf/use-fn
                                         (uf/debounce (fn [e]
                                                        (let [value (dom/get-target-val e)
                                                              errors (validate-descripion value)]
                                                          (reset! description-errors* errors)))))
        on-update-description
        (mf/use-fn
         (mf/deps on-update-description-debounced)
         (fn [e]
           (reset! description-ref (dom/get-target-val e))
           (on-update-description-debounced e)))
        valid-description-field? (not description-errors)

        ;; Form
        disabled? (or (not valid-name-field?)
                      (not valid-value-field?)
                      (not valid-description-field?))

        on-submit
        (mf/use-fn
         (mf/deps validate-name validate-descripion token active-theme-tokens)
         (fn [e]
           (dom/prevent-default e)
           ;; We have to re-validate the current form values before submitting
           ;; because the validation is asynchronous/debounced
           ;; and the user might have edited a valid form to make it invalid,
           ;; and press enter before the next validations could return.
           (let [final-name (finalize-name @token-name-ref)
                 valid-name? (try
                               (not (:errors (validate-name final-name)))
                               (catch js/Error _ nil))
                 final-value (let [value (mf/ref-val value-ref)
                                   font-family? (= :font-family (or (:type token) token-type))]
                               (if font-family?
                                 (ctt/split-font-family value)
                                 (finalize-value value)))
                 final-description @description-ref
                 valid-description? (if final-description
                                      (try
                                        (not (:errors (validate-descripion final-description)))
                                        (catch js/Error _ nil))
                                      true)]
             (when (and valid-name? valid-description?)
               (->> (validate-token-value {:value final-value
                                           :name-value final-name
                                           :token token
                                           :tokens active-theme-tokens})
                    (rx/subs!
                     (fn []
                       (st/emit!
                        (if (ctob/token? token)
                          (dwtl/update-token (:id token)
                                             {:name final-name
                                              :value final-value
                                              :description final-description})

                          (dwtl/create-token {:name final-name
                                              :type token-type
                                              :value final-value
                                              :description final-description}))
                        (dwtp/propagate-workspace-tokens)
                        (modal/hide)))))))))

        on-delete-token
        (mf/use-fn
         (mf/deps selected-token-set-name)
         (fn [e]
           (dom/prevent-default e)
           (modal/hide!)
           (st/emit! (dwtl/delete-token
                      (ctob/prefixed-set-path-string->set-name-string selected-token-set-name)
                      (:id token)))))

        on-cancel
        (mf/use-fn
         (fn [e]
           (dom/prevent-default e)
           (modal/hide!)))

        handle-key-down-delete
        (mf/use-fn
         (mf/deps on-delete-token)
         (fn [e]
           (when (k/enter? e)
             (on-delete-token e))))

        handle-key-down-cancel
        (mf/use-fn
         (mf/deps on-cancel)
         (fn [e]
           (when (k/enter? e)
             (on-cancel e))))

        handle-key-down-save
        (mf/use-fn
         (fn [e]
           (mf/deps on-submit)
           (when (k/enter? e)
             (on-submit e))))]

    ;; Clear form token cache on unmount
    (mf/use-effect
     (fn []
       #(reset! form-token-cache-atom nil)))

    ;; Update the value when editing an existing token
    ;; so the user doesn't have to interact with the form to validate the token
    (mf/use-effect
     (mf/deps create? resolved-tokens token token-resolve-result set-resolve-value)
     (fn []
       (when (and (not create?)
                  (not token-resolve-result)
                  resolved-tokens)
         (-> (get resolved-tokens @token-name-ref)
             (set-resolve-value)))))

    [:form {:class (stl/css :form-wrapper)
            :on-submit on-submit}
     [:div {:class (stl/css :token-rows)}
      [:> heading* {:level 2 :typography "headline-medium" :class (stl/css :form-modal-title)}
       (if (= action "edit")
         (tr "workspace.tokens.edit-token")
         (tr "workspace.tokens.create-token" token-type))]

      [:div {:class (stl/css :input-row)}
       (let [token-title (str/lower (:title token-properties))]
         [:> input* {:id "token-name"
                     :label (tr "workspace.tokens.token-name")
                     :placeholder (tr "workspace.tokens.enter-token-name", token-title)
                     :max-length max-input-length
                     :variant "comfortable"
                     :auto-focus true
                     :default-value @token-name-ref
                     :hint-type (when (seq (:errors @name-errors)) "error")
                     :ref name-ref
                     :on-blur on-blur-name
                     :on-change on-update-name}])

       (for [error (->> (:errors @name-errors)
                        (map #(-> (assoc @name-errors :errors [%])
                                  (me/humanize)))
                        (map first))]

         [:> hint-message* {:key error
                            :message error
                            :type "error"
                            :id "token-name-hint"}])

       (when (and warning-name-change? (= action "edit"))
         [:div {:class (stl/css :warning-name-change-notification-wrapper)}
          [:> context-notification*
           {:level :warning :appearance :ghost} (tr "workspace.tokens.warning-name-change")]])]

      [:div {:class (stl/css :input-row)}
       (let [placeholder (tr "workspace.tokens.token-value-enter")
             label (tr "workspace.tokens.token-value")
             default-value (mf/ref-val value-ref)
             ref value-input-ref
             error (not (nil? (:errors token-resolve-result)))
             on-blur on-update-value]
         (if (fn? custom-input-token-value)
           [:> custom-input-token-value
            {:placeholder placeholder
             :label label
             :default-value default-value
             :input-ref ref
             :error error
             :on-blur on-blur
             :on-update-value on-update-value
             :on-external-update-value on-external-update-value
             :custom-input-token-value-props custom-input-token-value-props}]
           [:> input-tokens-value*
            {:placeholder placeholder
             :label label
             :default-value default-value
             :ref ref
             :error error
             :on-blur on-blur
             :on-change on-update-value}]))
       [:& token-value-hint {:result token-resolve-result}]]
      [:div {:class (stl/css :input-row)}
       [:> input* {:label (tr "workspace.tokens.token-description")
                   :placeholder (tr "workspace.tokens.token-description")
                   :is-optional true
                   :max-length max-input-length
                   :variant "comfortable"
                   :default-value @description-ref
                   :on-blur on-update-description
                   :on-change on-update-description}]]

      [:div {:class (stl/css-case :button-row true
                                  :with-delete (= action "edit"))}
       (when (= action "edit")
         [:> button* {:on-click on-delete-token
                      :on-key-down handle-key-down-delete
                      :class (stl/css :delete-btn)
                      :type "button"
                      :icon i/delete
                      :variant "secondary"}
          (tr "labels.delete")])
       [:> button* {:on-click on-cancel
                    :on-key-down handle-key-down-cancel
                    :type "button"
                    :id "token-modal-cancel"
                    :variant "secondary"}
        (tr "labels.cancel")]
       [:> button* {:type "submit"
                    :on-key-down handle-key-down-save
                    :variant "primary"
                    :disabled disabled?}
        (tr "labels.save")]]]]))

;; FIXME: this function has confusing name
(defn- hex->value
  [hex]
  (when-let [tc (tinycolor/valid-color hex)]
    (let [hex (tinycolor/->hex-string tc)
          alpha (tinycolor/alpha tc)
          [r g b] (c/hex->rgb hex)
          [h s v] (c/hex->hsv hex)]
      {:hex hex
       :r r :g g :b b
       :h h :s s :v v
       :alpha alpha})))

(mf/defc ramp*
  [{:keys [color on-change]}]
  (let [wrapper-node-ref (mf/use-ref nil)
        dragging-ref     (mf/use-ref false)

        on-start-drag
        (mf/use-fn #(mf/set-ref-val! dragging-ref true))

        on-finish-drag
        (mf/use-fn #(mf/set-ref-val! dragging-ref false))

        internal-color*
        (mf/use-state #(hex->value color))

        internal-color
        (deref internal-color*)

        on-change'
        (mf/use-fn
         (mf/deps on-change)
         (fn [{:keys [hex alpha] :as selector-color}]
           (let [dragging? (mf/ref-val dragging-ref)]
             (when-not (and dragging? hex)
               (reset! internal-color* selector-color)
               (on-change hex alpha)))))]
    (mf/use-effect
     (mf/deps color)
     (fn []
       ;; Update internal color when user changes input value
       (when-let [color (tinycolor/valid-color color)]
         (when-not (= (tinycolor/->hex-string color) (:hex internal-color))
           (reset! internal-color* (hex->value color))))))

    (colorpicker/use-color-picker-css-variables! wrapper-node-ref internal-color)
    [:div {:ref wrapper-node-ref}
     [:> ramp-selector*
      {:color internal-color
       :on-start-drag on-start-drag
       :on-finish-drag on-finish-drag
       :on-change on-change'}]]))

(mf/defc color-picker*
  [{:keys [placeholder label default-value input-ref error on-blur on-update-value on-external-update-value custom-input-token-value-props]}]
  (let [{:keys [color on-display-colorpicker]} custom-input-token-value-props
        color-ramp-open* (mf/use-state false)
        color-ramp-open? (deref color-ramp-open*)

        on-click-swatch
        (mf/use-fn
         (mf/deps color-ramp-open? on-display-colorpicker)
         (fn []
           (let [open? (not color-ramp-open?)]
             (reset! color-ramp-open* open?)
             (on-display-colorpicker open?))))

        swatch
        (mf/html
         [:> input-token-color-bullet*
          {:color color
           :class (stl/css :slot-start)
           :on-click on-click-swatch}])

        on-change'
        (mf/use-fn
         (mf/deps color on-external-update-value)
         (fn [hex-value alpha]
           (let [;; StyleDictionary will always convert to hex/rgba, so we take the format from the value input field
                 prev-input-color (some-> (dom/get-value (mf/ref-val input-ref))
                                          (tinycolor/valid-color))
                  ;; If the input is a reference we will take the format from the computed value
                 prev-computed-color (when-not prev-input-color
                                       (some-> color (tinycolor/valid-color)))
                 prev-format (some-> (or prev-input-color prev-computed-color)
                                     (tinycolor/color-format))
                 to-rgba? (and
                           (< alpha 1)
                           (or (= prev-format "hex") (not prev-format)))
                 to-hex? (and (not prev-format) (= alpha 1))
                 format (cond
                          to-rgba? "rgba"
                          to-hex? "hex"
                          prev-format prev-format
                          :else "hex")
                 color-value (-> (tinycolor/valid-color hex-value)
                                 (tinycolor/set-alpha (or alpha 1))
                                 (tinycolor/->string format))]
             (on-external-update-value color-value))))]

    [:*
     [:> input-tokens-value*
      {:placeholder placeholder
       :label label
       :default-value default-value
       :ref input-ref
       :error error
       :on-blur on-blur
       :on-change on-update-value
       :slot-start swatch}]
     (when color-ramp-open?
       [:> ramp*
        {:color (some-> color (tinycolor/valid-color))
         :on-change on-change'}])]))

(mf/defc color-form*
  [{:keys [token on-display-colorpicker] :rest props}]
  (let [color* (mf/use-state (:value token))
        color (deref color*)
        on-value-resolve (mf/use-fn
                          (mf/deps color)
                          (fn [value]
                            (reset! color* value)
                            value))

        custom-input-token-value-props
        (mf/use-memo
         (mf/deps color on-display-colorpicker)
         (fn []
           {:color color
            :on-display-colorpicker on-display-colorpicker}))

        transform-value
        (mf/use-fn
         (fn [value]
           (if (tinycolor/hex-without-hash-prefix? value)
             (dm/str "#" value)
             value)))]
    [:> form*
     (mf/spread-props props {:token token
                             :transform-value transform-value
                             :on-value-resolve on-value-resolve
                             :custom-input-token-value color-picker*
                             :custom-input-token-value-props custom-input-token-value-props})]))

(mf/defc font-selector-wrapper*
  [{:keys [font input-ref on-select-font on-close-font-selector]}]
  (let [current-font* (mf/use-state (or font
                                        (some-> (mf/ref-val input-ref)
                                                (dom/get-value)
                                                (ctt/split-font-family)
                                                (first)
                                                (fonts/find-font-family))))
        current-font (deref current-font*)]
    [:div {:class (stl/css :font-select-wrapper)}
     [:> font-selector* {:current-font current-font
                         :on-select on-select-font
                         :on-close on-close-font-selector
                         :full-size true}]]))

(mf/defc font-picker*
  [{:keys [default-value input-ref error on-blur on-update-value on-external-update-value]}]
  (let [font* (mf/use-state (fonts/find-font-family default-value))
        font (deref font*)
        set-font (mf/use-fn
                  (mf/deps font)
                  #(reset! font* %))

        font-selector-open* (mf/use-state false)
        font-selector-open? (deref font-selector-open*)

        on-close-font-selector
        (mf/use-fn
         (fn []
           (reset! font-selector-open* false)))

        on-click-dropdown-button
        (mf/use-fn
         (mf/deps font-selector-open?)
         (fn [e]
           (dom/prevent-default e)
           (reset! font-selector-open* (not font-selector-open?))))

        on-select-font
        (mf/use-fn
         (mf/deps on-external-update-value set-font)
         (fn [{:keys [family] :as font}]
           (when font
             (set-font font)
             (on-external-update-value family))))

        on-update-value'
        (mf/use-fn
         (mf/deps on-update-value set-font)
         (fn [value]
           (set-font nil)
           (on-update-value value)))

        font-selector-button
        (mf/html
         [:> icon-button*
          {:on-click on-click-dropdown-button
           :aria-label (tr "workspace.tokens.token-font-family-select")
           :icon "arrow-down"
           :variant "action"
           :type "button"}])]
    [:*
     [:> input-tokens-value*
      {:placeholder (tr "workspace.tokens.token-font-family-value-enter")
       :label (tr "workspace.tokens.token-font-family-value")
       :default-value default-value
       :ref input-ref
       :error error
       :on-blur on-blur
       :on-change on-update-value'
       :icon "text-font-family"
       :slot-end font-selector-button}]
     (when font-selector-open?
       [:> font-selector-wrapper* {:font font
                                   :input-ref input-ref
                                   :on-select-font on-select-font
                                   :on-close-font-selector on-close-font-selector}])]))

(mf/defc font-family-form*
  [{:keys [token] :rest props}]
  (let [on-value-resolve
        (mf/use-fn
         (fn [value]
           (when value
             (ctt/join-font-family value))))]
    [:> form*
     (mf/spread-props props {:token (when token (update token :value ctt/join-font-family))
                             :custom-input-token-value font-picker*
                             :on-value-resolve on-value-resolve})]))

(mf/defc text-case-form*
  [{:keys [token] :rest props}]
  (let [placeholder (tr "workspace.tokens.text-case-value-enter")]
    [:> form*
     (mf/spread-props props {:token token
                             :input-placeholder placeholder})]))

(mf/defc form-wrapper*
  [{:keys [token token-type] :as props}]
  (let [token-type' (or (:type token) token-type)]
    (case token-type'
      :color [:> color-form* props]
      :font-family [:> font-family-form* props]
      :text-case [:> text-case-form* props]
      [:> form* props])))
