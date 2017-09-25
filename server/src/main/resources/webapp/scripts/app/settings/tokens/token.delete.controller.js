'use strict';

angular.module('CentralDogmaAdmin').controller('TokenDeleteController',
  function ($scope, $uibModalInstance, token, StringUtil, SettingsService) {

    $scope.token = token;
    $scope.message = {
      summary: '',
      detail: {
        content: '',
        markup: 'PLAINTEXT'
      }
    };

    $scope.deleteToken = function () {
      if (StringUtil.isEmpty($scope.message.summary)) {
        $scope.message.summary = 'Delete ' + $scope.token;
      }

      SettingsService.deleteToken($scope.token.appId).then(
        function () {
          $uibModalInstance.close({
            translationId: 'settings.deleted_token',
            interpolateParams: {path: $scope.id}
          });
        }, function (error) {
          $uibModalInstance.dismiss(error.message);
        });
    };

    $scope.cancel = function () {
      $uibModalInstance.dismiss('');
    };
  });
