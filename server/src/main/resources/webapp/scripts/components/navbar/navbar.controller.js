'use strict';

angular.module('CentralDogmaAdmin')
    .controller('NavbarController',
                function ($scope, $rootScope, $state, $q, $uibModal, $window,
                          Hostname, Auth, Principal) {

                  $scope.isAuthenticated = Principal.isAuthenticated;

                  Hostname.get().then(function (data) {
                    $scope.hostname = data;
                  });

                  $scope.logout = function () {
                    Auth.logout().then(function() {
                      $state.go('home', {}, {reload: true});
                    });
                  };
                });
