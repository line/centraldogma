'use strict';

angular.module('CentralDogmaAdmin')
    .controller('LogoutController', function (Auth, $state) {
                  Auth.logout();
                  $state.go($state.current.name, {}, {reload: true});
                });
