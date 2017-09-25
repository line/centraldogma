'use strict';

angular.module('CentralDogmaAdmin').controller('TokenGeneratedController',
  function ($scope, $timeout, $uibModalInstance, newToken) {
    $scope.newToken = newToken;
    $scope.close = function () {
      $uibModalInstance.close();
    };
  });
