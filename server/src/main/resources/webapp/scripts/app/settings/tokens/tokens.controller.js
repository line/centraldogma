'use strict';

angular.module('CentralDogmaAdmin').controller('TokensController',
  function ($scope, $uibModal, SettingsService) {

    var refresh = function () {
      SettingsService.listTokens().then(
        function (tokens) {
          var i;
          for (i in tokens) {
            tokens[i].creationTimeStr = moment(tokens[i].creationTime).fromNow();
          }
          $scope.tokens = tokens;
        }
      );
    };

    $scope.selectedToken = null;

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
          refresh();
        }
      )
    };

    $scope.showDeleteTokenDialog = function () {
      var modalInstance = $uibModal.open({
        templateUrl: 'scripts/app/settings/tokens/token.delete.html',
        controller: 'TokenDeleteController',
        resolve: {
          token: function () {
            return $scope.selectedToken;
          }
        }
      });
      modalInstance.result.then(
        function () {
          refresh();
        });
    };

    refresh();
  });
