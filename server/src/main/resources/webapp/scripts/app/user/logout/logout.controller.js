'use strict';

angular.module('CentralDogmaAdmin')
    .controller('LogoutController', function (Auth, $state) {
                  Auth.logout().then(function() {
                    $state.go('home', {}, {reload: true});
                  });
                });
