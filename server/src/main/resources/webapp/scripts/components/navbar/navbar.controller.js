'use strict';

angular.module('CentralDogmaAdmin')
    .controller('NavbarController',
                function ($scope, $rootScope, $state, $q, $uibModal, $window,
                          Title, Principal) {

                  $scope.isAuthenticated = Principal.isAuthenticated;

                  Title.get().then(function (data) {
                    $scope.title = data.title;
                    $scope.hostname = data.hostname;
                  });
                });
