'use strict';

angular.module(
    'CentralDogmaAdmin',
    ['LocalStorageModule',
     'ngCacheBuster',
     'ngCookies',
     'ngResource',
     'pascalprecht.translate',
     'tmh.dynamicLocale',
     'ui.ace',
     'ui.bootstrap',
     'ui-notification',
     'ui.router'])
    .constant('CentralDogmaConstant', {
      HEAD: 'head',
      LEVEL_USER: 'LEVEL_USER',
      LEVEL_ADMIN: 'LEVEL_ADMIN',
      ENTITY_NAME_PATTERN: /^[0-9A-Za-z](?:[-+_0-9A-Za-z\.]*[0-9A-Za-z])?$/,
      API_PREFIX: 'api/v0/',
      API_V1_PREFIX: 'api/v1/',
      PROJECT_ROLE_OWNER: "OWNER",
      PROJECT_ROLE_MEMBER: "MEMBER",
      PROJECT_ROLE_GUEST: "GUEST"
    })
    .run(function ($rootScope, $location, $window, $http, $state, $translate, $uibModal,
                   Principal, Language, NotificationUtil, Security) {
           $rootScope.showLoginDialog = function () {
             var modalInstance = $uibModal.open({
               templateUrl: 'scripts/app/user/login/login.html',
               controller: 'LoginController'
             });

             modalInstance.result.then(function (message) {
               NotificationUtil.success(message);
             });
           };

           $rootScope.$on('$stateChangeStart', function (event, toState, toStateParams) {
             $rootScope.toState = toState;
             $rootScope.toStateParams = toStateParams;

             Security.resolve().then(function () {
               Principal.refresh();
             });

             // Update the language
             Language.getCurrent().then(function (language) {
               $translate.use(language);
             });
           });

           $rootScope.$on('$stateChangeSuccess', function (event, toState, toParams, fromState, fromParams) {
             var titleKey = 'window.title';

             $rootScope.previousStateName = fromState.name;
             $rootScope.previousStateParams = fromParams;

             // Set the page title key to the one configured in state or use default one
             if (toState.data.pageTitle) {
               titleKey = toState.data.pageTitle;
             }
             $translate(titleKey).then(function (title) {
               // Change window title with translated one
               $window.document.title = title;
             });
           });

           $rootScope.back = function () {
             // If previous state do not exist go to 'home'
             if ($state.get($rootScope.previousStateName) === null) {
               $state.go('home');
             } else {
               $state.go($rootScope.previousStateName, $rootScope.previousStateParams);
             }
           };
         })
    .config(function ($stateProvider, $urlRouterProvider, $urlMatcherFactoryProvider,
                      $httpProvider, $locationProvider, $translateProvider,
                      tmhDynamicLocaleProvider, httpRequestInterceptorCacheBusterProvider,
                      NotificationProvider) {

              //Cache everything except rest api requests
              httpRequestInterceptorCacheBusterProvider.setMatchlist([/.*api.*/, /.*protected.*/], true);

              $urlMatcherFactoryProvider.type('repositoryPath', {
                encode: function (val) {
                  var temp = val !== null ? val.toString() : val;
                  temp = temp.replace(/\/\/+/, '/');
                  if (temp === '/') {
                    return '';
                  }

                  if (temp.indexOf('/') === 0) {
                    temp = temp.substring(1);
                  }
                  if (/\/$/.test(temp)) {
                    temp = temp.slice(0, -1);
                  }

                  var split = temp.split('/');
                  for (var index in split) {
                    split[index] = encodeURIComponent(split[index]);
                  }
                  return split.join('/');
                },
                decode: function (val) {
                  return val !== null ? decodeURIComponent(val.toString()) : val;
                },
                is: function () {
                  return true;
                }
              });

              $urlRouterProvider.otherwise('/projects');
              $stateProvider
                  .state('site', {
                    'abstract': true,
                    views: {
                      'navbar@': {
                        templateUrl: 'scripts/components/navbar/navbar.html',
                        controller: 'NavbarController'
                      }
                    },
                    resolve: {
                      translatePartialLoader: [
                        '$translate', '$translatePartialLoader',
                        function ($translate, $translatePartialLoader) {
                          $translatePartialLoader.addPart('main');
                          return $translate.refresh();
                        }]
                    }
                  })
                  .state('home', {
                    parent: 'site',
                    url: '/',
                    data: {
                      roles: []
                    },
                    views: {
                      'content@': {
                        templateUrl: 'scripts/app/entities/projects/projects.html',
                        controller: 'ProjectsController'
                      }
                    }
                  });

              // Initialize angular-translate
              $translateProvider.useLoader('$translatePartialLoader', {
                urlTemplate: 'i18n/{lang}.{part}.json'
              });

              $translateProvider.preferredLanguage('en');
              $translateProvider.useCookieStorage();
              $translateProvider.useSanitizeValueStrategy('escapeParameters');

              tmhDynamicLocaleProvider
                  .localeLocationPattern('node_modules/angular-i18n/angular-locale_{{locale}}.js');
              tmhDynamicLocaleProvider.useCookieStorage('NG_TRANSLATE_LANG_KEY');

              // Configure angular-ui-notification
              NotificationProvider.setOptions({
                delay: 5000,
                startTop: 70,
                startRight: 0,
                verticalSpacing: 20,
                horizontalSpacing: 20,
                positionX: 'center',
                positionY: 'top'
              });
            });
