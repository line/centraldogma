'use strict';

angular.module('CentralDogmaAdmin')
    .config(function ($stateProvider) {
              $stateProvider
                  .state('entity', {
                    abstract: true,
                    parent: 'site'
                  });
            });
