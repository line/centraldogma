'use strict';

angular.module('CentralDogmaAdmin').controller('TokensController',
  function ($scope, $uibModal, SettingsService, ConfirmationDialog, NotificationUtil, EntitiesUtil) {
    $scope.sanitizeEmail = EntitiesUtil.sanitizeEmail;

    var refresh = function () {
      $scope.selectedToken = null;
      SettingsService.listTokens().then(
        function (tokens) {
          $scope.tokens = tokens;
        }
      );
    };

    $scope.selectToken = function (token) {
      if (token === $scope.selectedToken) {
        $scope.selectedToken = null;
      } else {
        $scope.selectedToken = token;
      }
    };

    $scope.showGenerateTokenDialog = function () {
      var modalInstance = $uibModal.open({
        templateUrl: 'scripts/app/settings/tokens/token.new.html',
        controller: 'TokenNewController',
        backdrop: 'static'
      });
      modalInstance.result.then(
        function (newToken) {
          if (angular.isDefined(newToken)) {
            $scope.newToken = newToken;
            $scope.showTokenGeneratedDialog();
          }
        });
    };
    $scope.showTokenGeneratedDialog = function () {
      var modalInstance = $uibModal.open({
        templateUrl: 'scripts/app/settings/tokens/token.generated.html',
        controller: 'TokenGeneratedController',
        backdrop: 'static',
        resolve: {
          newToken: function () {
            return $scope.newToken;
          }
        }
      });
      modalInstance.result.then(
        function () {
          $scope.selectedToken = null;
          refresh();
        }
      )
    };

    $scope.activateToken = function () {
      ConfirmationDialog.openModal('settings.title_activate_token', {
        token: $scope.selectedToken.appId
      }).then(function () {
        SettingsService.activateToken($scope.selectedToken.appId).then(function () {
          refresh();
        }, function (error) {
          NotificationUtil.error(error);
        });
      });
    };

    $scope.deactivateToken = function () {
      ConfirmationDialog.openModal('settings.title_deactivate_token', {
        token: $scope.selectedToken.appId
      }).then(function () {
        SettingsService.deactivateToken($scope.selectedToken.appId).then(function () {
          refresh();
        }, function (error) {
          NotificationUtil.error(error);
        });
      });
    };

    $scope.deleteToken = function () {
      ConfirmationDialog.openModal('settings.title_delete_token', {
        token: $scope.selectedToken.appId
      }).then(function () {
        SettingsService.deleteToken($scope.selectedToken.appId).then(function () {
          refresh();
        }, function (error) {
          NotificationUtil.error(error);
        });
      });
    };

    refresh();
  });
