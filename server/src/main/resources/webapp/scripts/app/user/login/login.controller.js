'use strict';

angular.module('CentralDogmaAdmin')
    .controller('LoginController',
                function ($scope, $state, $timeout, $uibModalInstance, Auth, NotificationUtil) {
                  $scope.login = function () {
                    Auth.login({
                      username: $scope.username,
                      password: $scope.password
                    }).then(function () {
                      $uibModalInstance.close({
                        translationId: 'login.logged_in',
                        interpolateParams: { username: $scope.username }
                      });
                      $state.go($state.current.name, {}, {reload: true});
                    }, function (error) {
                      if (typeof error.status !== 'undefined' && error.status === 401) {
                        NotificationUtil.error('login.auth_failed');
                      } else {
                        NotificationUtil.error(error);
                      }
                    });
                  };

                  $scope.cancel = function () {
                    $uibModalInstance.dismiss('');
                  };

                  $timeout(function () {
                    angular.element('[ng-model="username"]').focus();
                  });
                });
