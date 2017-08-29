'use strict';

angular.module('CentralDogmaAdmin')
    .config(function ($stateProvider) {
              $stateProvider
                  .state('user', {
                    abstract: true,
                    parent: 'site'
                  });
            });
