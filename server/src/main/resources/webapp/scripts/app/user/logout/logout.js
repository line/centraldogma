'use strict';

angular.module('CentralDogmaAdmin')
    .config(function ($stateProvider) {
              $stateProvider
                  .state('logout', {
                    parent: 'user',
                    url: '/logout',
                    data: {
                      roles: []
                    },
                    views: {
                      'content@': {
                        templateUrl: 'scripts/app/main/main.html',
                        controller: 'LogoutController'
                      }
                    }
                  });
            });
