'use strict';

angular.module('CentralDogmaAdmin')
    .controller('NavbarController',
                function ($scope, $rootScope, $state, $q, $uibModal,
                          Hostname, Auth, Principal) {

                  $scope.isAuthenticated = Principal.isAuthenticated;

                  Auth.isEnabled().then(function (data) {
                    $scope.isSecurityEnabled = data;
                  });

                  Hostname.get().then(function (data) {
                    $scope.hostname = data;
                  });

                  $scope.logout = function () {
                    Auth.logout();
                    $state.go('home');
                  };
                });
