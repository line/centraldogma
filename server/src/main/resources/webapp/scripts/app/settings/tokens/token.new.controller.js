'use strict';

angular.module('CentralDogmaAdmin').controller('TokenNewController',
  function ($scope, $timeout, $uibModal, $uibModalInstance, $filter, SettingsService, NotificationUtil) {

    $scope.isAdmin = false;

    $scope.generateToken = function (isAdmin) {
      var data = 'appId=' + encodeURIComponent($scope.appId) + '&isAdmin=' + isAdmin;

      SettingsService.createToken(data).then(function (token) {
        $scope.newToken = token;
        $scope.newToken.creation.timestamp = moment(token.creationTime).fromNow();
        $uibModalInstance.close($scope.newToken);
      }, function (error) {
        if (typeof error.status !== 'undefined' && error.status === 409) {
          NotificationUtil.error('settings.token_application_id.exist', {appId: $scope.appId});
        } else {
          NotificationUtil.error(error);
        }
      });
    };

    $scope.close = function () {
      $uibModalInstance.close();
    };
  });
