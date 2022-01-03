'use strict';

angular.module('CentralDogmaAdmin')
    .directive('aceEditor',
               function () {
                 return {
                   template: '<div ng-class="editorClass" ' +
                             "     ui-ace=\"{ require: ['ace/ext/language_tools'], onLoad: aceLoaded }\"" +
                             '     data-ng-model="content"></div>' +
                             '<div class="ace-prefs form-inline">' +
                             '  <select class="form-control input-sm"' +
                             '          ng-model="theme"' +
                             '          ng-options="t.theme as t.caption group by t.type for t in availableThemes"' +
                             '          ng-change="updatePrefs()"></select>' +
                             '  <select class="form-control input-sm"' +
                             '          ng-model="keyboardHandler"' +
                             '          ng-options="h.value as h.name for h in availableKeyboardHandlers"' +
                             '          ng-change="updatePrefs()"></select>' +
                             '</div>',
                   restrict: 'E',
                   scope: {
                     'editorClass': '@class',
                     'path': '=pathNgModel',
                     'content': '=contentNgModel',
                     'readonly': '=',
                     'callback': '&'
                   },
                   controller: function ($scope, $timeout, $translate, localStorageService) {

                     // Load the theme list.
                     $scope.availableThemes = angular.copy(window.ace.require('ace/ext/themelist').themes);
                     $scope.defaultTheme = 'ace/theme/eclipse';

                     // Localize the theme type caption:
                     $translate('entities.light_themes').then(function (translated) {
                       for (var i in $scope.availableThemes) {
                         var t = $scope.availableThemes[i];
                         if (!t.isDark) {
                           t.type = translated;
                         }
                       }
                     });
                     $translate('entities.dark_themes').then(function (translated) {
                       for (var i in $scope.availableThemes) {
                         var t = $scope.availableThemes[i];
                         if (t.isDark) {
                           t.type = translated;
                         }
                       }
                     });

                     // Define the keyboard handler list.
                     $scope.availableKeyboardHandlers = [
                       { 'name': 'Ace',
                         'value': 'default' },
                       { 'name': 'Vim',
                         'value': 'ace/keyboard/vim' },
                       { 'name': 'Emacs',
                         'value': 'ace/keyboard/emacs' }
                     ];
                     $scope.defaultKeyboardHandler = $scope.availableKeyboardHandlers[0].value;

                     // Add '(Default)' to the theme and keyboard handler captions.
                     $translate('entities.default_theme').then(function (translated) {
                       for (var i in $scope.availableThemes) {
                         var t = $scope.availableThemes[i];
                         if (t.theme === $scope.defaultTheme) {
                           t.caption = t.caption + " (" + translated + ')';
                         }
                       }

                       for (var i in $scope.availableKeyboardHandlers) {
                         var h = $scope.availableKeyboardHandlers[i];
                         if (h.value === $scope.defaultKeyboardHandler) {
                           h.name = h.name + " (" + translated + ')';
                           break;
                         }
                       }
                     });

                     var KEY_ACE_PREFS = 'acePrefs';

                     // Configure the editor with the preferred theme and keyboard handler.
                     $scope.aceLoaded = function (editor) {
                       $scope.editor = editor;

                       editor.$blockScrolling = Infinity;
                       editor.setShowInvisibles(true);
                       editor.setAnimatedScroll(true);
                       editor.setShowFoldWidgets(true);
                       editor.setShowPrintMargin(true);
                       editor.setPrintMarginColumn(112);

                       $scope.loadPrefs(editor);

                       var options = {
                         showGutter: true,
                         showLineNumbers: true
                       };

                       if ($scope.readonly) {
                         editor.setReadOnly(true);
                       } else {
                         options.enableBasicAutocompletion = true;
                         options.enableLiveAutocompletion = true;
                       }

                       editor.setOptions(options);

                       var session = editor.getSession();
                       session.setUseWrapMode(true);
                       session.setUseSoftTabs(true);
                       session.setNewLineMode('unix');

                       var callback = $scope.callback;
                       if (typeof callback === 'function') {
                         callback = callback();
                         if (typeof callback === 'function') {
                           callback(editor);
                         }
                       }
                     };

                     // Auto-detect the edit mode and tab size from the file path.
                     $scope.$watch('path', function (value) {
                       if (typeof $scope.editor === 'undefined' || typeof value !== 'string') {
                         return;
                       }

                       var session = $scope.editor.getSession();
                       var modelist = window.ace.require('ace/ext/modelist');
                       var mode = modelist.getModeForPath(value).mode;
                       session.setMode(mode);
                       if (mode === 'ace/mode/json' || mode === 'ace/mode/json5') {
                         session.setTabSize(2);
                       } else {
                         session.setTabSize(4);
                       }
                     });

                     // Loads the preferred theme and keyboard handler.
                     $scope.loadPrefs = function (editor) {
                       var prefs = localStorageService.get(KEY_ACE_PREFS);

                       if (typeof prefs !== 'object' || prefs === null ||
                           typeof prefs.theme !== 'string' ||
                           typeof prefs.keyboardHandler !== 'string') {
                         prefs = {
                           theme: $scope.defaultTheme,
                           keyboardHandler: $scope.defaultKeyboardHandler
                         };
                       }

                       var themeSet = false;
                       for (var i in $scope.availableThemes) {
                         var t = $scope.availableThemes[i];
                         if (prefs.theme === t.theme) {
                           editor.setTheme(prefs.theme);
                           $scope.theme = prefs.theme;
                           themeSet = true;
                           break;
                         }
                       }

                       if (!themeSet) {
                         editor.setTheme($scope.defaultTheme);
                         $scope.theme = $scope.defaultTheme;
                       }

                       var keyboardHandlerSet = false;
                       if (prefs.keyboardHandler !== 'default') {
                         for (var i in $scope.availableKeyboardHandlers) {
                           var h = $scope.availableKeyboardHandlers[i];
                           if (prefs.keyboardHandler === h.value) {
                             editor.setKeyboardHandler(prefs.keyboardHandler);
                             $scope.keyboardHandler = prefs.keyboardHandler;
                             keyboardHandlerSet = true;
                             break;
                           }
                         }
                       }

                       if (!keyboardHandlerSet) {
                         editor.setKeyboardHandler(editor.commands);
                         $scope.keyboardHandler = $scope.defaultKeyboardHandler;
                       }
                     };

                     // Update and save the preferred theme and keyboard handler.
                     $scope.updatePrefs = function () {
                       if (typeof $scope.editor === 'undefined') {
                         return;
                       }

                       var editor = $scope.editor;
                       var theme = $scope.theme;
                       editor.setTheme(theme);

                       var keyboardHandler = $scope.keyboardHandler;
                       if (keyboardHandler === $scope.defaultKeyboardHandler) {
                         editor.setKeyboardHandler(editor.commands);
                       } else {
                         editor.setKeyboardHandler(keyboardHandler);
                       }

                       localStorageService.set(KEY_ACE_PREFS,
                                               { theme: theme, keyboardHandler: keyboardHandler });
                     };
                   }
                 };
               });
