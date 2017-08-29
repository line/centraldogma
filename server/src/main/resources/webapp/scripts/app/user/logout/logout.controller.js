'use strict';

angular.module('CentralDogmaAdmin')
    .controller('LogoutController', function (Auth, NotificationUtil) {
                  Auth.logout();
                });
