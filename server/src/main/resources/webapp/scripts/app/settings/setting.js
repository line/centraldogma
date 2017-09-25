'use strict';

angular.module('CentralDogmaAdmin').config(function ($stateProvider) {
  $stateProvider.state('setting', {
    abstract: true,
    parent: 'site',
    url: "/settings"
  });
});
