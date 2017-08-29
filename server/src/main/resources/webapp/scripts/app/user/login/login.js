'use strict';

angular.module('CentralDogmaAdmin')
    .config(function ($stateProvider) {
              $stateProvider
                  .state('login', {
                    parent: 'user',
                    url: '/login',
                    data: {
                      pageTitle: 'login.title'
                    },
                    views: {
                      'content@': {
                        templateUrl: 'scripts/app/user/login/login.html',
                        controller: 'LoginController'
                      }
                    }
                  });
            });
