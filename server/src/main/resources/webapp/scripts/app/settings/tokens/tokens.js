'use strict';

angular.module('CentralDogmaAdmin').config(function ($stateProvider) {
  $stateProvider.state('tokens', {
    parent: 'setting',
    url: '/tokens',
    data: {},
    views: {
      'content@': {
        templateUrl: 'scripts/app/settings/tokens/tokens.html',
        controller: 'TokensController'
      }
    }
  });
});
